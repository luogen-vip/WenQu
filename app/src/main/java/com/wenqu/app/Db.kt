package com.wenqu.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** SQLite 持久层：对话、消息（含来源/图片/视频等元数据），重装前后台清理后数据都在 */
object Db {
    private lateinit var helper: SQLiteOpenHelper

    fun init(ctx: Context) {
        helper = object : SQLiteOpenHelper(ctx, "wenqu.db", null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE convs(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, created_at INTEGER, updated_at INTEGER, last_query TEXT DEFAULT '', game_state TEXT DEFAULT '')"
                )
                db.execSQL(
                    "CREATE TABLE msgs(id INTEGER PRIMARY KEY AUTOINCREMENT, conv_id INTEGER, role INTEGER, text TEXT, meta TEXT, time INTEGER)"
                )
            }
            override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
        }
    }

    fun conversations(): List<Conv> {
        val out = ArrayList<Conv>()
        helper.readableDatabase.rawQuery(
            "SELECT id,title,created_at,updated_at,last_query,game_state FROM convs ORDER BY updated_at DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Conv(
                        c.getLong(0), c.getString(1) ?: "", c.getLong(2), c.getLong(3),
                        c.getString(4) ?: "", c.getString(5) ?: ""
                    )
                )
            }
        }
        return out
    }

    fun conv(id: Long): Conv? = conversations().firstOrNull { it.id == id }

    fun createConv(title: String): Long {
        val cv = ContentValues()
        cv.put("title", title)
        cv.put("created_at", System.currentTimeMillis())
        cv.put("updated_at", System.currentTimeMillis())
        return helper.writableDatabase.insert("convs", null, cv)
    }

    fun deleteConv(id: Long) {
        val db = helper.writableDatabase
        db.delete("msgs", "conv_id=?", arrayOf(id.toString()))
        db.delete("convs", "id=?", arrayOf(id.toString()))
    }

    fun touchConv(id: Long, lastQuery: String? = null, gameState: String? = null, title: String? = null) {
        val cv = ContentValues()
        cv.put("updated_at", System.currentTimeMillis())
        lastQuery?.let { cv.put("last_query", it) }
        gameState?.let { cv.put("game_state", it) }
        title?.let { cv.put("title", it) }
        helper.writableDatabase.update("convs", cv, "id=?", arrayOf(id.toString()))
    }

    fun insertMessage(convId: Long, role: Int, text: String, meta: String): Long {
        val cv = ContentValues()
        cv.put("conv_id", convId)
        cv.put("role", role)
        cv.put("text", text)
        cv.put("meta", meta)
        cv.put("time", System.currentTimeMillis())
        return helper.writableDatabase.insert("msgs", null, cv)
    }

    fun deleteMessage(id: Long) {
        helper.writableDatabase.delete("msgs", "id=?", arrayOf(id.toString()))
    }

    fun clearAll() {
        val db = helper.writableDatabase
        db.delete("msgs", null, null)
        db.delete("convs", null, null)
    }

    fun messages(convId: Long): List<UiMessage> {
        val out = ArrayList<UiMessage>()
        helper.readableDatabase.rawQuery(
            "SELECT id,role,text,meta,time FROM msgs WHERE conv_id=? ORDER BY id ASC", arrayOf(convId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val meta = c.getString(3) ?: "{}"
                out.add(metaToMessage(c.getLong(0), c.getInt(1), c.getString(2) ?: "", meta, c.getLong(4)))
            }
        }
        return out
    }

    fun lastUserTextBefore(convId: Long, beforeId: Long): String {
        helper.readableDatabase.rawQuery(
            "SELECT text FROM msgs WHERE conv_id=? AND role=0 AND id<? ORDER BY id DESC LIMIT 1",
            arrayOf(convId.toString(), beforeId.toString())
        ).use { c -> if (c.moveToFirst()) return c.getString(0) ?: "" }
        return ""
    }

    fun prevUserText(convId: Long): String {
        helper.readableDatabase.rawQuery(
            "SELECT text FROM msgs WHERE conv_id=? AND role=0 ORDER BY id DESC LIMIT 1", arrayOf(convId.toString())
        ).use { c -> if (c.moveToFirst()) return c.getString(0) ?: "" }
        return ""
    }

    fun updateMessage(id: Long, text: String, meta: String) {
        val cv = ContentValues()
        cv.put("text", text)
        cv.put("meta", meta)
        helper.writableDatabase.update("msgs", cv, "id=?", arrayOf(id.toString()))
    }

    // ---- meta JSON 序列化 ----
    fun metaOf(m: UiMessage): String {
        val o = JSONObject()
        try {
            val es = JSONArray()
            m.engineStates.forEach { p ->
                val eo = JSONObject().put("i", p.id).put("n", p.name).put("s", p.state).put("c", p.count)
                val rs = JSONArray()
                p.results.take(8).forEach { r ->
                    rs.put(JSONObject().put("t", Nlp.shorten(r.title, 50)).put("u", r.url))
                }
                eo.put("r", rs)
                es.put(eo)
            }
            o.put("engines", es)
            val ss = JSONArray()
            m.sources.forEach { s -> ss.put(JSONObject().put("t", s.title).put("u", s.url).put("e", s.engine).put("h", s.host)) }
            o.put("sources", ss)
            val im = JSONArray()
            m.images.forEach { x -> im.put(JSONObject().put("t", x.title).put("i", x.image).put("p", x.page).put("s", x.source).put("x", x.extra)) }
            o.put("images", im)
            val vs = JSONArray()
            m.videos.forEach { x -> vs.put(JSONObject().put("t", x.title).put("i", x.image).put("p", x.page).put("s", x.source).put("x", x.extra)) }
            o.put("videos", vs)
            val sg = JSONArray()
            m.suggestions.forEach { sg.put(it) }
            o.put("suggestions", sg)
            if (m.query.isNotEmpty()) o.put("query", m.query)
        } catch (_: Exception) {}
        return o.toString()
    }

    private fun metaToMessage(id: Long, role: Int, text: String, meta: String, time: Long): UiMessage {
        var es: List<EngineProgress> = emptyList()
        var ss: List<SourceRef> = emptyList()
        var im: List<MediaResult> = emptyList()
        var vs: List<MediaResult> = emptyList()
        var sg: List<String> = emptyList()
        var qy = ""
        try {
            val o = JSONObject(meta)
            qy = o.optString("query")
            o.optJSONArray("engines")?.let { a ->
                es = (0 until a.length()).map {
                    val x = a.getJSONObject(it)
                    val rs = x.optJSONArray("r")?.let { ra ->
                        (0 until ra.length()).map { k -> val w = ra.getJSONObject(k); WebResult(w.optString("t"), "", w.optString("u"), x.optString("n")) }
                    } ?: emptyList()
                    EngineProgress(x.optString("i"), x.optString("n"), x.optInt("s"), x.optInt("c"), rs)
                }
            }
            o.optJSONArray("sources")?.let { a ->
                ss = (0 until a.length()).map { val x = a.getJSONObject(it); SourceRef(x.optString("t"), x.optString("u"), x.optString("e"), x.optString("h")) }
            }
            o.optJSONArray("images")?.let { a ->
                im = (0 until a.length()).map { val x = a.getJSONObject(it); MediaResult(x.optString("t"), x.optString("i"), x.optString("p"), x.optString("s"), x.optString("x")) }
            }
            o.optJSONArray("videos")?.let { a ->
                vs = (0 until a.length()).map { val x = a.getJSONObject(it); MediaResult(x.optString("t"), x.optString("i"), x.optString("p"), x.optString("s"), x.optString("x")) }
            }
            o.optJSONArray("suggestions")?.let { a ->
                sg = (0 until a.length()).map { a.optString(it) }
            }
        } catch (_: Exception) {}
        return UiMessage(id, role, text, false, false, es, ss, im, vs, sg, qy, time)
    }
}
