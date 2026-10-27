package dev.gyeol.worker.identify

import io.awspring.cloud.sqs.listener.Visibility
import org.springframework.web.client.HttpServerErrorException
import software.amazon.awssdk.services.bedrockruntime.model.ServiceUnavailableException
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException

/**
 * identify·cover 큐는 곡마다 오래 걸릴 수 있어 표시 제한 시간을 길게(15분) 둔다.
 * 그런데 MusicBrainz·Bedrock이 "바쁘다"(503, 호출 한도 초과)고 해서 실패하면 15분을 기다릴 이유가 없으므로 [seconds](기본 30초) 뒤 다시 받게 한다.
 * 예외는 그대로 던져 실패 횟수가 쌓이게 한다 (3번이면 실패 큐).
 */
inline fun <T> retrySoonIfBusy(visibility: Visibility, seconds: Int, block: () -> T): T =
    try {
        block()
    } catch (e: HttpServerErrorException.ServiceUnavailable) {
        visibility.changeTo(seconds)
        throw e
    } catch (e: ServiceUnavailableException) { // Bedrock: "unable to process your request"
        visibility.changeTo(seconds)
        throw e
    } catch (e: ThrottlingException) { // Bedrock 호출 한도 초과
        visibility.changeTo(seconds)
        throw e
    }
