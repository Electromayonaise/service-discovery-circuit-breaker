package com.futurex.services.FutureXCourseCatalog;

import com.netflix.discovery.EurekaClient;
import io.opentelemetry.api.GlobalOpenTelemetry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(properties = "eureka.client.enabled=false")
class FutureXCourseCatalogApplicationTests {

	@MockBean
	private EurekaClient eurekaClient;

	// OpenTelemetryConfig registers the SDK globally; each new Spring test context needs a clean global
	@BeforeAll
	static void resetGlobalOpenTelemetry() {
		GlobalOpenTelemetry.resetForTest();
	}

	@Test
	void contextLoads() {
	}

}
