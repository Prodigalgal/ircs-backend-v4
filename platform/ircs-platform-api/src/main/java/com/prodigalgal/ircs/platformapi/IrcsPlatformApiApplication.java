package com.prodigalgal.ircs.platformapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.context.annotation.ImportRuntimeHints;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = "com.prodigalgal.ircs")
@ComponentScan(
        nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class,
        basePackages = {
                "com.prodigalgal.ircs.platformapi",
                "com.prodigalgal.ircs.common",
                "com.prodigalgal.ircs.contracts",
                "com.prodigalgal.ircs.messaging",
                "com.prodigalgal.ircs.observability",
                "com.prodigalgal.ircs.apigateway",
                "com.prodigalgal.ircs.catalog",
                "com.prodigalgal.ircs.config",
                "com.prodigalgal.ircs.content",
                "com.prodigalgal.ircs.credential",
                "com.prodigalgal.ircs.identity",
                "com.prodigalgal.ircs.interaction",
                "com.prodigalgal.ircs.ops",
                "com.prodigalgal.ircs.portal",
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
                ),
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.prodigalgal\\.ircs\\.apigateway\\.(ApiGatewayProxyController|GatewayProxyClient|ApiGatewayRoutes)"
                )
        }
)
@ImportRuntimeHints(IrcsPlatformApiRuntimeHints.class)
public class IrcsPlatformApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(IrcsPlatformApiApplication.class, args);
    }
}
