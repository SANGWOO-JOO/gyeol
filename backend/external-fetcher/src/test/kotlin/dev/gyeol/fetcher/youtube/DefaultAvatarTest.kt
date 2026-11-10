package dev.gyeol.fetcher.youtube

import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultAvatarTest {
    private fun jpg(img: BufferedImage) = ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()

    private fun image(bg: Color, fg: Color, text: String, font: Int = 120): ByteArray {
        val img = BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = bg; g.fillRect(0, 0, 240, 240)
        g.color = fg; g.font = Font(Font.SANS_SERIF, Font.PLAIN, font); g.drawString(text, 80, 160)
        g.dispose()
        return jpg(img)
    }

    @Test
    fun `색 바탕에 흰 글자 하나는 기본 프로필`() {
        assertTrue(DefaultAvatar.isDefault(image(Color(93, 64, 55), Color.WHITE, "B")))
        assertTrue(DefaultAvatar.isDefault(image(Color(0, 77, 64), Color.WHITE, "R")))
    }

    @Test
    fun `흰 바탕 글자 로고나 검은 바탕 로고는 기본 프로필이 아니다`() {
        assertFalse(DefaultAvatar.isDefault(image(Color.WHITE, Color.BLACK, "M.C the MAX", font = 20)))
        assertFalse(DefaultAvatar.isDefault(image(Color.BLACK, Color.WHITE, "LOGO", font = 60)))
    }

    @Test
    fun `여러 색이 섞인 사진은 기본 프로필이 아니다`() {
        val img = BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 240) for (x in 0 until 240) img.setRGB(x, y, Color(x, y, (x + y) / 2).rgb)
        assertFalse(DefaultAvatar.isDefault(jpg(img)))
    }
}
