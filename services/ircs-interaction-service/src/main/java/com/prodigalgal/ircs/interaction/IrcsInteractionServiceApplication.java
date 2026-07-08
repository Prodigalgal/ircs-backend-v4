package com.prodigalgal.ircs.interaction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.prodigalgal.ircs")
public class IrcsInteractionServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IrcsInteractionServiceApplication.class, args);
    }
}

