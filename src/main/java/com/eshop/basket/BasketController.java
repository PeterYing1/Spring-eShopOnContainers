package com.eshop.basket;

import com.eshop.basket.BasketModels.BasketCheckout;
import com.eshop.basket.BasketModels.CustomerBasket;
import com.eshop.ordering.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/basket")
public class BasketController {
    private final BasketService baskets;
    private final OrderService orders;

    public BasketController(BasketService baskets, OrderService orders) {
        this.baskets = baskets;
        this.orders = orders;
    }

    @GetMapping("/{id}")
    public CustomerBasket get(@PathVariable String id) {
        return baskets.get(id);
    }

    @PostMapping
    public CustomerBasket save(@Valid @RequestBody CustomerBasket basket) {
        return baskets.save(basket);
    }

    @PostMapping("/checkout")
    public ResponseEntity<?> checkout(@RequestHeader(value = "x-user-id", defaultValue = "demo-user") String userId,
                                      @RequestHeader(value = "x-requestid", required = false) String requestId,
                                      @Valid @RequestBody BasketCheckout checkout) {
        return baskets.findExisting(userId)
                .map(basket -> {
                    orders.createFromCheckout(userId, requestId == null || requestId.isBlank() ? checkout.requestId() : requestId, checkout, basket);
                    baskets.delete(userId);
                    return ResponseEntity.accepted().build();
                })
                .orElseGet(() -> ResponseEntity.badRequest().body(java.util.Map.of("messages", java.util.List.of("Basket does not exist."))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        baskets.delete(id);
        return ResponseEntity.ok().build();
    }
}
