package com.prodigalgal.ircs.credential;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.prodigalgal.ircs")
public class IrcsCredentialServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IrcsCredentialServiceApplication.class, args);
    }
}

