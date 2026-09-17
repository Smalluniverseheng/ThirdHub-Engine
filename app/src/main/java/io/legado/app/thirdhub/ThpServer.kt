package io.legado.app.thirdhub

import fi.iki.elonen.NanoHTTPD
import io.legado.app.api.ReturnData
import io.legado.app.api.controller.BookController
import io.legado.app.api.controller.EngineSearchController
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.GSON
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * THP/1.0 引擎服务端: 让 ThirdHub 前端/后端按 THP 协议直接调用本引擎
 * 端口 1234(与 EngineBridge 的 UDP HELLO 广播一致), 匿名无鉴权
 *
 * 端点(THP v1 最小集):
 *   GET /thp/meta                        → 引擎名片(name/caps/version/auth)
 *   GET /thp/search?type=novel&q=关键词   → {data:{items:[{id,name,author,coverUrl,intro,kind}]}}
 *   GET /thp/chapters?type=novel&id=书URL → {data:{items:[{name,url,index}]}}
 *   GET /thp/content?type=novel&id=书URL&chapter=章节URL → {data:{text}}
 * 说明: 搜索/目录/正文全部委托 Legado 官方控制器(EngineSearchController/BookController),
 *       规则解析 100% 由 Legado 引擎本体完成, 本层只做协议转换。
 */
class ThpServer(port: Int = 1234) : NanoHTTPD(port) {

    companion object {
        const val PORT = 1234
        const val VERSION = "thp-engine-1.2.0"
        private var instance: ThpServer? = null

        @Synchronized
        fun ensureStarted() {
            if (instance?.isAlive == true) return
            runCatching {
                instance = ThpServer(PORT).apply { start(SOCKET_READ_TIMEOUT, false) }
            }
        }

        @Synchronized
        fun stop() {
            runCatching { instance?.stop() }
            instance = null
        }
    }

