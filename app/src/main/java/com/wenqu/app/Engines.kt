package com.wenqu.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 搜索引擎抓取层（只保留国内直连可用的引擎，被墙/需登录的已移除）。
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

    /** 单引擎整体限时 9 秒：超时立即判失败，绝不拖慢整体进度 */
    open suspend fun search(q: String, n: Int, deep: Boolean): List<WebResult> = withContext(Dispatchers.IO) {
        withTimeout(9000L) {
            var out = emptyList<WebResult>()
            repeat(2) { attempt ->
                if (out.isNotEmpty()) return@repeat
                val target = url(q, 1)
                val base = target.substringBefore('?')
                val html = if (isPost())
                    Http.postForm(target, mapOf("q" to q), referer(), if (attempt == 0) Http.randomUA() else Http.mobileUA())
                else
                    Http.get(target, referer(), if (attempt == 0) Http.randomUA() else Http.mobileUA(), extraHeaders(), callTimeoutSec = 8)
                html ?: return@repeat
                out = try { parse(Jsoup.parse(html, base), n) } catch (e: Exception) { emptyList() }
            }
            out
        }
    }

    companion object {
        /** 通用兜底提取器：从任意 HTML 页面里抓"像结果"的链接（用于结构多变的站点） */
        fun genericExtract(doc: Document, engine: String, n: Int): List<WebResult> {
            val deny = listOf(
                "google.com", "gstatic.com", "bing.com/acs", "baidu.com/s", "baidu.com/link",
                "sogou.com/web", "so.com/s?", "so.com/link", "sm.cn/s?", "toutiao.com/search",
                "miitbeian", "beian.gov", "example.com", "w3.org", "go.microsoft",
                "/login", "/signin", "javascript:", "tel:", "mailto:"
            )
            val junk = Regex("^(首页|下一页|上一页|登录|注册|下载|客户端|帮助|反馈|关于我们|设置|隐私|更多|贴吧|知道|文库|地图|图片|视频|新闻|网页|资讯|更多结果|换一换|正在加载|Sign in|Sign up|Log in|Next|Previous)$")
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

class BingEngine(private val base: String, eid: String, ename: String) :
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
            val u = Nlp.unwrapRedirect(a.absUrl("href"))
            if (title.isEmpty() || !u.startsWith("http")) continue
            val sn = el.selectFirst(".b_caption p, p")?.text()?.trim() ?: ""
            out.add(WebResult(title, sn, u, name))
        }
        if (out.isEmpty()) out.addAll(Engine.genericExtract(doc, name, n))
        return out
    }
}

/** 百度移动版：反爬比 PC 版宽松，直链多藏在 data-log 的 mu 字段里 */
class BaiduEngine : Engine("baidu", "百度") {
    override val reliability = 0.25
    override fun url(q: String, page: Int) =
        "https://m.baidu.com/s?word=${URLEncoder.encode(q, "UTF-8")}&sa=ib&from=1000539d&uid=0"
    override fun referer() = "https://m.baidu.com/"
    override fun extraHeaders() = mapOf(
        "Cookie" to "BAIDUID=${Http.randomHex(32)}:FG=1; BIDUPSID=${Http.randomHex(32)}; PSTM=${System.currentTimeMillis() / 1000}; MAWEBC=1; FACTS=1",
        "X-Requested-With" to "com.android.browser"
    )

    override suspend fun search(q: String, n: Int, deep: Boolean): List<WebResult> = withContext(Dispatchers.IO) {
        withTimeout(9000L) {
            var out = emptyList<WebResult>()
            repeat(2) { attempt ->
                if (out.isNotEmpty()) return@repeat
                val html = Http.get(
                    url(q, 1), referer(),
                    if (attempt == 0) Http.mobileUA() else Http.mobileUA(),
                    extraHeaders(), callTimeoutSec = 8
                ) ?: return@repeat
                out = try { parse(Jsoup.parse(html, "https://m.baidu.com/"), n) } catch (e: Exception) { emptyList() }
            }
            out
        }
    }

    override fun parse(doc: Document, n: Int): List<WebResult> {
        val out = ArrayList<WebResult>()
        for (el in doc.select("div.c-result, div[class*=result], div.c-container")) {
            if (out.size >= n) break
            val a = el.selectFirst("a[href]") ?: continue
            val title = a.text().trim()
            if (title.isEmpty()) continue
            var u = a.absUrl("href")
            if (!u.startsWith("http")) continue
            // 还原百度跳转链的真实地址（data-log 的 mu 字段）
            if (u.contains("baidu.com/link") || u.contains("m.baidu.com/from")) {
                val dl = el.attr("data-log").ifEmpty { el.selectFirst("[data-log]")?.attr("data-log") ?: "" }
                if (dl.isNotEmpty()) {
                    runCatching {
                        val mu = JSONObject(dl).optString("mu")
                        if (mu.startsWith("http")) u = mu
                    }
                }
            }
            val sn = el.text().replace(title, " ").replace(Regex("\\s+"), " ").trim().take(220)
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

/** 引擎注册表：全部为国内直连可用，谁临时抽风就快速标失败换下一路 */
object Engines {
    fun all(): List<Engine> = listOf(
        BingEngine("https://cn.bing.com", "bingcn", "必应中国"),
        BingEngine("https://www.bing.com", "bing", "必应"),
        BaiduEngine(),
        SogouEngine(),
        So360Engine(),
        ShenmaEngine(),
        ToutiaoEngine()
    )

    private val cache: List<Engine> by lazy { all() }
    fun cached(): List<Engine> = cache
    fun allIds(): List<String> = cache.map { it.id }
    fun byId(id: String): Engine? = cache.firstOrNull { it.id == id }
}
