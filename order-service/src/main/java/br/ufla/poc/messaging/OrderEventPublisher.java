package br.ufla.poc.messaging;

import br.ufla.poc.domain.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Value("${app.rabbitmq.exchange}")
    private String exchange;

    @Value("${app.rabbitmq.routing.order-created}")
    private String orderCreatedKey;

    public void publishOrderCompleted(Order order) {
        var event = Map.of(
            "eventType", "ORDER_COMPLETED",
            "orderId", order.getId().toString(),
            "customerId", order.getCustomerId(),
            "totalAmount", order.getTotalAmount().toString(),
            "status", order.getStatus().name()
        );
        log.info("Publishing ORDER_COMPLETED orderId={}", order.getId());
        rabbitTemplate.convertAndSend(exchange, orderCreatedKey, event);
    }
}
