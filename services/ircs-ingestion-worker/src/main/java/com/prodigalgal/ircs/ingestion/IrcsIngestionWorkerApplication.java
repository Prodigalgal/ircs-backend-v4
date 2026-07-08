package com.prodigalgal.ircs.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.prodigalgal.ircs")
public class IrcsIngestionWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(IrcsIngestionWorkerApplication.class, args);
    }
}
