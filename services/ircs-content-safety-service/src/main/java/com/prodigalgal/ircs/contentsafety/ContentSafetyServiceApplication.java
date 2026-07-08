package com.prodigalgal.ircs.contentsafety;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ContentSafetyProperties.class)
public class ContentSafetyServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContentSafetyServiceApplication.class, args);
    }
}
