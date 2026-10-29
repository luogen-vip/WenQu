package com.wenqu.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URL

/**
 * 搜索引擎抓取层。
 * 每个引擎独立解析；失败自动换 UA 重试一次；解析失败走通用兜底提取器。
 * 上层 Aggregator 会并行调度全部引擎，单个引擎限额/被反爬不影响整体。
 */

abstract class Engine(val id: String, val name: String, val experimental: Boolean = false) {
    open val reliability = 0.0

    abstract fun url(q: String, page: Int): String
    abstract fun parse(doc: Document, n: Int): List<WebResult>
    open fun referer(): String? = null
    open fun extraHeaders(): Map<String, String> = emptyMap()
    open fun isPost(): Boolean = false
    open fun postBody(q: String): String = ""

    suspend fun search(q: String, n: Int, deep: Boolean): List<WebResult> = withContext(Dispatchers.IO) {
        var out = emptyList<WebResult>()
        repeat(2) { attempt ->
            if (out.isNotEmpty()) return@repeat
            val target = url(q, 1)
            val base = target.substringBefore('?')
            val html = if (isPost())
                Http.postForm(target, mapOf("q" to q), referer(), if (attempt == 0) Http.randomUA() else Http.mobileUA())
            else
                Http.get(target, referer(), if (attempt == 0) Http.randomUA() else Http.mobileUA(), extraHeaders())
            html ?: return@repeat
            out = try { parse(Jsoup.parse(html, base), n) } catch (e: Exception) { emptyList() }
        }
        out
    }

    protected fun collect(doc: Document, name: String, n: Int): MutableList<WebResult> = ArrayList()

    companion object {
        /** 通用兜底提取器：从任意 HTML 页面里抓"像结果"的链接（用于结构多变的站点） */
        fun genericExtract(doc: Document, engine: String, n: Int): List<WebResult> {
            val deny = listOf(
                "google.com", "gstatic.com", "bing.com/acs", "duckduckgo.com", "mojeek.com",
                "searx", "brave.com", "wikipedia.org/w", "search.yahoo.com", "yandex.com",
                "baidu.com/s", "sogou.com/web", "so.com/s?", "sm.cn/s?", "toutiao.com/search",
                "douyin.com/?", "metaso.cn/?", "bilibili.com/", "github.com", "apache.org",
                "miitbeian", "beian.gov", "example.com", "w3.org", "go.microsoft", "microsoft.com/zh-cn/download",
                "/login", "/signin", "javascript:"
            )
            val junk = Regex("^(首页|下一页|上一页|登录|注册|下载|客户端|帮助|反馈|关于我们|设置|隐私|更多|贴吧|知道|文库|地图|图片|视频|新闻|网页|资讯|更多结果|Sign in|Sign up|Log in|Next|Previous)$")
            val out = ArrayList<WebResult>()
            val seen = HashSet<String>()
            for (a in doc.select("a[href]")) {
                if (out.size >= n) break
                var href = a.absUrl("href")
                if (href.isEmpty()) href = a.attr("href")
                if (!href.startsWith("http")) continue
                val low = href.lowercase()
                if (deny.any { low.contains(it) }) continue
                val title = a.text().replace(Regex("\\s+"), " ").trim()
                if (title.length < 7 || title.length > 90) continue
                if (junk.matches(title)) continue
                if (!seen.add(href.substringBefore('#'))) continue
                var snippet = ""
                var p: Element? = a.parent()
                var hops = 0
                while (p != null && hops < 4) {
                    val t = p.text().replace(title, " ").replace(Regex("\\s+"), " ").trim()
                    if (t.length in 20..400) { snippet = t.take(200); break }
                    p = p.parent(); hops++
                }
                out.add(WebResult(title, snippet, href, engine))
            }
            return out
        }
    }
}

// ---------------- 各引擎实现 ----------------

