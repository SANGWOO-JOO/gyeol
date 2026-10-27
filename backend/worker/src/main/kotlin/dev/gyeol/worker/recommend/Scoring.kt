package dev.gyeol.worker.recommend

import dev.gyeol.core.recommend.ScoredInput
import dev.gyeol.core.recommend.Seed
import kotlin.math.ln1p
import kotlin.math.sqrt

/** 점수를 매긴 후보 */
data class Ranked(val input: ScoredInput, val score: Double, val vectorSim: Double)

/**
 * 추천 점수 (docs/l3-design.md 11장). 템포·에너지는 쓰지 않는다 (L3-2: 근거 거의 없음)
 *   점수 = w벡터 × 프로필 유사도 + wLast.fm × Last.fm 유사도 + w크레딧 × min(1, 같은 작곡가·프로듀서 수 / 2)
 * 벡터가 없는 후보는 벡터 항이 0이다.
 */
object Scoring {
    /** 들은 곡 벡터의 가중 평균을 다시 길이 1로 */
    fun profile(seeds: List<Seed>): FloatArray? {
        val withVec = seeds.filter { it.vector != null }
        if (withVec.isEmpty()) return null
        val acc = DoubleArray(withVec.first().vector!!.size)
        withVec.forEach { s -> s.vector!!.forEachIndexed { i, x -> acc[i] += x * s.weight } }
        val norm = sqrt(acc.sumOf { it * it }).takeIf { it > 0 } ?: return null
        return FloatArray(acc.size) { (acc[it] / norm).toFloat() }
    }

    fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / sqrt(na * nb)
    }

    fun rank(inputs: List<ScoredInput>, profile: FloatArray?, w: Triple<Double, Double, Double>, perArtist: Int, limit: Int): List<Ranked> {
        val ranked = inputs.map { c ->
            val sim = if (profile != null && c.vector != null) cosine(profile, c.vector!!) else 0.0
            val credit = minOf(1.0, c.creditOverlap / 2.0)
            Ranked(c, w.first * sim + w.second * c.lastfmMatch.coerceIn(0.0, 1.0) + w.third * credit, sim)
        }.sortedByDescending { it.score }
        // 같은 가수는 [perArtist]곡까지 (다양성)
        val perArtistCount = HashMap<String, Int>()
        return ranked.filter { r -> (perArtistCount.merge(r.input.artistMbid, 1, Int::plus) ?: 0) <= perArtist }.take(limit)
    }

    /** 재생 수 가중치 (청취 기록). 고른 곡은 1 */
    fun weight(plays: Int) = ln1p(plays.toDouble())
}
