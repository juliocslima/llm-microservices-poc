package br.ufla.poc.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationConsumer {

    private final MeterRegistry meterRegistry;
    private final AtomicLong processedCount = new AtomicLong(0);

    @RabbitListener(queues = "poc.notifications")
    public void handleOrderEvent(Map<String, Object> event) {
        String orderId  = (String) event.get("orderId");
        String eventType = (String) event.get("eventType");

        log.info("Notification received eventType={} orderId={}", eventType, orderId);

        // Simulate notification sending
        log.info("Sending notification to customer for orderId={} eventType={}", orderId, eventType);

        processedCount.incrementAndGet();
        Counter.builder("notifications.processed")
            .tag("eventType", eventType != null ? eventType : "UNKNOWN")
            .register(meterRegistry)
            .increment();
    }
}
