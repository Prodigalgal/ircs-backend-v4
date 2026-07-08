package com.prodigalgal.ircs.magnet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.prodigalgal.ircs")
public class IrcsMagnetServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IrcsMagnetServiceApplication.class, args);
    }
}
