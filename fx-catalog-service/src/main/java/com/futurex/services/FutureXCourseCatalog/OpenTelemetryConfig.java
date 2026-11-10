package com.futurex.services.FutureXCourseCatalog;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.ResourceAttributes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * OpenTelemetry SDK for the three signals, all exported by OTLP/gRPC to the OpenTelemetry Collector:
 * <ul>
 *   <li>traces: manual spans and the spans of the Spring Boot starter instrumentation (HTTP server/client, JDBC);</li>
 *   <li>metrics: the OpenTelemetry metrics of that instrumentation (e.g. http.server.request.duration);</li>
 *   <li>logs: every Logback event, through the OpenTelemetryAppender declared in logback-spring.xml (the starter
 *       installs this OpenTelemetry instance into it).</li>
 * </ul>
 * Micrometer metrics (custom timers/counters, JVM, Resilience4j) go to the Collector through the Micrometer OTLP
 * registry instead (management.otlp.metrics.export.* in application.properties).
 */
@Configuration
public class OpenTelemetryConfig {

    @Value("${spring.application.name}")
    private String applicationName;

    // OTLP gRPC endpoint of the OpenTelemetry Collector (otel.exporter.otlp.endpoint in application.properties)
    @Value("${otel.exporter.otlp.endpoint}")
    private String otlpEndpoint;

    // Spring calls close() on shutdown, which flushes the pending spans, metrics and logs. It replaces the
    // guide's JVM shutdown hook, which could run while Spring is still stopping and drop the last logs
    @Bean(destroyMethod = "close")
    public OpenTelemetry openTelemetry() {
        Resource resource = Resource.getDefault()
                .merge(Resource.create(Attributes.of(ResourceAttributes.SERVICE_NAME, applicationName)));

        OtlpGrpcSpanExporter spanExporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(otlpEndpoint)
                .build();

        SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                .setResource(resource)
                .build();

        OtlpGrpcMetricExporter metricExporter = OtlpGrpcMetricExporter.builder()
                .setEndpoint(otlpEndpoint)
                .build();

        SdkMeterProvider sdkMeterProvider = SdkMeterProvider.builder()
                // Every 10 s (the default is 60 s), in line with the 10 s scrape interval of Prometheus
                .registerMetricReader(PeriodicMetricReader.builder(metricExporter)
                        .setInterval(Duration.ofSeconds(10))
                        .build())
                .setResource(resource)
                .build();

        OtlpGrpcLogRecordExporter logExporter = OtlpGrpcLogRecordExporter.builder()
                .setEndpoint(otlpEndpoint)
                .build();

        SdkLoggerProvider sdkLoggerProvider = SdkLoggerProvider.builder()
                .addLogRecordProcessor(BatchLogRecordProcessor.builder(logExporter).build())
                .setResource(resource)
                .build();

        return OpenTelemetrySdk.builder()
                .setTracerProvider(sdkTracerProvider)
                .setMeterProvider(sdkMeterProvider)
                .setLoggerProvider(sdkLoggerProvider)
                // W3C Trace Context: outgoing calls carry the traceparent header, so the spans of both
                // services join the same trace
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .buildAndRegisterGlobal();
    }

    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer(applicationName);
    }
}