class BingEngine(private val base: String = "https://www.bing.com", eid: String = "bing", ename: String = "必应") :
    Engine(eid, ename) {
    override val reliability = 0.4
    override fun url(q: String, page: Int) = "$base/search?q=${Nlp.enc(q)}&count=15&setlang=zh-CN&mkt=zh-CN"
    override fun referer() = base
    override fun parse(doc: Document, n: Int): List<WebResult> {
        val out = ArrayList<WebResult>()
        for (el in doc.select("li.b_algo")) {
            if (out.size >= n) break
            val a = el.selectFirst("h2 a") ?: continue
            val title = a.text().trim()
            val u = a.absUrl("href")
            if (title.isEmpty() || !u.startsWith("http")) continue
            val sn = el.selectFirst(".b_caption p, p")?.text()?.trim() ?: ""
            out.add(WebResult(title, sn, u, name))
        }
        if (out.isEmpty()) out.addAll(Engine.genericExtract(doc, name, n))
        return out
    }
}

class BaiduEngine : Engine("baidu", "百度") {
    override val reliability = 0.2
    override fun url(q: String, page: Int) = "https://www.baidu.com/s?wd=${Nlp.enc(q)}&rn=15&ie=utf-8"
    override fun referer() = "https://www.baidu.com/"
    override fun extraHeaders() = mapOf(
        "Cookie" to "BAIDUID=${Http.randomHex(32)}:FG=1; BIDUPSID=${Http.randomHex(32)}; PSTM=${System.currentTimeMillis() / 1000}"
    )
    override fun parse(doc: Document, n: Int): List<WebResult> {
        val out = ArrayList<WebResult>()
        for (el in doc.select("div.result, div.c-container")) {
            if (out.size >= n) break
            val a = el.selectFirst("h3 a") ?: continue
            val title = a.text().trim()
            val u = a.absUrl("href")
            if (title.isEmpty() || u.isEmpty()) continue
            val sn = (el.selectFirst(".c-abstract, span[class*=abstract], div[class*=content-right]")?.text()
                ?: el.text()).take(200)
            out.add(WebResult(title, sn, u, name))
        }
        if (out.isEmpty()) out.addAll(Engine.genericExtract(doc, name, n))
        return out
    }
}

class SogouEngine : Engine("sogou", "搜狗") {
    override val reliability = 0.1
    override fun url(q: String, page: Int) = "https://www.sogou.com/web?query=${Nlp.enc(q)}&num=15"
    override fun referer() = "https://www.sogou.com/"
    override fun parse(doc: Document, n: Int): List<WebResult> {
        val out = ArrayList<WebResult>()
        for (el in doc.select("div.vrwrap, div.rb")) {
            if (out.size >= n) break
            val a = el.selectFirst("h3 a") ?: continue
            val title = a.text().trim()
            val u = a.absUrl("href")
            if (title.isEmpty() || u.isEmpty()) continue
            val sn = (el.selectFirst(".space-txt, .str-text-info, .str_info, .text-layout, p")?.text() ?: "").take(200)
            out.add(WebResult(title, sn, u, name))
        }
        if (out.isEmpty()) out.addAll(Engine.genericExtract(doc, name, n))
        return out
    }
}

class So360Engine : Engine("so360", "360搜索") {
    override val reliability = 0.1
    override fun url(q: String, page: Int) = "https://www.so.com/s?q=${Nlp.enc(q)}&pn=1"
    override fun referer() = "https://www.so.com/"
    override fun parse(doc: Document, n: Int): List<WebResult> {
        val out = ArrayList<WebResult>()
        for (el in doc.select("li.res-list")) {
            if (out.size >= n) break
            val a = el.selectFirst("h3 a") ?: continue
            val title = a.text().trim()
            val u = a.absUrl("href")
            if (title.isEmpty() || u.isEmpty()) continue
            val sn = (el.selectFirst("p.res-desc, p.res-linkinfo, p")?.text() ?: "").take(200)
            out.add(WebResult(title, sn, u, name))
        }
        if (out.isEmpty()) out.addAll(Engine.genericExtract(doc, name, n))
        return out
    }
}

class ShenmaEngine : Engine("shenma", "神马", experimental = true) {
    override fun url(q: String, page: Int) = "https://m.sm.cn/s?q=${Nlp.enc(q)}&from=smor"
    override fun referer() = "https://m.sm.cn/"
    override fun parse(doc: Document, n: Int): List<WebResult> = Engine.genericExtract(doc, name, n)
}

