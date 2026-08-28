package com.starticket.order;

import com.starticket.common.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;


@RestController
@RequestMapping("/api/orders")
@Validated
class OrderController {

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    OrderView create(Authentication authentication,
                     @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                     @Valid @RequestBody CreateOrderRequest request) {
        return orders.create(authentication.getName(), idempotencyKey, request);
    }

    @GetMapping
    PageResult<OrderView> list(Authentication authentication,
                               @RequestParam(required = false) String status,
                               @RequestParam(defaultValue = "0") @Min(0) int page,
                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return orders.list(authentication.getName(), status, page, size);
    }

    @GetMapping("/{orderNo}")
    OrderView get(@PathVariable String orderNo, Authentication authentication) {
        return orders.get(orderNo, authentication.getName());
    }

    @PostMapping("/{orderNo}/cancel")
    OrderView cancel(@PathVariable String orderNo, Authentication authentication) {
        return orders.cancel(orderNo, authentication.getName());
    }
}
