package dev.gyeol.api.web

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

data class ApiError(val code: String, val message: String)

class ApiException(val status: HttpStatus, val code: String, message: String) : RuntimeException(message) {
    companion object {
        fun validation(message: String) = ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", message)
        fun notFound() = ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "세션이 없거나 만료되었습니다")
        fun invalidState(message: String) = ApiException(HttpStatus.CONFLICT, "INVALID_STATE", message)
        fun notReady() = ApiException(HttpStatus.CONFLICT, "NOT_READY", "아직 처리 중입니다")
        fun tooLarge(maxBytes: Long) = ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "TOO_LARGE", "업로드는 ${maxBytes / 1024 / 1024}MB까지입니다")
        fun storage(message: String) = ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_ERROR", message)
    }
}

@RestControllerAdvice
class ApiErrorHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handle(e: ApiException): ResponseEntity<ApiError> =
        ResponseEntity.status(e.status).body(ApiError(e.code, e.message ?: e.code))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException): ResponseEntity<ApiError> =
        ResponseEntity.badRequest().body(ApiError("VALIDATION", "요청 본문을 읽을 수 없습니다"))

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<ApiError> {
        log.error("처리하지 못한 오류", e)
        return ResponseEntity.internalServerError().body(ApiError("INTERNAL", "서버 오류가 발생했습니다"))
    }
}