class ToutiaoEngine : Engine("toutiao", "头条", experimental = true) {
    override fun url(q: String, page: Int) = "https://so.toutiao.com/search?dvpf=pc&source=input&keyword=${Nlp.enc(q)}"
    override fun referer() = "https://so.toutiao.com/"
    override fun parse(doc: Document, n: Int): List<WebResult> {
        val out = ArrayList<WebResult>()
        for (el in doc.select("div.cs-source, article, div[class*=result]")) {
            if (out.size >= n) break
            val a = el.selectFirst("a[href]") ?: continue
            val title = a.text().trim()
            val u = a.absUrl("href")
            if (title.length < 8 || !u.startsWith("http")) continue
            val sn = (el.selectFirst("div[class*=desc], p")?.text() ?: "").take(200)
            out.add(WebResult(title, sn, u, name))
        }
        if (out.isEmpty()) out.addAll(Engine.genericExtract(doc, name, n))
        return out
    }
}

class DouyinEngine : Engine("douyin", "抖音", experimental = true) {
    override fun url(q: String, page: Int) = "https://www.douyin.com/search/${Nlp.enc(q)}"
    override fun referer() = "https://www.douyin.com/"
    override fun parse(doc: Document, n: Int): List<WebResult> = Engine.genericExtract(doc, name, n)
}

class MetasoEngine : Engine("metaso", "秘塔AI", experimental = true) {
    override fun url(q: String, page: Int) = "https://metaso.cn/?q=${Nlp.enc(q)}"
    override fun referer() = "https://metaso.cn/"
    override fun parse(doc: Document, n: Int): List<WebResult> {
        val out = ArrayList<WebResult>()
        for (el in doc.select("a[href]")) {
            if (out.size >= n) break
            val u = el.absUrl("href")
            val title = el.text().trim()
            if (u.startsWith("http") && !u.contains("metaso.cn") && title.length in 8..90) {
                out.add(WebResult(title, "", u, name))
            }
        }
        if (out.isEmpty()) out.addAll(Engine.genericExtract(doc, name, n))
        return out
    }
}

class DdgEngine : Engine("ddg", "DuckDuckGo") {
    override val reliability = 0.4
    override fun url(q: String, page: Int) = "https://lite.duckduckgo.com/lite/"
    override fun referer() = "https://duckduckgo.com/"
    override fun isPost() = true
    override fun postBody(q: String) = ""
    override suspend fun search(q: String, n: Int, deep: Boolean): List<WebResult> = withContext(Dispatchers.IO) {
        var out = emptyList<WebResult>()
        repeat(2) { attempt ->
            if (out.isNotEmpty()) return@repeat
            val html = Http.postForm(
                "https://lite.duckduckgo.com/lite/", mapOf("q" to q),
                "https://duckduckgo.com/", if (attempt == 0) Http.randomUA() else Http.mobileUA()
            ) ?: return@repeat
            out = try { parse(Jsoup.parse(html, "https://lite.duckduckgo.com/"), n) } catch (e: Exception) { emptyList() }
        }
        out
    }
    override fun parse(doc: Document, n: Int): List<WebResult> {
        val out = ArrayList<WebResult>()
        for (a in doc.select("a.result-link")) {
            if (out.size >= n) break
            val title = a.text().trim()
            val u = Nlp.unwrapRedirect(a.absUrl("href"))
            if (title.isEmpty() || !u.startsWith("http")) continue
            val tr = a.parents().firstOrNull { it.tagName() == "tr" }
            val sn = tr?.nextElementSibling()?.selectFirst("td.result-snippet")?.text()?.trim() ?: ""
            out.add(WebResult(title, sn, u, name))
        }
        return out
    }
}

class MojeekEngine : Engine("mojeek", "Mojeek") {
    override val reliability = 0.2
    override fun url(q: String, page: Int) = "https://www.mojeek.com/search?q=${Nlp.enc(q)}"
    override fun referer() = "https://www.mojeek.com/"
    override fun parse(doc: Document, n: Int): List<WebResult> {
        val out = ArrayList<WebResult>()
        for (el in doc.select("ul.results-standard li, li.result")) {
            if (out.size >= n) break
            val a = el.selectFirst("h2 a, a.title") ?: continue
            val title = a.text().trim()
            val u = a.absUrl("href")
            if (title.isEmpty() || !u.startsWith("http")) continue
            val sn = (el.selectFirst("p.s, p")?.text() ?: "").take(200)
            out.add(WebResult(title, sn, u, name))
        }
        if (out.isEmpty()) out.addAll(Engine.genericExtract(doc, name, n))
        return out
    }
}

