package com.prodigalgal.ircs.ops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.prodigalgal.ircs")
public class IrcsOpsServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IrcsOpsServiceApplication.class, args);
    }
}

