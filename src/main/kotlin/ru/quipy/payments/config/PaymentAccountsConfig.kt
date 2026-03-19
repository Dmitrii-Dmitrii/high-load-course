package ru.quipy.payments.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.payments.logic.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.*


@Configuration
class PaymentAccountsConfig {
    companion object {
        private val javaClient = HttpClient.newBuilder().build()
        private val mapper = ObjectMapper().registerKotlinModule().registerModules(JavaTimeModule())
    }

    @Value("\${payment.hostPort}")
    lateinit var paymentProviderHostPort: String

    @Value("\${payment.service-name}")
    lateinit var serviceName: String

    @Value("\${payment.token}")
    lateinit var token: String

    @Value("#{'\${payment.accounts}'.split(',')}")
    lateinit var allowedAccounts: List<String>

    @Value("\${payment.hedge-delay-ms}")
    var hedgeDelayMs: Long = 0

    @Value("\${payment.circuit-breaker.sliding-window-size:30}")
    var cbSlidingWindowSize: Int = 30

    @Value("\${payment.circuit-breaker.failure-rate-threshold:50}")
    var cbFailureRateThreshold: Float = 50f

    @Value("\${payment.circuit-breaker.slow-call-rate-threshold:80}")
    var cbSlowCallRateThreshold: Float = 80f

    @Value("\${payment.circuit-breaker.wait-duration-in-open-state-ms:5000}")
    var cbWaitDurationInOpenStateMs: Long = 5000

    @Value("\${payment.circuit-breaker.permitted-calls-in-half-open:3}")
    var cbPermittedCallsInHalfOpen: Int = 3

    @Value("\${payment.circuit-breaker.minimum-number-of-calls:5}")
    var cbMinimumNumberOfCalls: Int = 5

    @Bean
    fun accountAdapters(
        paymentService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
        meterRegistry: MeterRegistry,
    ): List<PaymentExternalSystemAdapter> {
        val request = HttpRequest.newBuilder()
            .uri(URI("http://${paymentProviderHostPort}/external/accounts?serviceName=$serviceName&token=$token"))
            .GET()
            .build()

        val resp = javaClient.send(request, HttpResponse.BodyHandlers.ofString())

        val cbProperties = CircuitBreakerProperties(
            slidingWindowSize = cbSlidingWindowSize,
            failureRateThreshold = cbFailureRateThreshold,
            slowCallRateThreshold = cbSlowCallRateThreshold,
            waitDurationInOpenStateMs = cbWaitDurationInOpenStateMs,
            permittedCallsInHalfOpen = cbPermittedCallsInHalfOpen,
            minimumNumberOfCalls = cbMinimumNumberOfCalls,
        )

        println("\nPayment accounts list:")
        return mapper.readValue<List<PaymentAccountProperties>>(
            resp.body(),
            mapper.typeFactory.constructCollectionType(List::class.java, PaymentAccountProperties::class.java)
        )
            .filter { it.accountName in allowedAccounts }
            .map { it.copy(enabled = true) }
            .onEach(::println)
            .map {
                PaymentExternalSystemAdapterImpl(
                    it,
                    paymentService,
                    paymentProviderHostPort,
                    token,
                    hedgeDelayMs,
                    cbProperties,
                    meterRegistry
                )
            }
    }
}