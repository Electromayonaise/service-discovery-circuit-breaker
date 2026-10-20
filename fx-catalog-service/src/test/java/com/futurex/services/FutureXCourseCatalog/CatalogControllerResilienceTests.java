package com.futurex.services.FutureXCourseCatalog;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "resilience4j.retry.instances.courseService.waitDuration=10ms"
})
@AutoConfigureMockMvc
class CatalogControllerResilienceTests {

    private static final String COURSES_JSON = "[{\"courseid\":1,\"coursename\":\"Spring Boot Fundamentals\",\"author\":\"John Doe\"}]";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @MockBean
    private EurekaClient eurekaClient;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("courseService");
        circuitBreaker.reset();
    }

    @Test
    void catalogIsServedFromCourseServiceDiscoveredThroughEureka() throws Exception {
        courseServiceRegisteredAt("http://course-host:8001/");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://course-host:8001/courses"))
                .andRespond(withSuccess(COURSES_JSON, MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/catalog"))
                .andExpect(status().isOk())
                .andExpect(content().string("Our courses are " + COURSES_JSON));

        server.verify();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void failingCallIsRetriedThenAnsweredByFallback() throws Exception {
        courseServiceUnavailable();

        mockMvc.perform(get("/catalog"))
                .andExpect(status().isOk())
                .andExpect(content().string(startsWith("Fallback: Unable to retrieve course catalog.")));

        // maxAttempts=3: the call is retried inside the circuit breaker, which records a single failure
        verify(eurekaClient, times(3)).getNextServerFromEureka(anyString(), anyBoolean());
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void circuitOpensAfterFailureThresholdAndStopsCallingCourseService() throws Exception {
        courseServiceUnavailable();

        // minimumNumberOfCalls=5 with 100% failures (> failureRateThreshold=50)
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/firstcourse"))
                    .andExpect(content().string(startsWith("Fallback: Unable to retrieve the first course.")));
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        mockMvc.perform(get("/catalog"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("CallNotPermittedException")));

        // 5 requests x 3 attempts; the request made while OPEN never reached Eureka
        verify(eurekaClient, times(15)).getNextServerFromEureka(anyString(), anyBoolean());
    }

    @Test
    void fallbackServesLastKnownCatalog() throws Exception {
        courseServiceRegisteredAt("http://course-host:8001/");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://course-host:8001/courses"))
                .andRespond(withSuccess(COURSES_JSON, MediaType.APPLICATION_JSON));
        mockMvc.perform(get("/catalog")).andExpect(status().isOk());

        courseServiceUnavailable();

        mockMvc.perform(get("/catalog"))
                .andExpect(content().string(startsWith("Fallback: Unable to retrieve course catalog. Last known courses are " + COURSES_JSON)));
    }

    @Test
    void homeAndFactoryEndpointsUseTheirFallbacks() throws Exception {
        courseServiceUnavailable();

        mockMvc.perform(get("/"))
                .andExpect(content().string(startsWith("Welcome to FutureX Course Catalog (Fallback:")));
        mockMvc.perform(get("/catalog/factory"))
                .andExpect(content().string(startsWith("Fallback (CircuitBreakerFactory):")));
    }

    private void courseServiceRegisteredAt(String homePageUrl) {
        InstanceInfo instanceInfo = mock(InstanceInfo.class);
        when(instanceInfo.getHomePageUrl()).thenReturn(homePageUrl);
        when(eurekaClient.getNextServerFromEureka("fx-course-service", false)).thenReturn(instanceInfo);
    }

    private void courseServiceUnavailable() {
        when(eurekaClient.getNextServerFromEureka("fx-course-service", false))
                .thenThrow(new RuntimeException("No matches for the virtual host name :fx-course-service"));
    }
}
