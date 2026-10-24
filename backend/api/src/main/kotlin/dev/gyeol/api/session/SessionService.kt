package dev.gyeol.api.session

import dev.gyeol.api.config.GyeolProperties
import dev.gyeol.api.web.ApiException
import dev.gyeol.core.session.Entry
import dev.gyeol.core.session.SeedTrack
import dev.gyeol.core.session.Session
import dev.gyeol.core.session.SessionRepository
import dev.gyeol.core.session.SessionStatus
import dev.gyeol.core.session.Tokens
import dev.gyeol.core.upload.UploadRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.time.Instant
import java.util.UUID

data class CreatedSession(val token: String, val session: Session)

data class UploadTicket(
    val uploadId: UUID,
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val expiresAt: Instant,
)

data class SeedInput(val mbid: String?, val title: String?, val artist: String?)

data class UploadCounts(val totalCount: Int?, val musicCount: Int?, val unclassifiedCount: Int?, val byteSize: Long?)

@Service
class SessionService(
    private val sessions: SessionRepository,
    private val uploads: UploadRepository,
    private val s3: S3Client,
    private val presigner: S3Presigner,
    private val props: GyeolProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun create(entry: Entry): CreatedSession {
        val token = Tokens.newToken()
        val id = sessions.create(Tokens.hash(token), entry, Instant.now().plus(props.sessionTtl))
        return CreatedSession(token, sessions.findById(id)!!)
    }

    /** 토큰으로 살아 있는 세션을 찾는다. 형식이 틀리거나 없거나 만료면 모두 404 */
    fun find(token: String): Session {
        if (!Tokens.looksValid(token)) throw ApiException.notFound()
        return sessions.findActiveByTokenHash(Tokens.hash(token)) ?: throw ApiException.notFound()
    }

    fun seedsOf(session: Session): List<SeedTrack>? =
        if (session.entry == Entry.SEEDS) sessions.findSeeds(session.id) else null

    @Transactional
    fun saveSeeds(token: String, input: List<SeedInput>?): Int {
        val session = find(token)
        if (session.entry != Entry.SEEDS) throw ApiException.invalidState("곡 고르기 세션이 아닙니다")
        val tracks = input.orEmpty()
        if (tracks.size !in 3..5) throw ApiException.validation("곡은 3~5개를 골라야 합니다")

        val seeds = tracks.mapIndexed { i, t ->
            val mbid = runCatching { UUID.fromString(t.mbid) }.getOrNull()
                ?: throw ApiException.validation("${i + 1}번째 곡의 mbid 형식이 올바르지 않습니다")
            val title = t.title?.trim().orEmpty()
            val artist = t.artist?.trim().orEmpty()
            if (title.isEmpty() || title.length > 300 || artist.isEmpty() || artist.length > 300) {
                throw ApiException.validation("${i + 1}번째 곡의 제목·가수는 1~300자여야 합니다")
            }
            SeedTrack(mbid, title, artist, i + 1)
        }
        if (seeds.map { it.mbid }.toSet().size != seeds.size) throw ApiException.validation("같은 곡을 두 번 고를 수 없습니다")

        // L1에는 추천이 없어 바로 DONE. L3부터는 PROCESSING을 거친다.
        if (!sessions.transition(session.id, SessionStatus.CREATED, SessionStatus.DONE)) {
            throw ApiException.invalidState("이미 곡을 저장한 세션입니다")
        }
        sessions.insertSeeds(session.id, seeds)
        return seeds.size
    }

    @Transactional
    fun issueUploadUrl(token: String, counts: UploadCounts): UploadTicket {
        val session = find(token)
        if (session.entry != Entry.TAKEOUT) throw ApiException.invalidState("청취 기록 업로드 세션이 아닙니다")

        val total = counts.totalCount ?: throw ApiException.validation("totalCount가 필요합니다")
        val music = counts.musicCount ?: throw ApiException.validation("musicCount가 필요합니다")
        val unclassified = counts.unclassifiedCount ?: throw ApiException.validation("unclassifiedCount가 필요합니다")
        val byteSize = counts.byteSize ?: throw ApiException.validation("byteSize가 필요합니다")
        if (total < 0 || music < 0 || unclassified < 0) throw ApiException.validation("건수는 0 이상이어야 합니다")
        if (music + unclassified > total) throw ApiException.validation("음악과 미판정 건수의 합이 전체보다 많습니다")
        if (byteSize <= 0) throw ApiException.validation("byteSize는 1 이상이어야 합니다")
        if (byteSize > props.upload.maxBytes) throw ApiException.tooLarge(props.upload.maxBytes)

        if (!sessions.markUploading(session.id, total, music, unclassified)) {
            throw ApiException.invalidState("이미 업로드를 시작한 세션입니다")
        }
        val uploadId = UUID.randomUUID()
        // 경로에 토큰이나 세션 번호를 넣지 않는다
        val key = "uploads/$uploadId.json"
        uploads.create(uploadId, session.id, key)

        val put = PutObjectRequest.builder()
            .bucket(props.upload.bucket)
            .key(key)
            .contentType("application/json")
            .contentLength(byteSize) // 크기를 서명에 넣어, 신고한 크기와 다른 파일은 S3가 거절하게 한다
            .build()
        val presigned = try {
            presigner.presignPutObject { it.signatureDuration(props.upload.presignTtl).putObjectRequest(put) }
        } catch (e: Exception) {
            log.error("서명 URL 생성 실패 (세션 {})", session.id, e)
            throw ApiException.storage("업로드 주소를 만들지 못했습니다")
        }
        val headers = presigned.signedHeaders()
            .filterKeys { !it.equals("host", ignoreCase = true) }
            .mapValues { it.value.first() }
        return UploadTicket(uploadId, presigned.url().toString(), "PUT", headers, presigned.expiration())
    }

    /** S3 파일을 먼저 지우고, 성공하면 DB 세션을 지운다 (업로드·청취 기록·고른 곡은 연쇄 삭제) */
    fun delete(token: String) {
        val session = find(token)
        val keys = uploads.keysOfSession(session.id)
        if (keys.isNotEmpty()) {
            try {
                s3.deleteObjects { req ->
                    req.bucket(props.upload.bucket)
                        .delete(Delete.builder().objects(keys.map { ObjectIdentifier.builder().key(it).build() }).build())
                }
            } catch (e: Exception) {
                log.error("S3 삭제 실패 (세션 {}, 파일 {}개)", session.id, keys.size, e)
                throw ApiException.storage("파일을 지우지 못했습니다. 다시 시도해 주세요")
            }
        }
        sessions.delete(session.id)
        log.info("세션 삭제: {} (파일 {}개)", Tokens.mask(token), keys.size)
    }
}
