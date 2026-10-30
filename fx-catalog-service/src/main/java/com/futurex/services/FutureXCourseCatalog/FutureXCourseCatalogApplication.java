package com.futurex.services.FutureXCourseCatalog;

import io.opentelemetry.api.OpenTelemetry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@SpringBootApplication
public class FutureXCourseCatalogApplication {

	public static void main(String[] args) {
		SpringApplication.run(FutureXCourseCatalogApplication.class, args);
	}

	/**
	 * Shared client for calls to fx-course-service, declared as a bean so the OpenTelemetry starter
	 * instruments it. Without timeouts a hung course service would block catalog threads
	 * indefinitely instead of failing and tripping the circuit breaker.
	 */
	@Bean
	public RestTemplate restTemplate(RestTemplateBuilder builder, OpenTelemetry openTelemetry) {
		return builder
				.setConnectTimeout(Duration.ofSeconds(2))
				.setReadTimeout(Duration.ofSeconds(3))
				.build();
	}

}
