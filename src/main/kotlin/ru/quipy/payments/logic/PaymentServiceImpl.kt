package ru.quipy.payments.logic

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.prometheus.metrics.core.metrics.Summary
import io.prometheus.metrics.model.registry.PrometheusRegistry
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
    meterRegistry: MeterRegistry,
    prometheusRegistry: PrometheusRegistry,
) : PaymentService {
    companion object {
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
            .quantile(0.99, 0.005)
            .labelNames("status_code")
            .register(prometheusRegistry)

    private val maxRetries = 2
    private val retryCodes: List<Int> = listOf(429, 500, 502, 503, 504)

    override fun submitPaymentRequest(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        for (account in paymentAccounts) {
            for (i in 1..maxRetries) {
                val start = System.currentTimeMillis()
                val res = account.performPaymentAsync(paymentId, amount, paymentStartedAt, deadline)
                val duration = System.currentTimeMillis() - start
                requestLatency.labelValues(res.second.toString()).observe(duration.toDouble())
                if (res.first) {
                    successCounter.increment()
                    break
                } else if (retryCodes.contains(res.second)) {
                    failCounter.increment()
                    Thread.sleep((6600 * i).toLong())
                }
            }
        }
    }
}