package dev.gyeol.api.admin

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdminSecurityTest {
    private val rfcSecret = "12345678901234567890".toByteArray()

    @Test
    fun `TOTP는 RFC 6238 부록 B 테스트 벡터와 같다`() {
        assertEquals("94287082", Totp.code(rfcSecret, 59 / 30, 8))
        assertEquals("07081804", Totp.code(rfcSecret, 1111111109L / 30, 8))
        assertEquals("14050471", Totp.code(rfcSecret, 1111111111L / 30, 8))
        assertEquals("89005924", Totp.code(rfcSecret, 1234567890L / 30, 8))
        assertEquals("69279037", Totp.code(rfcSecret, 2000000000L / 30, 8))
    }

    @Test
    fun `앞뒤 한 칸까지 받고, 그 밖과 형식이 틀린 코드는 거절`() {
        val at = Instant.ofEpochSecond(1_700_000_000)
        val now = Totp.step(at)
        assertEquals(now - 1, Totp.verify(rfcSecret, Totp.code(rfcSecret, now - 1), at))
        assertEquals(now + 1, Totp.verify(rfcSecret, Totp.code(rfcSecret, now + 1), at))
        assertNull(Totp.verify(rfcSecret, Totp.code(rfcSecret, now - 2), at))
        assertNull(Totp.verify(rfcSecret, "12345", at))
        assertNull(Totp.verify(rfcSecret, "abcdef", at))
    }

    @Test
    fun `Base32와 tools-admin-account가 만든 암호문을 같은 키로 푼다`() {
        assertEquals("12345678901234567890", String(Totp.base32Decode("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")))
        // 테스트 전용 가짜 키·가짜 비밀값 (make_admin.encrypt로 만든 값)
        val key = "dGVzdC1vbmx5LWZha2Uta2V5LTAxMjM0NTY3ODlhYiE="
        val fromPython = "2ypF1aJeIG2KWHkOewDfkL5ORglxmiqYeg6NmPrsbzkKzHmn1cWjt6dfz92eZBc82pR5n7fRLQhd7N5K"
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", TotpCipher(key).decrypt(fromPython))
        val c = TotpCipher(key)
        assertEquals("ABC", c.decrypt(c.encrypt("ABC")))
    }

    @Test
    fun `방장 이메일 가리기`() {
        assertEquals("a***@g***.com", AdminService.maskEmail("abcd@gmail.com"))
        assertEquals("h***@e***.co.kr", AdminService.maskEmail("host@example.co.kr"))
    }
}
