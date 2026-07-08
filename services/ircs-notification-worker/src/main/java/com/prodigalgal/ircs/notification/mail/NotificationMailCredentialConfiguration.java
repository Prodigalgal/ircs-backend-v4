package com.prodigalgal.ircs.notification.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.DefaultOutboundAddressResolver;
import com.prodigalgal.ircs.common.outbound.JdkOutboundTransport;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class NotificationMailCredentialConfiguration {

    @Bean
    CredentialServiceMailCredentialRepository credentialServiceMailCredentialRepository(
            ObjectMapper objectMapper,
            NotificationMailProperties properties) {
        return new CredentialServiceMailCredentialRepository(
                new OutboundHttpClient(
                        new OutboundUrlPolicy(new DefaultOutboundAddressResolver()),
                        new JdkOutboundTransport(properties.getCredentialService().getRequestTimeout())),
                objectMapper,
                properties);
    }
}
