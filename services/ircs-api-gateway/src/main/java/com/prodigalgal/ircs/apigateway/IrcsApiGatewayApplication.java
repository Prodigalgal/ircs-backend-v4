package com.prodigalgal.ircs.apigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.prodigalgal.ircs")
public class IrcsApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(IrcsApiGatewayApplication.class, args);
    }
}
