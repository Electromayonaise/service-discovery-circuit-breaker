package com.futurex.services.FutureXCourseApp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.math.BigInteger;

@Entity
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Course {

    @Id
    private BigInteger courseid;
    private String coursename;
    private String author;

    public Course() {
    }

    public Course(BigInteger courseid, String coursename, String author) {
        this.courseid = courseid;
        this.coursename = coursename;
        this.author = author;
    }

    // Getters and setters
    public BigInteger getCourseid() {
        return courseid;
    }

    public void setCourseid(BigInteger courseid) {
        this.courseid = courseid;
    }

    public String getCoursename() {
        return coursename;
    }

    public void setCoursename(String coursename) {
        this.coursename = coursename;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }
}