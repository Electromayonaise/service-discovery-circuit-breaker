package com.futurex.services.FutureXCourseApp;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigInteger;
import java.util.List;
import java.util.function.Supplier;

@RestController
public class CourseController {

    private static final Logger logger = LoggerFactory.getLogger(CourseController.class);

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private Tracer tracer;

    @Autowired
    private MeterRegistry meterRegistry;

    @RequestMapping("/")
    public String getCourseAppHome() {

        return traced("getCourseAppHome", () -> "Course App Home");
    }

    @RequestMapping("/courses")
    public List<Course> getCourses() {

        return traced("getCourses", () -> {
            logger.info("Fetching all courses");
            meterRegistry.counter("courses.accessed", "endpoint", "/courses").increment();
            List<Course> courses = courseRepository.findAll();
            logger.info("Fetched {} courses", courses.size());
            return courses;
        });
    }

    @RequestMapping("/{id}")
    public Course getSpecificCourse(@PathVariable("id") BigInteger id ) {
        return traced("getSpecificCourse", () -> {
            Span.current().setAttribute("course.id", id.longValue());
            logger.info("Fetching course {}", id);
            meterRegistry.counter("courses.accessed", "endpoint", "/{id}").increment();
            return courseRepository.getOne(id);
        });
    }

    @RequestMapping(method = RequestMethod.POST, value="/courses")
    public void saveCourse(@RequestBody Course course) {
        logger.info("Saving course {}", course.getCourseid());
        courseRepository.save(course);
    }

    @RequestMapping(method = RequestMethod.DELETE,value = "{id}")
    public void deleteCourse(@PathVariable BigInteger id) {
        logger.info("Deleting course {}", id);
        courseRepository.deleteById(id);
    }

    // Runs the operation inside a manual span made current, so the spans created while it runs
    // (repository, JDBC) become its children and the log lines carry its trace_id/span_id; errors are
    // recorded on the span and logged
    private <T> T traced(String spanName, Supplier<T> operation) {
        Span span = tracer.spanBuilder(spanName).startSpan();
        Scope scope = span.makeCurrent();
        try {
            return operation.get();
        } catch (RuntimeException ex) {
            span.recordException(ex);
            span.setStatus(StatusCode.ERROR);
            logger.error("{} failed: {} - {}", spanName, ex.getClass().getSimpleName(), ex.getMessage());
            throw ex;
        } finally {
            scope.close();
            span.end();
        }
    }

}
