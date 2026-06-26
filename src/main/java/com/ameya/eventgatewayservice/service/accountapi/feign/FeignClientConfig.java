package com.ameya.eventgatewayservice.service.accountapi.feign;

import feign.Logger;
import feign.RequestInterceptor;
import feign.Retryer;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Objects;

@Configuration
public class FeignClientConfig {

    /**
     * Manually propagates the current trace context onto outgoing Feign
     * requests. Feign builds its own HTTP client under the hood and does NOT
     * automatically pick up Micrometer's RestClient/RestTemplate
     * instrumentation, so without this the Account Service would start a
     * brand new, disconnected trace.
     */
    @Bean
    public RequestInterceptor traceHeaderPropagationInterceptor(Tracer tracer, Propagator propagator) {
        return requestTemplate -> {
            if (tracer.currentSpan() == null) {
                return;
            }
            propagator.inject(
                    Objects.requireNonNull(tracer.currentTraceContext().context()),
                    requestTemplate,
                    (carrier, key, value) -> carrier.header(key, value)
            );
        };
    }

    // Feign's own retry is disabled - Resilience4j (via the circuit breaker
    // config) owns retry/backoff so behavior isn't governed by two
    // independent, possibly conflicting policies.
    @Bean
    public Retryer feignRetryer() {
        return Retryer.NEVER_RETRY;
    }

    @Bean
    public Logger.Level feignLoggerLevel() {
        return Logger.Level.BASIC;
    }
}