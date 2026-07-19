package com.sbp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SbpApplication {
    public static void main(String[] args) {
        SpringApplication.run(SbpApplication.class, args);
    }
}
