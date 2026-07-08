package com.prodigalgal.ircs.scraper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.prodigalgal.ircs")
public class IrcsScraperServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IrcsScraperServiceApplication.class, args);
    }
}