    // 搜索结果缓存: bookUrl → 搜索结果字段(目录阶段需要 origin 等信息补入库)
    private val searchCache = ConcurrentHashMap<String, Map<String, Any?>>()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        if (!uri.startsWith("/thp/")) return json(404, err("not_found", "未知端点"))
        return try {
            when {
                uri == "/thp/meta" -> json(200, JSONObject()
                    .put("object", "meta")
                    .put("data", JSONObject()
                        .put("name", "阅读引擎")
                        .put("version", VERSION)
                        .put("caps", JSONArray().put("novel").put("comic").put("audio"))
                        .put("auth", JSONArray().put("none"))))
                uri == "/thp/search" -> search(session.parms)
                uri == "/thp/chapters" -> chapters(session.parms)
                uri == "/thp/content" -> content(session.parms)
                uri == "/thp/discover" -> json(404, err("unsupported", "发现页暂不支持, 请用搜索"))
                else -> json(404, err("not_found", "未知端点"))
            }
        } catch (e: Exception) {
            json(500, err("engine_error", e.message ?: "引擎内部错误"))
        }
    }

    // ── 搜索: 复用 EngineSearchController(官方 SearchModel 全量书源并发) ──
    private fun search(parms: Map<String, List<String>>): Response {
        val q = parms["q"]?.firstOrNull()?.trim()
        val type = parms["type"]?.firstOrNull() ?: "novel"
        if (q.isNullOrEmpty()) return json(400, err("invalid_request", "缺参数 q"))
        // THP type → legado sourceType: novel→0(text) comic→2(image) music→1(audio)
        val wantType = when (type) { "comic" -> 2; "music" -> 1; else -> 0 }
        val rd = EngineSearchController.search(mapOf("key" to listOf(q)))
        if (!rd.isSuccess) return json(502, err("source_error", rd.errorMsg ?: "搜索失败"))
        @Suppress("UNCHECKED_CAST")
        val raw = (rd.data as? List<Map<String, Any?>>) ?: emptyList()
        val items = JSONArray()
        for (b in raw) {
            val st = (b["sourceType"] as? Int) ?: 0
            if (st != wantType) continue
            val bookUrl = (b["bookUrl"] as? String) ?: continue
            searchCache[bookUrl] = b
            if (searchCache.size > 500) searchCache.remove(searchCache.keys.first())
            items.put(JSONObject()
                .put("id", bookUrl)
                .put("name", b["name"] ?: "")
                .put("author", b["author"] ?: "")
                .put("coverUrl", b["coverUrl"] ?: "")
                .put("intro", (b["intro"] as? String ?: "").take(200))
                .put("kind", b["kind"] ?: ""))
        }
        return json(200, JSONObject().put("object", "list").put("data", JSONObject().put("items", items)))
    }

    // ── 目录: 书不在库时按搜索缓存补登, 再走官方 refreshToc ──
    private fun chapters(parms: Map<String, List<String>>): Response {
        val id = parms["id"]?.firstOrNull()
        if (id.isNullOrEmpty()) return json(400, err("invalid_request", "缺参数 id"))
        ensureBook(id)
        val rd = BookController.getChapterList(mapOf("url" to listOf(id)))
        if (!rd.isSuccess) return json(502, err("source_error", rd.errorMsg ?: "目录获取失败"))
        val list = (rd.data as? List<*>) ?: emptyList<Any>()
        val items = JSONArray()
        for (c in list) {
            if (c !is BookChapter) continue
            items.put(JSONObject()
                .put("name", c.title)
                .put("url", c.url)
                .put("index", c.index))
        }
        return json(200, JSONObject().put("object", "list").put("data", JSONObject().put("items", items)))
    }

    // ── 正文: 章节URL → 反查 index → 官方 getBookContent ──
    private fun content(parms: Map<String, List<String>>): Response {
        val id = parms["id"]?.firstOrNull()
        val chapter = parms["chapter"]?.firstOrNull()
        if (id.isNullOrEmpty() || chapter.isNullOrEmpty()) return json(400, err("invalid_request", "缺参数 id/chapter"))
        ensureBook(id)
        // chapter 可能是序号也可能是 URL
        var index = chapter.toIntOrNull()
        if (index == null) {
            val toc = appDb.bookChapterDao.getChapterList(id)
            val hit = toc.firstOrNull { it.url == chapter }
            index = hit?.index
        }
        if (index == null) return json(404, err("not_found", "章节不存在"))
        val rd = BookController.getBookContent(mapOf("url" to listOf(id), "index" to listOf(index.toString())))
        if (!rd.isSuccess) return json(502, err("source_error", rd.errorMsg ?: "正文获取失败"))
        return json(200, JSONObject().put("object", "novel-content")
            .put("data", JSONObject().put("text", (rd.data as? String) ?: "")))
    }

    // 书不在 Legado 库 → 用搜索缓存构造 Book 入库(tocUrl 留空, refreshToc 会自动取详情)
    private fun ensureBook(bookUrl: String) {
        if (appDb.bookDao.getBook(bookUrl) != null) return
        val c = searchCache[bookUrl] ?: return
        val book = Book(
            bookUrl = bookUrl,
            origin = (c["origin"] as? String) ?: "",
            originName = (c["originName"] as? String) ?: "",
            name = (c["name"] as? String) ?: "",
            author = (c["author"] as? String) ?: "",
            kind = (c["kind"] as? String),
            coverUrl = (c["coverUrl"] as? String),
            intro = (c["intro"] as? String),
            type = (c["sourceType"] as? Int) ?: 0,
        )
        runCatching { book.save() }
    }

    private fun json(code: Int, obj: JSONObject): Response {
        val status = Response.Status.values().firstOrNull { it.requestStatus == code } ?: Response.Status.OK
        val r = newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString())
        r.addHeader("Access-Control-Allow-Origin", "*")
        return r
    }

    private fun err(type: String, msg: String) = JSONObject()
        .put("object", "error")
        .put("data", JSONObject().put("type", type).put("message", msg))
}
