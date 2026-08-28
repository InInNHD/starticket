package com.starticket.order;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

record CreateOrderRequest(@NotNull Long performanceId, @NotEmpty @Size(max = 6) List<@NotNull Long> seatIds) {
}

record OrderItemView(Long id, Long seatId, String seatCode, String tierName, BigDecimal price) {
}

record OrderView(
        String orderNo,
        Long performanceId,
        BigDecimal totalAmount,
        String status,
        Instant expiresAt,
        Instant paidAt,
        Instant createdAt,
        List<OrderItemView> items
) {
}
