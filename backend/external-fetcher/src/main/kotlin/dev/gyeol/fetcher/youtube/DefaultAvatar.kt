package dev.gyeol.fetcher.youtube

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * YouTube 기본 프로필(채널 이름 첫 글자 그림)인지 이미지 내용으로 판별한다.
 * 기본 프로필은 흰색·검은색이 아닌 단색 바탕이 대부분이고 그다음 많은 색이 흰 글자다.
 * 주소(/ytc/)로는 구분되지 않는다: ZARD 공식 로고도 /ytc/ 주소였다 (2026-11-10, 실제 프로필 23장으로 기준 확인:
 * 기본 2장은 바탕 92~93%, 흰 바탕 글자 로고·손그림은 94~97%여도 바탕이 흰색이라 통과)
 */
object DefaultAvatar {
    private const val MIN_SHARE = 0.8

    fun isDefault(bytes: ByteArray): Boolean {
        val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: return false
        val counts = HashMap<Int, Int>()
        for (y in 0 until img.height) for (x in 0 until img.width) counts.merge(bucket(img.getRGB(x, y)), 1, Int::plus)
        val top = counts.entries.sortedByDescending { it.value }.take(2)
        if (top.size < 2) return false
        val share = top[0].value.toDouble() / (img.width * img.height)
        val (r, g, b) = rgb(top[0].key)
        val light = (r + g + b) / 3
        val (r2, g2, b2) = rgb(top[1].key)
        return share >= MIN_SHARE && light in 30..215 && minOf(r2, g2, b2) >= 200
    }

    // 색을 채널당 5비트로 묶는다 (JPEG 압축 차이를 무시)
    private fun bucket(p: Int) = ((p shr 16 and 255) shr 3 shl 10) or ((p shr 8 and 255) shr 3 shl 5) or ((p and 255) shr 3)

    private fun rgb(k: Int) = Triple((k shr 10 and 31) shl 3, (k shr 5 and 31) shl 3, (k and 31) shl 3)
}
