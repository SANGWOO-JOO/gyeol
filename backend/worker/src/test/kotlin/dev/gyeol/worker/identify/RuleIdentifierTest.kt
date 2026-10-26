package dev.gyeol.worker.identify

import dev.gyeol.core.identify.AliasKey
import dev.gyeol.core.identify.Query
import dev.gyeol.core.identify.RuleIdentifier
import dev.gyeol.core.listen.Source
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** core의 정규화 키와 규칙 식별기 (core에는 테스트 설정이 없어 worker에서 확인) */
class RuleIdentifierTest {

    @Test
    fun `정규화 키 - 전각·대소문자·공백 차이는 같은 키, 문장부호는 그대로`() {
        assertEquals("밤편지|아이유 - topic", AliasKey.of(" 밤편지 ", "아이유  - Topic"))
        assertEquals(AliasKey.of("ＴＴ", "TWICE - Topic"), AliasKey.of("tt", "twice - topic"))
        assertNotEquals(AliasKey.of("Hello!", "Adele - Topic"), AliasKey.of("Hello", "Adele - Topic"))
        assertEquals("곡|", AliasKey.of("곡", null))
    }

    @Test
    fun `Topic 채널은 채널에서 가수를 얻는다`() {
        assertEquals(Query("아이유", "밤편지", byChannelName = false), RuleIdentifier.query("밤편지", "아이유 - Topic", Source.YOUTUBE))
        assertNull(RuleIdentifier.query("곡", " - Topic", Source.YOUTUBE))
    }

    @Test
    fun `뮤직 앱의 일반 채널은 공식 꼬리를 떼고 가수로 시도한다`() {
        assertEquals(Query("IU", "Love wins all", byChannelName = true), RuleIdentifier.query("Love wins all", "IU Official", Source.MUSIC_APP))
        assertEquals("NewJeans", RuleIdentifier.query("Ditto", "NewJeansVEVO", Source.MUSIC_APP)!!.artist)
    }

    @Test
    fun `일반 유튜브 영상은 규칙으로 풀지 않는다 - L2-3 LLM 몫`() {
        assertNull(RuleIdentifier.query("[MV] 아이유(IU) _ 밤편지", "1theK (원더케이)", Source.YOUTUBE))
        assertNull(RuleIdentifier.query("곡", null, Source.MUSIC_APP))
        assertNull(RuleIdentifier.query(" ", "가수 - Topic", Source.YOUTUBE))
    }
}