class SearxEngine : Engine("searx", "SearXNG") {
    override val reliability = 0.2
    override fun url(q: String, page: Int) = "https://searx.be/search?q=${Nlp.enc(q)}&language=zh-CN"
    override fun referer() = "https://searx.be/"
    override fun parse(doc: Document, n: Int): List<WebResult> {
        val out = ArrayList<WebResult>()
        for (el in doc.select("article.result, div.result")) {
            if (out.size >= n) break
            val a = el.selectFirst("h3 a, a.url_wrapper") ?: continue
            val title = a.text().trim()
            val u = a.absUrl("href")
            if (title.isEmpty() || !u.startsWith("http")) continue
            val sn = (el.selectFirst("p.content, p")?.text() ?: "").take(200)
            out.add(WebResult(title, sn, u, name))
        }
        if (out.isEmpty()) out.addAll(Engine.genericExtract(doc, name, n))
        return out
    }
}

class BraveEngine : Engine("brave", "Brave", experimental = true) {
    override fun url(q: String, page: Int) = "https://search.brave.com/search?q=${Nlp.enc(q)}"
    override fun referer() = "https://search.brave.com/"
    override fun parse(doc: Document, n: Int): List<WebResult> {
        val out = ArrayList<WebResult>()
        for (el in doc.select("div.snippet, .result")) {
            if (out.size >= n) break
            val a = el.selectFirst("a[href^=http]") ?: continue
            val title = (el.selectFirst(".title, .snippet-title")?.text() ?: a.text()).trim()
            val u = a.absUrl("href")
            if (title.length < 7 || !u.startsWith("http")) continue
            val sn = (el.selectFirst(".snippet-description, .snippet-content, p")?.text() ?: "").take(200)
            out.add(WebResult(title, sn, u, name))
        }
        if (out.isEmpty()) out.addAll(Engine.genericExtract(doc, name, n))
        return out
    }
}

class WikiEngine : Engine("wiki", "维基百科") {
    override val reliability = 0.5
    override fun url(q: String, page: Int) =
        "https://zh.wikipedia.org/w/api.php?action=query&list=search&srsearch=${Nlp.enc(q)}&srlimit=8&format=json&utf8=1"

    override suspend fun search(q: String, n: Int, deep: Boolean): List<WebResult> = withContext(Dispatchers.IO) {
        val html = Http.get(url(q, 1)) ?: return@withContext emptyList()
        try {
            val arr = JSONObject(html).getJSONObject("query").getJSONArray("search")
            (0 until minOf(arr.length(), n)).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val title = o.optString("title")
                if (title.isEmpty()) null else {
                    val sn = org.jsoup.Jsoup.parse(o.optString("snippet")).text()
                    val u = "https://zh.wikipedia.org/wiki/" + java.net.URLEncoder.encode(title, "UTF-8").replace("+", "_")
                    WebResult(title, sn, u, name)
                }
            }
        } catch (e: Exception) { emptyList() }
    }
    override fun parse(doc: Document, n: Int): List<WebResult> = emptyList()
}

/** 引擎注册表 */
object Engines {
    fun all(): List<Engine> = listOf(
        BingEngine("https://www.bing.com"),
        BingEngine("https://cn.bing.com", "bingcn", "必应中国"),
        BaiduEngine(),
        SogouEngine(),
        So360Engine(),
        ShenmaEngine(),
        ToutiaoEngine(),
        DouyinEngine(),
        MetasoEngine(),
        DdgEngine(),
        MojeekEngine(),
        SearxEngine(),
        BraveEngine(),
        WikiEngine()
    )

    private val cache: List<Engine> by lazy { all() }
    fun cached(): List<Engine> = cache
    fun allIds(): List<String> = cache.map { it.id }
    fun byId(id: String): Engine? = cache.firstOrNull { it.id == id }
}
