package com.prodigalgal.ircs.task;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.prodigalgal.ircs")
public class IrcsTaskServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IrcsTaskServiceApplication.class, args);
    }
}
