package com.prodigalgal.ircs.ops.dashboard.infrastructure;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import com.prodigalgal.ircs.ops.infrastructure.rabbit.RabbitManagementQueueClient;
import com.prodigalgal.ircs.ops.infrastructure.rabbit.RabbitManagementQueues;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

class RabbitManagementRateProbeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashOps = org.mockito.Mockito.mock(HashOperations.class);
    @SuppressWarnings("unchecked")
    private final ZSetOperations<String, String> zSetOps = org.mockito.Mockito.mock(ZSetOperations.class);

    @Test
    void springContextCreatesProbeWithRuntimeConfigConstructor() {
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(StringRedisTemplate.class, () -> redisTemplate)
                .withBean(RuntimeConfigService.class, () -> mock(RuntimeConfigService.class))
                .withBean(RabbitManagementQueueClient.class, () -> mock(RabbitManagementQueueClient.class))
                .withUserConfiguration(RabbitManagementRateProbeConfiguration.class)
                .run(context -> org.assertj.core.api.Assertions.assertThat(context)
                        .hasSingleBean(RabbitManagementRateProbe.class));
    }

    @Test
    void recordsPositiveCounterDeltasIntoRateBuckets() throws Exception {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        RabbitManagementQueueClient queueClient = mock(RabbitManagementQueueClient.class);
        when(queueClient.fetchQueues()).thenReturn(Optional.of(new RabbitManagementQueues(
                objectMapper.readTree(queueJson(1, 2, 3, 0)),
                List.of())));
        RabbitManagementRateProbe probe = new RabbitManagementRateProbe(redisTemplate, null, queueClient);

        probe.recordCounters(objectMapper.readTree(queueJson(1, 2, 3, 0)));
        probe.recordCounters(objectMapper.readTree(queueJson(5, 7, 9, 1)));

        verify(hashOps).increment(
                anyString(),
                eq(RabbitManagementRateProbe.metricKey(
                        QueueTopic.INGEST_VIDEO,
                        RabbitManagementRateProbe.RabbitQueueRole.MAIN,
                        RabbitManagementRateProbe.RabbitRateAction.PUBLISHED)),
                eq(4L));
        verify(hashOps).increment(
                anyString(),
                eq(RabbitManagementRateProbe.metricKey(
                        QueueTopic.INGEST_VIDEO,
                        RabbitManagementRateProbe.RabbitQueueRole.MAIN,
                        RabbitManagementRateProbe.RabbitRateAction.ACKED)),
                eq(6L));
        verify(zSetOps, atLeastOnce()).removeRangeByScore(anyString(), anyDouble(), anyDouble());
    }

    private String queueJson(long publish, long deliverGet, long ack, long redeliver) {
        return """
                [
                  {
                    "name": "%s",
                    "message_stats": {
                      "publish": %d,
                      "deliver_get": %d,
                      "ack": %d,
                      "redeliver": %d
                    }
                  }
                ]
                """.formatted(QueueTopic.INGEST_VIDEO.queueName(), publish, deliverGet, ack, redeliver);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(RabbitManagementRateProbe.class)
    static class RabbitManagementRateProbeConfiguration {
    }

}
