package br.ufla.poc.controller;

import br.ufla.poc.domain.Product;
import br.ufla.poc.repository.ProductRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
@Slf4j
public class InventoryController {

    private final ProductRepository productRepository;
    private final MeterRegistry meterRegistry;

    // Runtime fault injection
    private volatile int artificialDelayMs = 0;
    private volatile boolean faultEnabled = false;

    @PostMapping("/reserve")
    public ResponseEntity<?> reserve(@RequestBody Map<String, Object> request) {
        String orderId = (String) request.get("orderId");
        log.info("Reserve request orderId={} delayMs={}", orderId, artificialDelayMs);

        Timer.Sample sample = Timer.start(meterRegistry);

        // S2: artificial latency injection
        if (faultEnabled && artificialDelayMs > 0) {
            log.warn("FAULT_INJECTION: adding artificial delay={}ms orderId={}", artificialDelayMs, orderId);
            try { Thread.sleep(artificialDelayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) request.get("items");

        for (var item : items) {
            String productId = (String) item.get("productId");
            int qty = Integer.parseInt(item.get("quantity").toString());

            Product p = productRepository.findById(productId).orElse(null);
            if (p == null || p.getStockQuantity() < qty) {
                log.error("Insufficient stock productId={} requested={} available={}",
                    productId, qty, p != null ? p.getStockQuantity() : 0);
                counter("inventory.reservation.failed", "reason", "insufficient_stock").increment();
                sample.stop(timer("inventory.reserve.duration", "result", "failed"));
                return ResponseEntity.status(422).body(Map.of("error", "Insufficient stock", "productId", productId));
            }

            p.setStockQuantity(p.getStockQuantity() - qty);
            p.setReservedQuantity(p.getReservedQuantity() + qty);
            productRepository.save(p);
        }

        counter("inventory.reservation.success").increment();
        sample.stop(timer("inventory.reserve.duration", "result", "success"));
        log.info("Inventory reserved for orderId={}", orderId);
        return ResponseEntity.ok(Map.of("orderId", orderId, "status", "RESERVED"));
    }

    @GetMapping("/products")
    public ResponseEntity<?> listProducts() {
        return ResponseEntity.ok(productRepository.findAll());
    }

    /** Fault injection control - used by scripts for scenario S2 */
    @PostMapping("/fault")
    public ResponseEntity<?> configureFault(@RequestBody Map<String, Object> config) {
        this.faultEnabled     = Boolean.parseBoolean(config.getOrDefault("enabled", false).toString());
        this.artificialDelayMs = Integer.parseInt(config.getOrDefault("delayMs", 0).toString());
        log.warn("Fault injection configured: enabled={} delayMs={}", faultEnabled, artificialDelayMs);
        return ResponseEntity.ok(Map.of("faultEnabled", faultEnabled, "delayMs", artificialDelayMs));
    }

    @GetMapping("/fault")
    public ResponseEntity<?> getFaultStatus() {
        return ResponseEntity.ok(Map.of("faultEnabled", faultEnabled, "delayMs", artificialDelayMs));
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(meterRegistry);
    }

    private Timer timer(String name, String... tags) {
        return Timer.builder(name).tags(tags).register(meterRegistry);
    }
}
