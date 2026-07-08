package com.prodigalgal.ircs.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RestClientAvatarStorageClientContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RestClientAvatarStorageClient.class)
            .withPropertyValues("app.identity.storage-service.base-url=http://storage.test:8080");

    @Test
    void createsBeanWithConfiguredStorageServiceBaseUrl() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(AvatarStorageClient.class)
                .hasSingleBean(RestClientAvatarStorageClient.class));
    }
}
