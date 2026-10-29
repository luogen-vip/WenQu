package com.wenqu.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * 全网图片/视频搜索。
 * 图片三路：必应图片 + 百度图片 + DuckDuckGo 图片；视频两路：B站开放接口 + 必应视频页。
 * 每路可独立调用（供 UI 显示实时进度），search() 负责交错合并。
 */
object ImageSearcher {

    suspend fun search(q: String, offset: Int, count: Int = 15): List<MediaResult> {
        val lists = listOf(
            fromBing(q, offset, 20),
            fromBaidu(q, offset, 20),
            fromDdg(q, offset, 20)
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
            val url = "https://www.bing.com/images/search?q=${Nlp.enc(q)}&first=${offset + 1}&count=35"
            val html = Http.get(url, "https://www.bing.com/") ?: return@withContext out
            val doc = Jsoup.parse(html, "https://www.bing.com/")
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
            val html = Http.get(url, "https://image.baidu.com/", Http.mobileUA()) ?: return@withContext out
            val start = html.indexOf('{')
            val json = if (start > 0) html.substring(start) else html
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

    /** DuckDuckGo 图片：先取 vqd 令牌，再调 i.js */
    suspend fun fromDdg(q: String, offset: Int, count: Int): List<MediaResult> = withContext(Dispatchers.IO) {
        val out = ArrayList<MediaResult>()
        try {
            val home = Http.get("https://duckduckgo.com/?q=${Nlp.enc(q)}&iax=images&ia=images", "https://duckduckgo.com/")
                ?: return@withContext out
            val vqd = Regex("vqd=\"?([\\d-]+)\"?").find(home)?.groupValues?.get(1) ?: return@withContext out
            val api = "https://duckduckgo.com/i.js?l=zh-cn&o=json&q=${Nlp.enc(q)}&vqd=$vqd&f=,,,&p=1&s=$offset"
            val js = Http.get(api, "https://duckduckgo.com/") ?: return@withContext out
            val arr = JSONObject(js).optJSONArray("results") ?: return@withContext out
            for (i in 0 until arr.length()) {
                if (out.size >= count) break
                val o = arr.getJSONObject(i)
                val big = o.optString("image")
                val thumb = o.optString("thumbnail").ifEmpty { big }
                val title = o.optString("title").ifEmpty { q }
                val page = o.optString("url")
                if (big.startsWith("http")) out.add(MediaResult(title, thumb, page, "DuckDuckGo图片"))
            }
        } catch (_: Exception) {}
        out
    }
}

object VideoSearcher {

    suspend fun search(q: String, offset: Int, count: Int = 10): List<MediaResult> {
        val bili = fromBilibili(q, offset / 10 + 1, count)
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
            // 先访问主页拿 buvid cookie，再调搜索接口
            Http.get("https://www.bilibili.com/", "https://www.bilibili.com/")
            val api = "https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword=${Nlp.enc(q)}&page=$page"
            val js = Http.get(api, "https://www.bilibili.com/") ?: return@withContext out
            val data = JSONObject(js).optJSONObject("data") ?: return@withContext out
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
                out.add(MediaResult(title, pic, "https://www.bilibili.com/video/$bvid", "哔哩哔哩", "$author · $duration"))
            }
        } catch (_: Exception) {}
        out
    }

    suspend fun fromBing(q: String, count: Int): List<MediaResult> = withContext(Dispatchers.IO) {
        val out = ArrayList<MediaResult>()
        try {
            val url = "https://www.bing.com/videos/search?q=${Nlp.enc(q)}"
            val html = Http.get(url, "https://www.bing.com/") ?: return@withContext out
            val doc = Jsoup.parse(html, "https://www.bing.com/")
            for (a in doc.select("a.mc_vtvc_link")) {
                if (out.size >= count) break
                val u = a.absUrl("href")
                if (!u.startsWith("http")) continue
                val title = (a.attr("aria-label").ifEmpty { a.selectFirst(".mc_vtvc_title")?.text() ?: u }).trim()
                val meta = a.selectFirst(".mc_vtvc_meta_block, .mc_vtvc_meta")?.text()?.take(60) ?: ""
                out.add(MediaResult(title.take(60), "", u, "必应视频", meta))
            }
        } catch (_: Exception) {}
        out
    }
}
