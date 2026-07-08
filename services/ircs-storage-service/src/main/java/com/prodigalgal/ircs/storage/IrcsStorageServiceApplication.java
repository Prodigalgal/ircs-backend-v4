package com.prodigalgal.ircs.storage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.prodigalgal.ircs")
public class IrcsStorageServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IrcsStorageServiceApplication.class, args);
    }
}

