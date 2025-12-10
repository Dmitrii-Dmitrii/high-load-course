package ru.quipy.payments.logic

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.quipy.common.utils.CallerBlockingRejectedExecutionHandler
import ru.quipy.common.utils.LeakingBucketRateLimiter
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger


@Service
class OrderPayer(meterRegistry: MeterRegistry, @Value("\${payment.rps:16}") private val rateLimitPerSec: Int) {
    private var queueCapacity: Int = 2000

    companion object {
        val logger: Logger = LoggerFactory.getLogger(OrderPayer::class.java)
    }

    @Autowired
    private lateinit var paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>

    @Autowired
    private lateinit var paymentService: PaymentService

    private val paymentExecutor = ThreadPoolExecutor(
        200,
        200,
        100L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(queueCapacity),
        NamedThreadFactory("payment-submission-executor"),
        CallerBlockingRejectedExecutionHandler()
    )

    private val paymentLimiter =
        LeakingBucketRateLimiter(rateLimitPerSec.toLong(), Duration.ofSeconds(1), rateLimitPerSec)

    private val processPaymentsGauge = AtomicInteger()

    init {
        meterRegistry.gauge("process_payments_gauge", processPaymentsGauge)
    }

    fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Long {
        val createdAt = System.currentTimeMillis()

        if (deadline <= createdAt) {
            throw TooManyRequestsException(createdAt + 100)
        }

        val deadlineTimeout = maxOf(0, deadline - createdAt)
        if (!paymentLimiter.tickBlocking(Duration.ofMillis(deadlineTimeout))) {
            throw TooManyRequestsException(createdAt + 100)
        }

        if (paymentExecutor.queue.remainingCapacity() == 0) {
            throw TooManyRequestsException(createdAt + 100)
        }

        val paymentFuture = CompletableFuture.runAsync({
            val createdEvent = paymentESService.create {
                it.create(
                    paymentId,
                    orderId,
                    amount
                )
            }
            logger.trace("Payment {} for order {} created.", createdEvent.paymentId, orderId)

            paymentService.submitPaymentRequest(paymentId, amount, createdAt, deadline).join()
        }, paymentExecutor)

        try {
            paymentFuture.join()
        } catch (e: Exception) {
            var current: Throwable? = e
            while (current != null) {
                if (current is TooManyRequestsException) {
                    throw current
                }
                current = current.cause
            }
            throw e
        }

        processPaymentsGauge.set(paymentExecutor.queue.size)
        return createdAt
    }
}