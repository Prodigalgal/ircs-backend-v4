package com.prodigalgal.ircs.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.prodigalgal.ircs")
public class IrcsNotificationWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(IrcsNotificationWorkerApplication.class, args);
    }
}

