package com.prodigalgal.ircs.portal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.prodigalgal.ircs")
public class IrcsPortalServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IrcsPortalServiceApplication.class, args);
    }
}

