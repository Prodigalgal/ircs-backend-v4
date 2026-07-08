package com.prodigalgal.ircs.aggregation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.prodigalgal.ircs")
public class IrcsAggregationWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(IrcsAggregationWorkerApplication.class, args);
    }
}

