package com.futurex.services.FutureXCourseCatalog;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.atomic.AtomicReference;

@RestController
public class CatalogController {

    private static final String COURSE_SERVICE = "courseService";

    @Autowired
    private EurekaClient client;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    // Last successful response from fx-course-service, served by the fallback (graceful degradation)
    private final AtomicReference<String> lastKnownCourses = new AtomicReference<>();

    @GetMapping("/")
    @CircuitBreaker(name = COURSE_SERVICE, fallbackMethod = "fallbackGetCatalogHome")
    @Retry(name = COURSE_SERVICE)
    public String getCatalogHome() {
        String courseAppMesage = restTemplate.getForObject(getCourseAppURL(), String.class);

        return("Welcome to FutureX Course Catalog "+courseAppMesage);
    }

    @GetMapping("/catalog")
    @CircuitBreaker(name = COURSE_SERVICE, fallbackMethod = "fallbackGetCatalog")
    @Retry(name = COURSE_SERVICE)
    public String getCatalog() {
        String courseAppURL = getCourseAppURL()+"/courses";
        String courses = restTemplate.getForObject(courseAppURL,String.class);
        lastKnownCourses.set(courses);

        return("Our courses are "+courses);
    }

    @GetMapping("/firstcourse")
    @CircuitBreaker(name = COURSE_SERVICE, fallbackMethod = "fallbackGetSpecificCourse")
    @Retry(name = COURSE_SERVICE)
    public String getSpecificCourse() {
        String courseAppURL = getCourseAppURL()+"/1";
        Course course = restTemplate.getForObject(courseAppURL,Course.class);

        return("Our first course is "+course.getCoursename());
    }

    // Same call as /catalog, but protected programmatically through Spring Cloud's
    // CircuitBreakerFactory (circuit breaker + time limiter configured in Resilience4JConfig)
    @GetMapping("/catalog/factory")
    public String getCatalogWithFactory() {
        return circuitBreakerFactory.create("courseServiceFactory").run(
                () -> "Our courses are "+restTemplate.getForObject(getCourseAppURL()+"/courses",String.class),
                throwable -> "Fallback (CircuitBreakerFactory): Unable to retrieve course catalog. Cause: "+describe(throwable));
    }

    public String fallbackGetCatalogHome(Exception ex) {
        return "Welcome to FutureX Course Catalog (Fallback: course service is unavailable. Cause: "+describe(ex)+")";
    }

    public String fallbackGetCatalog(Exception ex) {
        String cached = lastKnownCourses.get();
        if (cached == null) {
            return "Fallback: Unable to retrieve course catalog. Cause: "+describe(ex);
        }
        return "Fallback: Unable to retrieve course catalog. Last known courses are "+cached+" (cause: "+describe(ex)+")";
    }

    public String fallbackGetSpecificCourse(Exception ex) {
        return "Fallback: Unable to retrieve the first course. Cause: "+describe(ex);
    }

    private String getCourseAppURL() {
        InstanceInfo instanceInfo = client.getNextServerFromEureka("fx-course-service",false);
        return instanceInfo.getHomePageUrl();
    }

    private static String describe(Throwable ex) {
        return ex.getClass().getSimpleName()+" - "+ex.getMessage();
    }

}
