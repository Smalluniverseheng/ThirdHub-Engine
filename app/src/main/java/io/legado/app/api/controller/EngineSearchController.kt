package io.legado.app.api.controller

import io.legado.app.api.ReturnData
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.model.webBook.SearchModel
import io.legado.app.ui.book.search.SearchScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ThirdHub 引擎扩展: 把 WebSocket 搜索包装成普通 HTTP GET
 * 后端(数据库)无需 ws 客户端, 直接 GET /searchBookHttp?key=xxx 即可调用本引擎
 * 返回同时携带 sourceType, 后端据此归一化输出(小说text/漫画images/音频audio)
 */
object EngineSearchController {

    fun search(parameters: Map<String, List<String>>): ReturnData {
        val key = parameters["key"]?.firstOrNull()?.trim()
        if (key.isNullOrEmpty()) return ReturnData().setErrorMsg("参数key不能为空")
        val results = CopyOnWriteArrayList<SearchBook>()
        val done = CountDownLatch(1)
        val callBack = object : SearchModel.CallBack {
            override fun getSearchScope(): SearchScope = SearchScope(AppConfig.searchScope)
            override fun onSearchStart() {}
            override fun onSearchProgress(searched: Int, total: Int) {}
            override fun onSearchSuccess(searchBooks: List<SearchBook>) { results.addAll(searchBooks) }
            override fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean) { done.countDown() }
            override fun onSearchCancel(exception: Throwable?) { done.countDown() }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return try {
            val model = SearchModel(scope, callBack)
            model.search(System.currentTimeMillis(), key)
            done.await(25, TimeUnit.SECONDS)
            runCatching { model.close() }
            // 附带书源类型, 方便后端按模块路由(0小说 1音频 2漫画)
            val list = results.map { b ->
                mapOf(
                    "name" to b.name, "author" to (b.author ?: ""),
                    "kind" to (b.kind ?: ""), "coverUrl" to (b.coverUrl ?: ""),
                    "intro" to (b.intro ?: ""), "bookUrl" to b.bookUrl,
                    "origin" to b.origin, "originName" to b.originName,
                    "sourceType" to b.type, "typeName" to when (b.type) { 0 -> "text"; 1 -> "audio"; 2 -> "image"; else -> "unknown" }
                )
            }
            ReturnData().setData(list)
        } catch (e: Exception) {
            ReturnData().setErrorMsg("搜索失败: ${e.message}")
        } finally {
            scope.cancel()
        }
    }
}
