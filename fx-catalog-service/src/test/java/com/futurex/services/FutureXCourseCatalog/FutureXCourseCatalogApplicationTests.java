package com.futurex.services.FutureXCourseCatalog;

import com.netflix.discovery.EurekaClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(properties = "eureka.client.enabled=false")
class FutureXCourseCatalogApplicationTests {

	@MockBean
	private EurekaClient eurekaClient;

	@Test
	void contextLoads() {
	}

}
