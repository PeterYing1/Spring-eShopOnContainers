package com.eshop.basket;

import com.eshop.basket.BasketModels.CustomerBasket;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class BasketService {
    private final ConcurrentHashMap<String, CustomerBasket> baskets = new ConcurrentHashMap<>();

    public CustomerBasket get(String buyerId) {
        return Optional.ofNullable(baskets.get(buyerId)).orElseGet(() -> new CustomerBasket(buyerId, java.util.List.of()));
    }

    public Optional<CustomerBasket> findExisting(String buyerId) {
        return Optional.ofNullable(baskets.get(buyerId));
    }

    public CustomerBasket save(CustomerBasket basket) {
        baskets.put(basket.buyerId(), basket);
        return basket;
    }

    public void delete(String buyerId) {
        baskets.remove(buyerId);
    }
}
