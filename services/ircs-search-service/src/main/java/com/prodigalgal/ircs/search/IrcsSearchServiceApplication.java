package com.prodigalgal.ircs.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.prodigalgal.ircs")
public class IrcsSearchServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IrcsSearchServiceApplication.class, args);
    }
}

