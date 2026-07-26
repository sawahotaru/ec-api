package com.example.ecapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EcApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(EcApiApplication.class, args);
    }
}
