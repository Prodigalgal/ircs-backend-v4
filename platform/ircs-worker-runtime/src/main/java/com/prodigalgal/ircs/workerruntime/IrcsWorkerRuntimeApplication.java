package com.prodigalgal.ircs.workerruntime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = "com.prodigalgal.ircs")
@ComponentScan(
        nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class,
        basePackages = {
                "com.prodigalgal.ircs.workerruntime",
                "com.prodigalgal.ircs.common",
                "com.prodigalgal.ircs.contracts",
                "com.prodigalgal.ircs.messaging",
                "com.prodigalgal.ircs.observability",
                "com.prodigalgal.ircs.aggregation",
                "com.prodigalgal.ircs.contentsafety",
                "com.prodigalgal.ircs.content",
                "com.prodigalgal.ircs.ingestion",
                "com.prodigalgal.ircs.magnet",
                "com.prodigalgal.ircs.metadata",
                "com.prodigalgal.ircs.normalization",
                "com.prodigalgal.ircs.notification",
                "com.prodigalgal.ircs.ops",
                "com.prodigalgal.ircs.opsalert",
                "com.prodigalgal.ircs.scraper",
                "com.prodigalgal.ircs.search",
                "com.prodigalgal.ircs.storage",
                "com.prodigalgal.ircs.task"
        },
        excludeFilters = {
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.prodigalgal\\.ircs\\..*\\.[A-Za-z0-9]+Application"
                ),
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.prodigalgal\\.ircs\\..*\\.[A-Za-z0-9]+(Service|Worker)AuditConfiguration"
                )
        }
)
@ImportRuntimeHints(IrcsWorkerRuntimeHints.class)
public class IrcsWorkerRuntimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(IrcsWorkerRuntimeApplication.class, args);
    }
}
