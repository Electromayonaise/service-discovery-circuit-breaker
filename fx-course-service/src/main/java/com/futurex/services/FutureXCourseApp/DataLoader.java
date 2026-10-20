package com.futurex.services.FutureXCourseApp;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigInteger;

@Component
public class DataLoader implements CommandLineRunner {

    @Autowired
    private CourseRepository courseRepository;

    @Override
    public void run(String... args) throws Exception {
        // Load sample data
        courseRepository.save(new Course(BigInteger.valueOf(1), "Spring Boot Fundamentals", "John Doe"));
        courseRepository.save(new Course(BigInteger.valueOf(2), "Advanced Java Programming", "Jane Smith"));
        courseRepository.save(new Course(BigInteger.valueOf(3), "Microservices Architecture", "Bob Johnson"));

        System.out.println("Sample courses loaded successfully.");
    }
}