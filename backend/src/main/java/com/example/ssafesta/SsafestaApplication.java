package com.example.ssafesta;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SsafestaApplication {

    public static void main(String[] args) {
        SpringApplication.run(SsafestaApplication.class, args);
    }

}
