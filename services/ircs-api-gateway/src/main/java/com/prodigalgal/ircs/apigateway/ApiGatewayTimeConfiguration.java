package com.prodigalgal.ircs.apigateway;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ApiGatewayTimeConfiguration {

    @Bean
    Clock apiGatewayClock() {
        return Clock.systemUTC();
    }
}
