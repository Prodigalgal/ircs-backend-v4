package com.prodigalgal.ircs.metadata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.prodigalgal.ircs")
public class IrcsMetadataWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(IrcsMetadataWorkerApplication.class, args);
    }
}
