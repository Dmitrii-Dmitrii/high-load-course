package ru.quipy.payments.logic

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.prometheus.metrics.core.metrics.Summary
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.time.Duration
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.toDuration


@Service
class PaymentSystemImpl(
    private val paymentAccounts: List<PaymentExternalSystemAdapter>,
    meterRegistry: MeterRegistry
) : PaymentService {
    companion object {
        val logger = LoggerFactory.getLogger(PaymentSystemImpl::class.java)
    }

    private val successCounter =
        Counter.builder("http_request_success_pay").description("Counts the number of success pays").register(meterRegistry)

    private val failCounter =
        Counter.builder("http_request_fail_pay").description("Counts the number of fail pay").register(meterRegistry)

    private val requestLatency =
        Summary.builder().name("request_latency").quantile(0.85).build()

    private val maxRetries = 25

    override fun submitPaymentRequest(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        for (account in paymentAccounts) {
            for (i in 1..maxRetries) {
                val start = System.currentTimeMillis()
                val res = account.performPaymentAsync(paymentId, amount, paymentStartedAt, deadline)
                val duration = System.currentTimeMillis() - start
                requestLatency.observe(duration.toDouble())
                if (res) {
                    successCounter.increment()
                    break
                } else {
                    failCounter.increment()
                    Thread.sleep((10 * i).toLong())
                }
            }
        }
    }
}