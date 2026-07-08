package com.prodigalgal.ircs.content;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.prodigalgal.ircs")
public class IrcsContentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IrcsContentServiceApplication.class, args);
    }
}

