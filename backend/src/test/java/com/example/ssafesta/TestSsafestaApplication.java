package com.example.ssafesta;

import org.springframework.boot.SpringApplication;

public class TestSsafestaApplication {

    public static void main(String[] args) {
        SpringApplication.from(SsafestaApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
