package com.prodigalgal.ircs.messaging;

import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

@Configuration
@ConditionalOnClass(TopicExchange.class)
@ConditionalOnProperty(prefix = "ircs.messaging.rabbit-topology", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RabbitTopologyConfiguration {

    @Value("${ircs.messaging.rabbit-topology.retry-delay-ms:30000}")
    private long retryDelayMs = 30000L;

    @Bean
    public Declarables ircsRabbitTopology() {
        Map<String, TopicExchange> exchanges = new LinkedHashMap<>();
        for (QueueTopic topic : QueueTopic.values()) {
            exchanges.putIfAbsent(topic.exchange(), new TopicExchange(topic.exchange(), true, false));
        }
        exchanges.putIfAbsent(QueueTopic.Names.DLX, new TopicExchange(QueueTopic.Names.DLX, true, false));
        exchanges.putIfAbsent(QueueTopic.Names.DOMAIN_EVENT_X, new TopicExchange(QueueTopic.Names.DOMAIN_EVENT_X, true, false));

        Collection<Declarable> declarables = new java.util.ArrayList<>(exchanges.values());
        TopicExchange dlx = exchanges.get(QueueTopic.Names.DLX);

        for (QueueTopic topic : QueueTopic.values()) {
            Queue queue = QueueBuilder.durable(topic.queueName())
                    .withArgument("x-dead-letter-exchange", QueueTopic.Names.DLX)
                    .withArgument("x-dead-letter-routing-key", topic.dlqName())
                    .build();
            Queue dlq = QueueBuilder.durable(topic.dlqName()).build();
            Queue retryQueue = QueueBuilder.durable(topic.retryName())
                    .withArgument("x-message-ttl", retryDelayMs)
                    .withArgument("x-dead-letter-exchange", topic.exchange())
                    .withArgument("x-dead-letter-routing-key", topic.routingKey())
                    .build();

            declarables.add(queue);
            declarables.add(dlq);
            declarables.add(retryQueue);
            declarables.add(BindingBuilder.bind(queue)
                    .to(exchanges.get(topic.exchange()))
                    .with(topic.routingKey()));
            declarables.add(BindingBuilder.bind(retryQueue)
                    .to(exchanges.get(topic.exchange()))
                    .with(topic.retryRoutingKey()));
            declarables.add(BindingBuilder.bind(dlq)
                    .to(dlx)
                    .with(topic.dlqName()));
        }

        return new Declarables(declarables);
    }

    public static boolean ownsAny(String serviceName, QueueTopic... topics) {
        return Arrays.stream(topics)
                .allMatch(topic -> serviceName.equals(QueueOwnership.OWNER_BY_TOPIC.get(topic)));
    }
}
