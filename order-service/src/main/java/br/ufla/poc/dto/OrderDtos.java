package br.ufla.poc.dto;

import br.ufla.poc.domain.Order;
import lombok.Builder;
import lombok.Data;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class OrderDtos {

    @Data
    public static class CreateOrderRequest {
        @NotBlank
        private String customerId;
        @NotEmpty
        private List<ItemRequest> items;

        @Data
        public static class ItemRequest {
            @NotBlank private String productId;
            @Min(1)   private Integer quantity;
            @DecimalMin("0.01") private BigDecimal unitPrice;
        }
    }

    @Data @Builder
    public static class OrderResponse {
        private UUID id;
        private String customerId;
        private Order.OrderStatus status;
        private BigDecimal totalAmount;
        private List<ItemResponse> items;
        private Instant createdAt;
        private Instant updatedAt;

        @Data @Builder
        public static class ItemResponse {
            private UUID id;
            private String productId;
            private Integer quantity;
            private BigDecimal unitPrice;
        }
    }

    @Data @Builder
    public static class OrderSummary {
        private long total;
        private long pending;
        private long paid;
        private long failed;
    }
}
