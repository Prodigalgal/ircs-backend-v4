package com.prodigalgal.ircs.opsalert;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.prodigalgal.ircs")
@ConfigurationPropertiesScan(basePackages = "com.prodigalgal.ircs.opsalert")
@EnableScheduling
public class IrcsOpsAlertServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IrcsOpsAlertServiceApplication.class, args);
    }
}
