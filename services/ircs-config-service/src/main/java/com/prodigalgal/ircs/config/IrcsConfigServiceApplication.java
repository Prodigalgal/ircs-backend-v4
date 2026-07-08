package com.prodigalgal.ircs.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.prodigalgal.ircs")
public class IrcsConfigServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IrcsConfigServiceApplication.class, args);
    }
}

