package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.domain.Event
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


// Advice: always treat time as a Duration
class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentProviderHostPort: String,
    private val token: String,
    private val hedgeDelayMs: Long,
) : PaymentExternalSystemAdapter {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)
        val mapper = ObjectMapper().registerKotlinModule()

        init {
            System.setProperty("jdk.httpclient.connectionPoolSize", "500")
            System.setProperty("jdk.httpclient.keepalive.timeout", "120")
            System.setProperty("jdk.httpclient.receiveBufferSize", "524288")
            System.setProperty("jdk.httpclient.sendBufferSize", "524288")
        }
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val requestTimeout = 3 * requestAverageProcessingTime.toMillis()
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests
    private val retryCodes: List<Int> = listOf(429, 500, 502, 503, 504)


    private val executor = Executors.newCachedThreadPool()

    private val httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .executor(executor)
        .connectTimeout(Duration.ofSeconds(requestTimeout))
        .build()

    private val paymentLimiter =
        SlidingWindowRateLimiter(rate = rateLimitPerSec.toLong(), window = Duration.ofSeconds(1))

    private val semaphore = Semaphore(parallelRequests)
    private var i = 0

    override fun performPaymentAsync(
        paymentId: UUID,
        amount: Int,
        paymentStartedAt: Long,
        deadline: Long
    ): CompletableFuture<PaymentResult> {
        i += 1
        val transactionId = UUID.randomUUID()
        val resultFuture = CompletableFuture<PaymentResult>()

        val remainingTime = maxOf(0, deadline - System.currentTimeMillis())
        if (remainingTime <= 0) {
            logger.warn("[$accountName] Deadline already exceeded for payment $paymentId")
            logProcessingFailure(paymentId, transactionId, "Deadline exceeded before submission")
            resultFuture.complete(PaymentResult(false, 408, "Deadline exceeded before submission"))
            return resultFuture
        }

        val acquired = try {
            semaphore.tryAcquire(remainingTime, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn("[$accountName] Interrupted while waiting for semaphore for payment $paymentId")
            resultFuture.complete(PaymentResult(false, 429, "Semaphore interrupted"))
            return resultFuture
        }

        if (!acquired) {
            logger.warn("[$accountName] Could not acquire semaphore within deadline for payment $paymentId")
            logProcessingFailure(paymentId, transactionId, "Semaphore acquisition timeout")
            resultFuture.complete(PaymentResult(false, 429, "Semaphore acquisition timeout"))
            return resultFuture
        }

        resultFuture.whenComplete { _, _ -> semaphore.release() }

        val deadlineTimeout = maxOf(0, deadline - System.currentTimeMillis())
        if (deadlineTimeout <= 0) {
            logger.warn("[$accountName] Deadline exceeded after semaphore acquisition for $paymentId")
            logProcessingFailure(paymentId, transactionId, "Deadline exceeded after semaphore acquisition")
            resultFuture.complete(PaymentResult(false, 408, "Deadline exceeded"))
            return resultFuture
        }

        if (!paymentLimiter.tickBlocking(Duration.ofMillis(deadlineTimeout))) {
            logger.warn("[$accountName] Rate limiter timeout before payment for $paymentId")
            logProcessingFailure(paymentId, transactionId, "Rate limiter timeout")
            resultFuture.complete(PaymentResult(false, 429, "Rate limiter timeout"))
            return resultFuture
        }

        logger.info("[$accountName] Submitting payment request for payment $paymentId")

        asyncUpdate(paymentId) { state ->
            state.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
        }

        val url =
            "http://$paymentProviderHostPort/external/process?serviceName=$serviceName&token=$token&accountName=$accountName&transactionId=$transactionId&paymentId=$paymentId&amount=$amount"

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .POST(HttpRequest.BodyPublishers.noBody())
            .timeout(Duration.ofMillis(minOf(requestTimeout, deadlineTimeout)))
            .build()

        val responseHandler = java.util.function.BiConsumer<HttpResponse<String>?, Throwable?> { response, throwable ->
            if (resultFuture.isDone) return@BiConsumer

            if (throwable != null) {
                val (status, reason) = when {
                    throwable is java.net.http.HttpTimeoutException ||
                            throwable.cause is java.net.http.HttpTimeoutException -> 408 to "Request timeout."

                    else -> 500 to (throwable.message ?: "Unknown error")
                }
                logger.error(
                    "[$accountName] Payment failed for txId: $transactionId, payment: $paymentId",
                    throwable
                )
                logProcessingFailure(paymentId, transactionId, reason)
                resultFuture.complete(PaymentResult(false, status, reason))
                return@BiConsumer
            }

            val rawBody = response!!.body()
            val body = try {
                mapper.readValue(rawBody, ExternalSysResponse::class.java)
            } catch (e: Exception) {
                logger.error(
                    "[$accountName] Unable to parse response for txId: $transactionId, payment: $paymentId, rawBody: $rawBody",
                    e
                )
                ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, e.message)
            }

            logger.info(
                "[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${body.result}, message: ${body.message}"
            )

            if (retryCodes.contains(response.statusCode())) {
                val retryAfter = System.currentTimeMillis() + 100
                logger.warn("[$accountName] External system returned 429 for txId: $transactionId, payment: $paymentId")
                asyncUpdate(paymentId) { state ->
                    state.logProcessing(false, now(), transactionId, reason = body.message)
                }
                resultFuture.completeExceptionally(TooManyRequestsException(retryAfter))
                return@BiConsumer
            }

            asyncUpdate(paymentId) { state ->
                state.logProcessing(body.result, now(), transactionId, reason = body.message)
            }
            resultFuture.complete(PaymentResult(body.result, response.statusCode(), body.message))
        }

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete(responseHandler)

        CompletableFuture.runAsync({
            if (!resultFuture.isDone) {
                logger.info("[$accountName] Sending hedge request for payment $paymentId, txId: $transactionId")
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete(responseHandler)
            }
        }, CompletableFuture.delayedExecutor(hedgeDelayMs, TimeUnit.MILLISECONDS, executor))

        return resultFuture
    }

    private fun asyncUpdate(paymentId: UUID, action: (PaymentAggregateState) -> Event<PaymentAggregate>) {
        CompletableFuture.runAsync({
            try {
                paymentESService.update(paymentId) { state -> action(state) }
            } catch (e: Exception) {
                logger.error("[$accountName] Failed to update payment aggregate for $paymentId", e)
            }
        }, executor)
    }

    private fun logProcessingFailure(paymentId: UUID, transactionId: UUID, reason: String?) {
        asyncUpdate(paymentId) { state ->
            state.logProcessing(false, now(), transactionId, reason = reason)
        }
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

}

public fun now() = System.currentTimeMillis()
