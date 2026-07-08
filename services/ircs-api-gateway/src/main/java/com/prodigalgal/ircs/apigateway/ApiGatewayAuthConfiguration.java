package com.prodigalgal.ircs.apigateway;

import com.prodigalgal.ircs.common.security.IrcsJwtRequestAuthenticator;
import com.prodigalgal.ircs.common.security.IrcsJwtRuntimeConfigResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
class ApiGatewayAuthConfiguration {

    @Bean
    IrcsJwtRequestAuthenticator apiGatewayJwtRequestAuthenticator(
            Environment environment,
            @Value("${spring.datasource.url:}") String datasourceUrl,
            @Value("${spring.datasource.username:}") String username,
            @Value("${spring.datasource.password:}") String password) {
        return new IrcsJwtRequestAuthenticator(new IrcsJwtRuntimeConfigResolver(
                environment,
                datasourceUrl,
                username,
                password));
    }
}
