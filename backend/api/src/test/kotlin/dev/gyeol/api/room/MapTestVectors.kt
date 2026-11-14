package dev.gyeol.api.room

/** I-40 시험용 384차원 벡터: [axis] 축에서 k가 클수록 조금씩 멀어짐 (k=0이면 축 그대로) */
fun vecOf(axis: Int, k: Int): FloatArray = FloatArray(384) { i ->
    when (i) {
        axis -> 1f
        100 + axis * 10 + k -> 0.15f * k
        else -> 0f
    }
}.let { v -> val n = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat(); FloatArray(v.size) { v[it] / n } }
