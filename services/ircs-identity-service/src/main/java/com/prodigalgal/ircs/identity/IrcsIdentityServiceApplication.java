package com.prodigalgal.ircs.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(
        scanBasePackages = "com.prodigalgal.ircs",
        exclude = UserDetailsServiceAutoConfiguration.class)
public class IrcsIdentityServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IrcsIdentityServiceApplication.class, args);
    }
}

