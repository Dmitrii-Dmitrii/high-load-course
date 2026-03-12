package ru.quipy.payments.logic

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.prometheus.metrics.core.metrics.Summary
import io.prometheus.metrics.model.registry.PrometheusRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.quipy.common.utils.NamedThreadFactory
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit


@Service
class PaymentSystemImpl(
    private val paymentAccounts: List<PaymentExternalSystemAdapter>,
    meterRegistry: MeterRegistry,
    prometheusRegistry: PrometheusRegistry,
    private val retryScheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(
        maxOf(2, maxOf(1, paymentAccounts.size) * 2),
        NamedThreadFactory("payment-retry-scheduler")
    ),
    private val baseRetryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
) : PaymentService {
    companion object {
        private const val DEFAULT_RETRY_DELAY_MILLIS = 10L
        val logger = LoggerFactory.getLogger(PaymentSystemImpl::class.java)
    }

    private val successCounter =
        Counter.builder("http_request_success_pay").description("Counts the number of success pays")
            .register(meterRegistry)

    private val failCounter =
        Counter.builder("http_request_fail_pay").description("Counts the number of fail pay").register(meterRegistry)

    private val requestLatency =
        Summary.builder().name("request_latency")
            .help("Request latency.")
            .quantile(0.5, 0.01)
            .quantile(0.8, 0.005)
            .quantile(0.9, 0.005)
            .quantile(0.99, 0.005)
            .labelNames("status_code")
            .register(prometheusRegistry)

    private val maxRetries = 2
    private val retryCodes: List<Int> = listOf(429, 500, 502, 503, 504)

    override fun submitPaymentRequest(
        paymentId: UUID,
        amount: Int,
        paymentStartedAt: Long,
        deadline: Long
    ): CompletableFuture<Void> {
        val activeAccounts = paymentAccounts.filter { it.isEnabled() }
        if (activeAccounts.isEmpty()) {
            logger.warn("No enabled payment accounts to process payment {}", paymentId)
            return CompletableFuture.completedFuture(null)
        }

        val futures = activeAccounts.map { account ->
            dispatchPayment(account, paymentId, amount, paymentStartedAt, deadline, attempt = 1)
        }

        return CompletableFuture.allOf(*futures.toTypedArray())
            .thenApply { null }
    }

    private fun dispatchPayment(
        account: PaymentExternalSystemAdapter,
        paymentId: UUID,
        amount: Int,
        paymentStartedAt: Long,
        deadline: Long,
        attempt: Int,
    ): CompletableFuture<Void> {
        if (deadline <= System.currentTimeMillis()) {
            logger.warn(
                "[{}] Deadline exceeded before attempt {} for payment {}",
                account.name(),
                attempt,
                paymentId
            )
            return CompletableFuture.completedFuture(null)
        }

        val start = System.currentTimeMillis()
        return account.performPaymentAsync(paymentId, amount, paymentStartedAt, deadline)
            .handle { result, throwable ->
                val duration = System.currentTimeMillis() - start
                val statusCode = resolveStatusCode(result, throwable)
                requestLatency.labelValues(statusCode.toString()).observe(duration.toDouble())

                if (throwable != null) {
                    if (throwable is TooManyRequestsException) {
                        logger.warn(
                            "[{}] Payment {} attempt {} failed with TooManyRequestsException, propagating",
                            account.name(),
                            paymentId,
                            attempt
                        )
                        throw throwable
                    }
                    logger.warn(
                        "[{}] Payment {} attempt {} failed with exception: {}",
                        account.name(),
                        paymentId,
                        attempt,
                        throwable.message
                    )
                    handleFailure(account, paymentId, amount, paymentStartedAt, deadline, attempt, statusCode)
                    return@handle null
                }

                if (result?.success == true) {
                    successCounter.increment()
                } else {
                    handleFailure(account, paymentId, amount, paymentStartedAt, deadline, attempt, statusCode)
                }
                null
            }
    }

    private fun handleFailure(
        account: PaymentExternalSystemAdapter,
        paymentId: UUID,
        amount: Int,
        paymentStartedAt: Long,
        deadline: Long,
        attempt: Int,
        statusCode: Int,
    ) {
        if (!retryCodes.contains(statusCode)) {
            return
        }

        failCounter.increment()

        if (attempt >= maxRetries) {
            logger.warn("[{}] Max retries reached for payment {}", account.name(), paymentId)
            return
        }

        val delay = retryDelay(attempt)
        val scheduledAt = System.currentTimeMillis() + delay
        if (scheduledAt >= deadline) {
            logger.warn("[{}] Skip retry for payment {} due to deadline", account.name(), paymentId)
            return
        }

        retryScheduler.schedule(
            {
                dispatchPayment(account, paymentId, amount, paymentStartedAt, deadline, attempt + 1)
            },
            delay,
            TimeUnit.MILLISECONDS
        )
    }

    private fun retryDelay(attempt: Int) = baseRetryDelayMillis * attempt

    private fun resolveStatusCode(result: PaymentResult?, throwable: Throwable?): Int {
        return result?.statusCode ?: when (throwable) {
            is TooManyRequestsException -> 429
            else -> 500
        }
    }
}