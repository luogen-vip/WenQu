package com.wenqu.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * 全网图片/视频搜索（全部为国内直连可用源）。
 * 图片四路：必应图片 + 百度图片 + 搜狗图片 + 360图片；视频两路：B站开放接口 + 必应视频。
 * 每路可独立调用（供 UI 显示实时进度），search() 负责交错合并。
 */
object ImageSearcher {

    suspend fun search(q: String, offset: Int, count: Int = 15): List<MediaResult> {
        val lists = listOf(
            fromBing(q, offset, 20),
            fromBaidu(q, offset, 20),
            fromSogou(q, offset, 20),
            fromSo360(q, offset, 20)
        )
        val out = ArrayList<MediaResult>()
        val seen = HashSet<String>()
        var idx = 0
        while (out.size < count) {
            var added = false
            for (l in lists) {
                if (idx < l.size) {
                    val m = l[idx]
                    if (seen.add(m.image)) {
                        out.add(m); added = true
                        if (out.size >= count) break
                    }
                }
            }
            if (!added) break
            idx++
        }
        return out
    }

    /** 必应图片：SSR 页面里 a.iusc 的 m 属性是 JSON，murl=大图 */
    suspend fun fromBing(q: String, offset: Int, count: Int): List<MediaResult> = withContext(Dispatchers.IO) {
        val out = ArrayList<MediaResult>()
        try {
            val url = "https://cn.bing.com/images/search?q=${Nlp.enc(q)}&first=${offset + 1}&count=35"
            val html = Http.get(url, "https://cn.bing.com/") ?: return@withContext out
            val doc = Jsoup.parse(html, "https://cn.bing.com/")
            for (a in doc.select("a.iusc")) {
                if (out.size >= count) break
                val m = a.attr("m")
                if (m.isEmpty()) continue
                try {
                    val o = JSONObject(m)
                    val big = o.optString("murl")
                    val thumb = o.optString("turl").ifEmpty { big }
                    val title = o.optString("t").ifEmpty { q }
                    if (big.startsWith("http")) out.add(MediaResult(title, thumb, big, "必应图片"))
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        out
    }

    /** 百度图片 acjson 接口 */
    suspend fun fromBaidu(q: String, offset: Int, count: Int): List<MediaResult> = withContext(Dispatchers.IO) {
        val out = ArrayList<MediaResult>()
        try {
            val url = "https://image.baidu.com/search/acjson?tn=resultjson_com&ipn=rj&ct=201326592&fp=result" +
                    "&word=${Nlp.enc(q)}&pn=$offset&rn=30"
            val html = Http.get(url, "https://image.baidu.com/", Http.mobileUA(), callTimeoutSec = 9)
                ?: return@withContext out
            val start = html.indexOf('{')
            val json = if (start >= 0) html.substring(start) else html
            val o = JSONObject(json)
            val data = o.optJSONArray("data") ?: return@withContext out
            for (i in 0 until data.length()) {
                if (out.size >= count) break
                val item = data.optJSONObject(i) ?: continue
                val big = item.optString("middleURL").ifEmpty { item.optString("thumbURL") }
                val thumb = item.optString("thumbURL").ifEmpty { big }
                val title = item.optString("fromPageTitleEnc").ifEmpty { q }
                val page = item.optString("fromURL")
                if (big.startsWith("http")) out.add(MediaResult(title, thumb, page, "百度图片"))
            }
        } catch (_: Exception) {}
        out
    }

    /** 搜狗图片 napi JSON 接口 */
    suspend fun fromSogou(q: String, offset: Int, count: Int): List<MediaResult> = withContext(Dispatchers.IO) {
        val out = ArrayList<MediaResult>()
        try {
            val url = "https://pic.sogou.com/napi/pc/searchList?mode=1&start=$offset&xml_len=48&query=${Nlp.enc(q)}"
            val js = Http.get(url, "https://pic.sogou.com/", callTimeoutSec = 9) ?: return@withContext out
            val o = JSONObject(js)
            val data = o.optJSONObject("data") ?: return@withContext out
            val items = data.optJSONArray("items") ?: return@withContext out
            for (i in 0 until items.length()) {
                if (out.size >= count) break
                val item = items.optJSONObject(i) ?: continue
                val big = item.optString("picUrl").ifEmpty { item.optString("originUrl") }
                val thumb = item.optString("thumbUrl").ifEmpty { big }
                val title = item.optString("title").ifEmpty { q }
                val page = item.optJSONObject("link")?.optString("url")
                    ?: item.optString("pcUrl").ifEmpty { item.optString("url") }
                if (big.startsWith("http")) out.add(MediaResult(title, thumb, page, "搜狗图片"))
            }
        } catch (_: Exception) {}
        out
    }

    /** 360 图片 JSON 接口 */
    suspend fun fromSo360(q: String, offset: Int, count: Int): List<MediaResult> = withContext(Dispatchers.IO) {
        val out = ArrayList<MediaResult>()
        try {
            val url = "https://image.so.com/j?q=${Nlp.enc(q)}&src=srp&correct=&pn=$offset&sn=$offset&gn=0&kn=50"
            val js = Http.get(url, "https://image.so.com/", callTimeoutSec = 9) ?: return@withContext out
            val o = JSONObject(js)
            val arr = o.optJSONArray("list") ?: return@withContext out
            for (i in 0 until arr.length()) {
                if (out.size >= count) break
                val item = arr.optJSONObject(i) ?: continue
                val big = item.optString("img").ifEmpty { item.optString("img128") }
                val thumb = item.optString("thumb").ifEmpty { big }
                val title = item.optString("title").ifEmpty { q }
                val page = item.optString("link")
                if (big.startsWith("http")) out.add(MediaResult(title, thumb, page, "360图片"))
            }
        } catch (_: Exception) {}
        out
    }
}

object VideoSearcher {

    suspend fun search(q: String, page: Int, count: Int = 10): List<MediaResult> {
        val bili = fromBilibili(q, page, count)
        val bing = fromBing(q, count)
        val out = ArrayList<MediaResult>()
        val seen = HashSet<String>()
        var i = 0; var j = 0
        while (out.size < count && (i < bili.size || j < bing.size)) {
            if (i < bili.size) { val m = bili[i++]; if (seen.add(m.page)) out.add(m) }
            if (out.size >= count) break
            if (j < bing.size) { val m = bing[j++]; if (seen.add(m.page)) out.add(m) }
        }
        return out
    }

    suspend fun fromBilibili(q: String, page: Int, count: Int): List<MediaResult> = withContext(Dispatchers.IO) {
        val out = ArrayList<MediaResult>()
        try {
            // 先访问主页拿 buvid cookie，再调搜索接口（无 cookie 会被风控 -412）
            Http.get("https://www.bilibili.com/", "https://www.bilibili.com/", callTimeoutSec = 8)
            val api = "https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword=${Nlp.enc(q)}&page=$page"
            val js = Http.get(api, "https://www.bilibili.com/", Http.mobileUA(), callTimeoutSec = 9)
                ?: return@withContext out
            val root = JSONObject(js)
            if (root.optInt("code", -1) != 0) return@withContext out
            val data = root.optJSONObject("data") ?: return@withContext out
            val arr = data.optJSONArray("result") ?: return@withContext out
            for (i in 0 until arr.length()) {
                if (out.size >= count) break
                val o = arr.getJSONObject(i)
                val bvid = o.optString("bvid")
                if (bvid.isEmpty()) continue
                val title = Jsoup.parse(o.optString("title")).text()
                val author = o.optString("author")
                val duration = o.optString("duration")
                val pic = o.optString("pic").replace("http://", "https://")
                // m 站链接在内置 WebView 里兼容性最好，可直接网页播放
                out.add(MediaResult(title, pic, "https://m.bilibili.com/video/$bvid", "哔哩哔哩", "$author · $duration"))
            }
        } catch (_: Exception) {}
        out
    }

    suspend fun fromBing(q: String, count: Int): List<MediaResult> = withContext(Dispatchers.IO) {
        val out = ArrayList<MediaResult>()
        try {
            val url = "https://cn.bing.com/videos/search?q=${Nlp.enc(q)}"
            val html = Http.get(url, "https://cn.bing.com/", callTimeoutSec = 9) ?: return@withContext out
            val doc = Jsoup.parse(html, "https://cn.bing.com/")
            for (a in doc.select("a.mc_vtvc_link")) {
                if (out.size >= count) break
                val u = Nlp.unwrapRedirect(a.absUrl("href"))
                if (!u.startsWith("http")) continue
                val title = (a.attr("aria-label").ifEmpty { a.selectFirst(".mc_vtvc_title")?.text() ?: u }).trim()
                val meta = a.selectFirst(".mc_vtvc_meta_block, .mc_vtvc_meta")?.text()?.take(60) ?: ""
                // 抓封面缩略图（必应视频卡内的 img）
                val img = a.selectFirst("img")?.let { im ->
                    val src = im.attr("src").ifEmpty { im.attr("data-src") }
                    if (src.startsWith("http")) src else ""
                } ?: ""
                if (title.isNotEmpty()) out.add(MediaResult(title.take(60), img, u, "必应视频", meta))
            }
        } catch (_: Exception) {}
        out
    }
}
