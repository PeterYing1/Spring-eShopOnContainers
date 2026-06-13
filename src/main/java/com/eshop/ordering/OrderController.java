package com.eshop.ordering;

import com.eshop.basket.BasketModels.CustomerBasket;
import com.eshop.ordering.OrderModels.OrderCommand;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    public Object orders(@RequestHeader(value = "x-user-id", defaultValue = "demo-user") String userId) {
        return orders.summariesForUser(userId);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<?> detail(@PathVariable int orderId) {
        return orders.detail(orderId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/cardtypes")
    public Object cardTypes() {
        return orders.cardTypes();
    }

    @PostMapping("/draft")
    public Object draft(@Valid @RequestBody CustomerBasket basket) {
        return orders.draft(basket.items());
    }

    @PutMapping("/cancel")
    public ResponseEntity<?> cancel(@RequestHeader(value = "x-requestid", required = false) String requestId,
                                    @Valid @RequestBody OrderCommand command) {
        if (requestId == null || requestId.isBlank() || command.orderNumber() == null || command.orderNumber() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "No orderId found"));
        }
        return orders.cancel(command.orderNumber()) ? ResponseEntity.ok().build() : ResponseEntity.badRequest().build();
    }

    @PutMapping("/ship")
    public ResponseEntity<?> ship(@RequestHeader(value = "x-requestid", required = false) String requestId,
                                  @Valid @RequestBody OrderCommand command) {
        if (requestId == null || requestId.isBlank() || command.orderNumber() == null || command.orderNumber() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "No orderId found"));
        }
        return orders.ship(command.orderNumber()) ? ResponseEntity.ok().build() : ResponseEntity.badRequest().build();
    }

    @PutMapping("/{orderId}/pay")
    public ResponseEntity<?> pay(@PathVariable int orderId) {
        return orders.markPaid(orderId) ? ResponseEntity.ok().build() : ResponseEntity.badRequest().build();
    }
}
