package br.ufla.poc.controller;

import br.ufla.poc.domain.Payment;
import br.ufla.poc.repository.PaymentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaymentRepository paymentRepository;
    private final MeterRegistry meterRegistry;

    // Runtime fault injection flags (changed via /api/payments/fault endpoint)
    private volatile boolean faultEnabled = false;
    private volatile String faultType = "NONE";
    private volatile int delayMs = 0;

    @PostMapping
    public ResponseEntity<?> processPayment(@RequestBody Map<String, Object> request) {
        String orderId = (String) request.get("orderId");
        BigDecimal amount = new BigDecimal(request.get("amount").toString());

        log.info("Processing payment orderId={} amount={} fault={}", orderId, amount, faultType);

        // Fault injection: S1 - service unavailable (503)
        if (faultEnabled && "UNAVAILABLE".equals(faultType)) {
            log.error("FAULT_INJECTION: payment-service returning 503 for orderId={}", orderId);
            counter("payments.fault_injection", "type", "UNAVAILABLE").increment();
            return ResponseEntity.status(503).body(Map.of(
                "error", "Service temporarily unavailable",
                "fault", true
            ));
        }

        // Fault injection: S5 - authentication/authorization failure (401)
        if (faultEnabled && "AUTH_FAILURE".equals(faultType)) {
            log.error("FAULT_INJECTION: payment-service returning 401 Unauthorized for orderId={} — invalid inter-service token",
                orderId);
            counter("payments.fault_injection", "type", "AUTH_FAILURE").increment();
            return ResponseEntity.status(401).body(Map.of(
                "error", "Unauthorized — invalid or expired inter-service authentication token",
                "fault", true,
                "wwwAuthenticate", "Bearer realm=\"payment-service\""
            ));
        }

        // Fault injection: slow processing
        if (faultEnabled && "SLOW".equals(faultType) && delayMs > 0) {
            log.warn("FAULT_INJECTION: adding artificial delay={}ms for orderId={}", delayMs, orderId);
            try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        Payment payment = Payment.builder()
            .orderId(UUID.fromString(orderId))
            .amount(amount)
            .status(Payment.PaymentStatus.APPROVED)
            .processedAt(Instant.now())
            .build();

        paymentRepository.save(payment);
        counter("payments.processed", "status", "approved").increment();
        log.info("Payment approved paymentId={} orderId={}", payment.getId(), orderId);

        return ResponseEntity.ok(Map.of(
            "paymentId", payment.getId(),
            "orderId", orderId,
            "status", "APPROVED",
            "amount", amount
        ));
    }

    /** Fault injection control endpoint - used by fault injection scripts */
    @PostMapping("/fault")
    public ResponseEntity<?> configureFault(@RequestBody Map<String, Object> config) {
        this.faultEnabled = Boolean.parseBoolean(config.getOrDefault("enabled", false).toString());
        this.faultType    = config.getOrDefault("type", "NONE").toString();
        this.delayMs      = Integer.parseInt(config.getOrDefault("delayMs", 0).toString());
        log.warn("Fault injection configured: enabled={} type={} delayMs={}", faultEnabled, faultType, delayMs);
        return ResponseEntity.ok(Map.of("faultEnabled", faultEnabled, "faultType", faultType, "delayMs", delayMs));
    }

    @GetMapping("/fault")
    public ResponseEntity<?> getFaultStatus() {
        return ResponseEntity.ok(Map.of("faultEnabled", faultEnabled, "faultType", faultType, "delayMs", delayMs));
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(meterRegistry);
    }
}
