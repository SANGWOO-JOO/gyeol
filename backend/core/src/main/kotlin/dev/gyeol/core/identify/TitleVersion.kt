package dev.gyeol.core.identify

/** 제목의 판 표시 (Inst.·Live·Remix·ver. 등): 같은 노래의 다른 녹음이다 (I-30, api 같은 곡 합치기와 worker 최신순이 같이 쓴다) */
object TitleVersion {
    val REGEX = Regex("""(?i)\b(inst|instrumental|mr|live|remix|mix|ver|version|edit|remaster(ed)?|acoustic|demo|feat|ft|prod|sped|slowed|cover|karaoke)\b|라이브|리믹스|버전|반주|어쿠스틱""")

    fun has(title: String): Boolean = REGEX.containsMatchIn(title)
}
