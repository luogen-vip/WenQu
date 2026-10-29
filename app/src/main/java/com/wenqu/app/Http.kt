package com.wenqu.app

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 统一 HTTP：UA 轮换、浏览器指纹头、Cookie、超时控制 —— 降低被识别为爬虫/限流的概率 */
object Http {
    private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(14, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                if (cookies.isNotEmpty()) cookieStore[url.host] = cookies
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = cookieStore[url.host] ?: emptyList()
        })
        .build()

    private val DESKTOP_UAS = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    )
    private val MOBILE_UAS = listOf(
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (Linux; Android 13; M2102K1C) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
    )

    fun randomUA(mobile: Boolean = false): String =
        (if (mobile) MOBILE_UAS else DESKTOP_UAS + MOBILE_UAS).random()

    fun mobileUA(): String = MOBILE_UAS.random()

    fun randomHex(len: Int): String {
        val chars = "abcdef0123456789"
        return buildString { repeat(len) { append(chars.random()) } }
    }

    fun get(
        url: String,
        referer: String? = null,
        ua: String = randomUA(),
        extraHeaders: Map<String, String> = emptyMap(),
        callTimeoutSec: Long = 18
    ): String? {
        return try {
            val b = Request.Builder().url(url)
                .header("User-Agent", ua)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Cache-Control", "no-cache")
            referer?.let { b.header("Referer", it) }
            extraHeaders.forEach { (k, v) -> b.header(k, v) }
            val c = if (callTimeoutSec != 18L) client.newBuilder().callTimeout(callTimeoutSec, TimeUnit.SECONDS).build() else client
            c.newCall(b.build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun postForm(
        url: String,
        form: Map<String, String>,
        referer: String? = null,
        ua: String = randomUA(),
        callTimeoutSec: Long = 18
    ): String? {
        return try {
            val fb = FormBody.Builder()
            form.forEach { (k, v) -> fb.add(k, v) }
            val b = Request.Builder().url(url).post(fb.build())
                .header("User-Agent", ua)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
            referer?.let { b.header("Referer", it) }
            val c = if (callTimeoutSec != 18L) client.newBuilder().callTimeout(callTimeoutSec, TimeUnit.SECONDS).build() else client
            c.newCall(b.build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }
}
