package com.futurex.services.FutureXCourseCatalog;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@RestController
public class CatalogController {

    private static final String COURSE_SERVICE = "courseService";

    @Autowired
    private EurekaClient client;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    @Autowired
    private Tracer tracer;

    // Last successful response from fx-course-service, served by the fallback (graceful degradation)
    private final AtomicReference<String> lastKnownCourses = new AtomicReference<>();

    @GetMapping("/")
    @CircuitBreaker(name = COURSE_SERVICE, fallbackMethod = "fallbackGetCatalogHome")
    @Retry(name = COURSE_SERVICE)
    public String getCatalogHome() {
        return traced("getCatalogHome", () -> {
            String courseAppMesage = restTemplate.getForObject(getCourseAppURL(), String.class);

            return("Welcome to FutureX Course Catalog "+courseAppMesage);
        });
    }

    @GetMapping("/catalog")
    @CircuitBreaker(name = COURSE_SERVICE, fallbackMethod = "fallbackGetCatalog")
    @Retry(name = COURSE_SERVICE)
    public String getCatalog() {
        return traced("getCatalog", () -> {
            String courseAppURL = getCourseAppURL()+"/courses";
            String courses = restTemplate.getForObject(courseAppURL,String.class);
            lastKnownCourses.set(courses);

            return("Our courses are "+courses);
        });
    }

    @GetMapping("/firstcourse")
    @CircuitBreaker(name = COURSE_SERVICE, fallbackMethod = "fallbackGetSpecificCourse")
    @Retry(name = COURSE_SERVICE)
    public String getSpecificCourse() {
        return traced("getSpecificCourse", () -> {
            String courseAppURL = getCourseAppURL()+"/1";
            Course course = restTemplate.getForObject(courseAppURL,Course.class);

            return("Our first course is "+course.getCoursename());
        });
    }

    // Same call as /catalog, but protected programmatically through Spring Cloud's
    // CircuitBreakerFactory (circuit breaker + time limiter configured in Resilience4JConfig)
    @GetMapping("/catalog/factory")
    public String getCatalogWithFactory() {
        return traced("getCatalogWithFactory", () -> circuitBreakerFactory.create("courseServiceFactory").run(
                () -> "Our courses are "+restTemplate.getForObject(getCourseAppURL()+"/courses",String.class),
                throwable -> fallbackEvent("Fallback (CircuitBreakerFactory): Unable to retrieve course catalog. Cause: "+describe(throwable), throwable)));
    }

    public String fallbackGetCatalogHome(Exception ex) {
        recordFallback(ex);
        return "Welcome to FutureX Course Catalog (Fallback: course service is unavailable. Cause: "+describe(ex)+")";
    }

    public String fallbackGetCatalog(Exception ex) {
        recordFallback(ex);
        String cached = lastKnownCourses.get();
        if (cached == null) {
            return "Fallback: Unable to retrieve course catalog. Cause: "+describe(ex);
        }
        return "Fallback: Unable to retrieve course catalog. Last known courses are "+cached+" (cause: "+describe(ex)+")";
    }

    public String fallbackGetSpecificCourse(Exception ex) {
        recordFallback(ex);
        return "Fallback: Unable to retrieve the first course. Cause: "+describe(ex);
    }

    private String getCourseAppURL() {
        InstanceInfo instanceInfo = client.getNextServerFromEureka("fx-course-service",false);
        return instanceInfo.getHomePageUrl();
    }

    // Runs the operation inside a manual span made current, so the outgoing RestTemplate call becomes its
    // child. The span is inside @CircuitBreaker/@Retry: each retry attempt gets its own span
    private <T> T traced(String spanName, Supplier<T> operation) {
        Span span = tracer.spanBuilder(spanName).startSpan();
        try (Scope scope = span.makeCurrent()) {
            return operation.get();
        } catch (RuntimeException ex) {
            span.recordException(ex);
            span.setStatus(StatusCode.ERROR);
            throw ex;
        } finally {
            span.end();
        }
    }

    // Marks on the request span that the response came from a fallback, and why
    private static void recordFallback(Throwable ex) {
        Span.current().addEvent("fallback", Attributes.of(
                AttributeKey.stringKey("fallback.cause"), describe(ex)));
    }

    private static String fallbackEvent(String response, Throwable ex) {
        recordFallback(ex);
        return response;
    }

    private static String describe(Throwable ex) {
        return ex.getClass().getSimpleName()+" - "+ex.getMessage();
    }

}
