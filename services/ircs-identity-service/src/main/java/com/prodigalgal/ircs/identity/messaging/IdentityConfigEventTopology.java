package com.prodigalgal.ircs.identity.messaging;

import com.prodigalgal.ircs.contracts.config.SystemConfigChangedEvent;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(TopicExchange.class)
@ConditionalOnProperty(prefix = "ircs.messaging.rabbit-topology", name = "enabled", havingValue = "true", matchIfMissing = true)
class IdentityConfigEventTopology {

    @Bean
    Declarables identityConfigChangedTopology() {
        TopicExchange exchange = new TopicExchange(QueueTopic.Names.DOMAIN_EVENT_X, true, false);
        org.springframework.amqp.core.Queue queue = QueueBuilder.durable(SystemConfigChangedListener.QUEUE_NAME)
                .withArgument("x-dead-letter-exchange", QueueTopic.Names.DLX)
                .withArgument("x-dead-letter-routing-key", SystemConfigChangedListener.QUEUE_NAME + ".dlq")
                .build();
        org.springframework.amqp.core.Queue dlq = QueueBuilder.durable(SystemConfigChangedListener.QUEUE_NAME + ".dlq")
                .build();
        TopicExchange dlx = new TopicExchange(QueueTopic.Names.DLX, true, false);
        return new Declarables(
                exchange,
                dlx,
                queue,
                dlq,
                BindingBuilder.bind(queue).to(exchange).with(SystemConfigChangedEvent.ROUTING_KEY),
                BindingBuilder.bind(dlq).to(dlx).with(SystemConfigChangedListener.QUEUE_NAME + ".dlq"));
    }
}
