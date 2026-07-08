package com.prodigalgal.ircs.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.prodigalgal.ircs")
public class IrcsCatalogServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IrcsCatalogServiceApplication.class, args);
    }
}

