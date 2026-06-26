package br.ufla.poc.service;

import br.ufla.poc.domain.Order;
import br.ufla.poc.domain.Order.OrderStatus;
import br.ufla.poc.domain.OrderItem;
import br.ufla.poc.dto.OrderDtos.*;
import br.ufla.poc.messaging.OrderEventPublisher;
import br.ufla.poc.repository.OrderRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher eventPublisher;
    private final RestTemplate restTemplate;
    private final MeterRegistry meterRegistry;

    @Value("${app.payment-service-url}")
    private String paymentServiceUrl;

    @Value("${app.inventory-service-url}")
    private String inventoryServiceUrl;

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest req) {
        log.info("Creating order for customer={}", req.getCustomerId());

        Timer.Sample timerSample = Timer.start(meterRegistry);

        Order order = new Order();
        order.setCustomerId(req.getCustomerId());

        BigDecimal total = BigDecimal.ZERO;
        for (var item : req.getItems()) {
            OrderItem oi = new OrderItem();
            oi.setOrder(order);
            oi.setProductId(item.getProductId());
            oi.setQuantity(item.getQuantity());
            oi.setUnitPrice(item.getUnitPrice());
            order.getItems().add(oi);
            total = total.add(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }
        order.setTotalAmount(total);
        order = orderRepository.save(order);

        log.info("Order saved orderId={} status={} total={}", order.getId(), order.getStatus(), total);

        // Call payment-service
        boolean paymentOk = processPayment(order);
        if (!paymentOk) {
            order.setStatus(OrderStatus.FAILED);
            orderRepository.save(order);
            counter("orders.failed", "reason", "payment").increment();
            log.error("Payment failed for orderId={}", order.getId());
            timerSample.stop(timer("orders.processing.time", "result", "payment_failed"));
            return toResponse(order);
        }

        order.setStatus(OrderStatus.PAYMENT_PROCESSING);
        orderRepository.save(order);

        // Reserve inventory
        boolean inventoryOk = reserveInventory(order);
        if (!inventoryOk) {
            order.setStatus(OrderStatus.FAILED);
            orderRepository.save(order);
            counter("orders.failed", "reason", "inventory").increment();
            log.error("Inventory reservation failed for orderId={}", order.getId());
            timerSample.stop(timer("orders.processing.time", "result", "inventory_failed"));
            return toResponse(order);
        }

        order.setStatus(OrderStatus.COMPLETED);
        orderRepository.save(order);

        eventPublisher.publishOrderCompleted(order);

        counter("orders.created", "status", "success").increment();
        timerSample.stop(timer("orders.processing.time", "result", "success"));

        log.info("Order completed orderId={}", order.getId());
        return toResponse(order);
    }

    private boolean processPayment(Order order) {
        try {
            log.info("Calling payment-service for orderId={} amount={}", order.getId(), order.getTotalAmount());
            Map<String, Object> paymentReq = Map.of(
                "orderId", order.getId().toString(),
                "amount", order.getTotalAmount()
            );
            var response = restTemplate.postForEntity(
                paymentServiceUrl + "/api/payments", paymentReq, Map.class);

            boolean ok = response.getStatusCode().is2xxSuccessful();
            log.info("Payment response orderId={} status={}", order.getId(), response.getStatusCode());
            return ok;
        } catch (RestClientException e) {
            log.error("Payment service call failed orderId={} error={}", order.getId(), e.getMessage());
            counter("orders.dependency.error", "service", "payment-service").increment();
            return false;
        }
    }

    private boolean reserveInventory(Order order) {
        try {
            log.info("Calling inventory-service for orderId={}", order.getId());
            var items = order.getItems().stream().map(i ->
                Map.of("productId", i.getProductId(), "quantity", i.getQuantity())
            ).toList();
            Map<String, Object> req = Map.of("orderId", order.getId().toString(), "items", items);

            var response = restTemplate.postForEntity(
                inventoryServiceUrl + "/api/inventory/reserve", req, Map.class);

            boolean ok = response.getStatusCode().is2xxSuccessful();
            log.info("Inventory response orderId={} status={}", order.getId(), response.getStatusCode());
            return ok;
        } catch (RestClientException e) {
            log.error("Inventory service call failed orderId={} error={}", order.getId(), e.getMessage());
            counter("orders.dependency.error", "service", "inventory-service").increment();
            return false;
        }
    }

    public List<OrderResponse> listOrders() {
        return orderRepository.findAll().stream().map(this::toResponse).toList();
    }

    public OrderResponse getOrder(UUID id) {
        return orderRepository.findById(id)
            .map(this::toResponse)
            .orElseThrow(() -> new IllegalArgumentException("Order not found: " + id));
    }

    public OrderSummary getSummary() {
        return OrderSummary.builder()
            .total(orderRepository.count())
            .pending(orderRepository.countByStatus(OrderStatus.PENDING))
            .paid(orderRepository.countByStatus(OrderStatus.PAID))
            .failed(orderRepository.countByStatus(OrderStatus.FAILED))
            .build();
    }

    private OrderResponse toResponse(Order o) {
        return OrderResponse.builder()
            .id(o.getId())
            .customerId(o.getCustomerId())
            .status(o.getStatus())
            .totalAmount(o.getTotalAmount())
            .createdAt(o.getCreatedAt())
            .updatedAt(o.getUpdatedAt())
            .items(o.getItems().stream().map(i -> OrderResponse.ItemResponse.builder()
                .id(i.getId())
                .productId(i.getProductId())
                .quantity(i.getQuantity())
                .unitPrice(i.getUnitPrice())
                .build()).toList())
            .build();
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(meterRegistry);
    }

    private Timer timer(String name, String... tags) {
        return Timer.builder(name).tags(tags).register(meterRegistry);
    }
}
