package dev.gyeol.worker.map

/**
 * 가수 설명 문체를 "~이다"체에서 "~해요"체로 바꾼다 (11-14 일회성 변환, 앞으로의 설명은 프롬프트로 해요체).
 * 문장 끝(마지막 "다.")만 바꾼다. 문장 가운데("~하며", "~로")는 그대로 둔다.
 * 규칙으로 확신할 수 없는 활용은 바꾸지 않고 [Result.unchanged]로 돌려준다 (손으로 고칠 후보).
 */
object HaeyoStyle {
    data class Result(val text: String, val converted: Int, val unchanged: List<String>, val sentences: Int)

    private const val BASE = 0xAC00
    private val SENTENCE = Regex("""(?<=[.!?])\s+""")
    // 중성 번호: ㅏ0 ㅐ1 ㅓ4 ㅔ5 ㅕ6 ㅗ8 ㅘ9 ㅚ11 ㅜ13 ㅝ14 ㅡ18 ㅣ20
    private const val A = 0; private const val AE = 1; private const val EO = 4; private const val E = 5; private const val YEO = 6
    private const val O = 8; private const val WA = 9; private const val WAE = 10; private const val OE = 11; private const val U = 13; private const val WO = 14; private const val EU = 18; private const val I = 20
    // 종성 번호: ㄴ4 ㄹ8 ㅂ17 ㅆ20
    private const val N = 4; private const val L = 8; private const val B = 17; private const val SS = 20

    /** ㄹ 받침 동사 어간 (현재형에서 ㄹ이 빠져 "만든다"가 된다). 이 목록에 있을 때만 ㄹ 탈락으로 되돌린다 */
    private val L_STEMS = listOf("만들", "넘나들", "이끌", "들", "열", "살", "끌", "떨", "울", "풀", "빌", "불", "놀", "얼")
    /** 받침 없는 형용사 불규칙 (명사+다 규칙에 걸리지 않게) */
    private val IRREGULAR = mapOf("아니" to "아니에요")

    fun convert(text: String): Result {
        val parts = text.trim().split(SENTENCE)
        var converted = 0
        val unchanged = mutableListOf<String>()
        val out = parts.map { s ->
            if (!s.endsWith("다.")) return@map s
            val r = sentence(s)
            if (r == null) { unchanged += s; s } else { converted++; r }
        }
        return Result(out.joinToString(" "), converted, unchanged, parts.size)
    }

    /** "…다."로 끝나는 문장 하나. 확신 못 하면 null */
    fun sentence(s: String): String? {
        val body = s.removeSuffix("다.")
        if (body.isEmpty()) return null
        val lastWordStart = body.lastIndexOf(' ') + 1
        val head = body.substring(0, lastWordStart)
        val word = body.substring(lastWordStart)
        return ending(word)?.let { "$head$it." }
    }

    /** 끝 낱말(“다” 뗀 것) → 해요체. 예: "특징이" → "특징이에요", "보인" → "보여요", "구축했" → "구축했어요" */
    fun ending(w: String): String? {
        if (w.isEmpty()) return null
        IRREGULAR.entries.firstOrNull { w.endsWith(it.key) }?.let { (k, v) -> return w.dropLast(k.length) + v }
        if (w.endsWith("니")) return null                            // "~습니다/~ㅂ니다"는 이미 존댓말: 손으로
        if (w.endsWith("있") || w.endsWith("없")) return w + "어요"
        val (i, m, f) = dec(w.last()) ?: return null
        return when {
            f == SS -> w + "어요"                                     // 했다·왔다·보였다 → 했어요·왔어요·보였어요
            w.endsWith("이") && w.length >= 2 && dec(w[w.length - 2])?.third == 0 -> w.dropLast(1) + "예요" // 프로듀서이다 → 프로듀서예요
            w.endsWith("이") -> w + "에요"                            // 특징이다 → 특징이에요
            w.last() == '는' -> ayo(w.dropLast(1))                  // 받는다 → 받아요
            f == N -> {                                              // 보인다·한다·만든다
                val stem = w.dropLast(1) + comp(i, m, 0)
                val withL = w.dropLast(1) + comp(i, m, L)
                // 한 글자 어간(들·열·울…)은 낱말 전체가 같을 때만 (채운다 → 채울×, 채워요)
                if (L_STEMS.any { if (it.length == 1) withL == it else withL.endsWith(it) }) withL + (if (m == A || m == O) "아요" else "어요") else ayo(stem)
            }
            f == B -> w.dropLast(1) + comp(i, m, 0) + "워요"          // 다채롭다 → 다채로워요
            f == 0 && w.endsWith("하") -> w.dropLast(1) + "해요"       // 다양하다 → 다양해요
            f == 0 && w.endsWith("르") && w.length >= 2 -> ayo(w)       // 다르다·빠르다 → 달라요·빨라요
            f == 0 -> w + "예요"                                      // 가수다 → 가수예요
            else -> w + (if (m == A || m == O) "아요" else "어요")     // 깊다·많다 → 깊어요·많아요
        }
    }

    /** 동사 어간 + 아/어요 (규칙 활용). 확신 못 하면 null */
    private fun ayo(stem: String): String? {
        if (stem.isEmpty()) return null
        if (stem.endsWith("하")) return stem.dropLast(1) + "해요"
        val (i, m, f) = dec(stem.last()) ?: return null
        if (f != 0) return stem + (if (m == A || m == O) "아요" else "어요")
        val front = stem.dropLast(1)
        // 르 불규칙: 아우르 → 아울러요, 부르 → 불러요, 다르 → 달라요 (앞 글자에 ㄹ 받침 + 라/러)
        if (stem.last() == '르' && front.isNotEmpty()) {
            val (pi, pm, pf) = dec(front.last()) ?: return null
            if (pf != 0) return null
            return front.dropLast(1) + comp(pi, pm, L) + (if (pm == A || pm == O) "라요" else "러요")
        }
        return when (m) {
            I -> front + comp(i, YEO, 0) + "요"        // 보이 → 보여요
            U -> front + comp(i, WO, 0) + "요"         // 주 → 줘요
            O -> front + comp(i, WA, 0) + "요"         // 보 → 봐요
            OE -> front + comp(i, WAE, 0) + "요"       // 되 → 돼요 (평가된다 → 평가돼요)
            EU -> front + comp(i, EO, 0) + "요"        // 쓰 → 써요
            A, EO, AE, E, YEO -> "${stem}요"            // 가요·서요·내요·세요·켜요
            else -> null
        }
    }

    private fun dec(c: Char): Triple<Int, Int, Int>? {
        val o = c.code - BASE
        if (o < 0 || o >= 11172) return null
        return Triple(o / 588, (o % 588) / 28, o % 28)
    }

    private fun comp(i: Int, m: Int, f: Int): Char = (BASE + i * 588 + m * 28 + f).toChar()
}
