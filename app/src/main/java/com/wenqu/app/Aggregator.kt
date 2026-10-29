package com.wenqu.app

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** 多引擎并行聚合 + 排序去重 + 健康度冷却 */
class Aggregator(private val onUpdate: (EngineProgress) -> Unit) {

    suspend fun searchWeb(
        query: String,
        engines: List<Engine>,
        perEngine: Int,
        deep: Boolean,
        maxParallel: Int = 6
    ): Pair<List<WebResult>, List<EngineProgress>> {
        val states = ConcurrentHashMap<String, EngineProgress>()
        val collected = Collections.synchronizedList(ArrayList<WebResult>())

        coroutineScope {
            val sem = Semaphore(maxParallel)
            engines.map { e ->
                async {
                    sem.withPermit {
                        val cd = Prefs.cooldownUntil(e.id)
                        if (cd > System.currentTimeMillis()) {
                            val p = EngineProgress(e.id, e.name, 3, 0)
                            states[e.id] = p; onUpdate(p)
                            return@withPermit
                        }
                        val p0 = EngineProgress(e.id, e.name, 0, 0)
                        states[e.id] = p0; onUpdate(p0)
                        val rs = try { e.search(query, perEngine, deep) } catch (ex: Exception) { emptyList<WebResult>() }
                        val p1 = if (rs.isEmpty()) {
                            Prefs.recordFail(e.id)
                            EngineProgress(e.id, e.name, 2, 0)
                        } else {
                            Prefs.recordOk(e.id)
                            collected.addAll(rs)
                            EngineProgress(e.id, e.name, 1, rs.size)
                        }
                        states[e.id] = p1; onUpdate(p1)
                    }
                }
            }.awaitAll()
        }
        return Aggregator.rank(query, collected.toList()) to states.values.toList()
    }

    companion object {
        /** 去重（URL）+ 相关度排序（标题/摘要命中率 + 多引擎命中同域加分 + 引擎可靠性） */
        fun rank(q: String, rs: List<WebResult>): List<WebResult> {
            val toks = Nlp.tokens(q)
            val seen = HashSet<String>()
            val dedup = ArrayList<WebResult>()
            val hostCount = HashMap<String, Int>()
            for (r in rs) {
                val key = r.url.substringBefore('#').trimEnd('/').lowercase()
                if (!seen.add(key)) continue
                dedup.add(r)
                val h = Nlp.hostOf(r.url)
                hostCount[h] = (hostCount[h] ?: 0) + 1
            }
            for (r in dedup) {
                var s = Nlp.coverage(toks, r.title) * 3.0 + Nlp.coverage(toks, r.snippet) * 1.3
                val h = Nlp.hostOf(r.url)
                if ((hostCount[h] ?: 0) >= 2) s += 1.2
                s += 0.15
                r.score = s
            }
            return dedup.sortedByDescending { it.score }
        }
    }
}
