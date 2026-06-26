package br.ufla.poc.config;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    @Bean public TopicExchange orderExchange() { return new TopicExchange("poc.events", true, false); }
    @Bean public Queue notificationQueue() { return new Queue("poc.notifications", true); }
    @Bean public Binding binding(Queue notificationQueue, TopicExchange orderExchange) {
        return BindingBuilder.bind(notificationQueue).to(orderExchange).with("order.*");
    }
    @Bean public Jackson2JsonMessageConverter messageConverter() { return new Jackson2JsonMessageConverter(); }
    @Bean public RabbitTemplate rabbitTemplate(ConnectionFactory cf, Jackson2JsonMessageConverter conv) {
        RabbitTemplate t = new RabbitTemplate(cf); t.setMessageConverter(conv); return t;
    }
}
