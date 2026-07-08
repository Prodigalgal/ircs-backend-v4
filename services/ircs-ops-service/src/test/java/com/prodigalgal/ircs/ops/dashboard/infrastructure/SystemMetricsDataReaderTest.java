package com.prodigalgal.ircs.ops.dashboard.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.ops.infrastructure.rabbit.RabbitManagementQueueClient;
import com.prodigalgal.ircs.ops.infrastructure.rabbit.RabbitManagementQueueSnapshot;
import com.prodigalgal.ircs.ops.infrastructure.rabbit.RabbitManagementQueues;
import com.prodigalgal.ircs.ops.queue.domain.QueueState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnectionFactory;

class SystemMetricsDataReaderTest {

    private final RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
    private final RedisConnectionFactory redisConnectionFactory = mock(RedisConnectionFactory.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<com.prodigalgal.ircs.common.work.RuntimeWorkQueue> runtimeWorkQueueProvider =
            mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AggregationOpsStatsClient> aggregationOpsStatsClientProvider =
            mock(ObjectProvider.class);
    private final RabbitManagementQueueClient rabbitManagementQueueClient = mock(RabbitManagementQueueClient.class);
    private final SystemMetricsDataReader reader = new SystemMetricsDataReader(
            rabbitAdmin,
            redisConnectionFactory,
            new SimpleMeterRegistry(),
            runtimeWorkQueueProvider,
            aggregationOpsStatsClientProvider,
            rabbitManagementQueueClient);

    @Test
    void queueStatePrefersNativeRabbitTotalWhenManagementSnapshotIsAvailable() {
        when(rabbitManagementQueueClient.fetchQueueSnapshots()).thenReturn(Optional.of(new RabbitManagementQueues(null, List.of(
                new RabbitManagementQueueSnapshot("q.task.detail.dlq", 7, 5, 12, 0)
        ))));

        QueueState state = reader.queueState("q.task.detail.dlq");

        assertThat(state.messageCount()).isEqualTo(12);
        assertThat(state.consumerCount()).isZero();
        verifyNoInteractions(rabbitAdmin);
    }

    @Test
    void queueStateFallsBackToRabbitAdminWhenManagementSnapshotIsUnavailable() {
        Properties props = new Properties();
        props.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 4);
        props.put(RabbitAdmin.QUEUE_CONSUMER_COUNT, 1);
        when(rabbitManagementQueueClient.fetchQueueSnapshots()).thenReturn(Optional.empty());
        when(rabbitAdmin.getQueueProperties("q.task.detail.dlq")).thenReturn(props);

        QueueState state = reader.queueState("q.task.detail.dlq");

        assertThat(state.messageCount()).isEqualTo(4);
        assertThat(state.consumerCount()).isEqualTo(1);
    }
}
