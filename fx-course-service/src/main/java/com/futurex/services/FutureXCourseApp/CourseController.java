package com.futurex.services.FutureXCourseApp;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigInteger;
import java.util.List;
import java.util.function.Supplier;

@RestController
public class CourseController {

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private Tracer tracer;

    @RequestMapping("/")
    public String getCourseAppHome() {

        return traced("getCourseAppHome", () -> "Course App Home");
    }

    @RequestMapping("/courses")
    public List<Course> getCourses() {

        return traced("getCourses", () -> courseRepository.findAll());
    }

    @RequestMapping("/{id}")
    public Course getSpecificCourse(@PathVariable("id") BigInteger id ) {
        return traced("getSpecificCourse", () -> {
            Span.current().setAttribute("course.id", id.longValue());
            return courseRepository.getOne(id);
        });
    }

    @RequestMapping(method = RequestMethod.POST, value="/courses")
    public void saveCourse(@RequestBody Course course) {
        courseRepository.save(course);
    }

    @RequestMapping(method = RequestMethod.DELETE,value = "{id}")
    public void deleteCourse(@PathVariable BigInteger id) {
        courseRepository.deleteById(id);
    }

    // Runs the operation inside a manual span made current, so the spans created while it runs
    // (repository, JDBC) become its children; errors are recorded on the span
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

}
