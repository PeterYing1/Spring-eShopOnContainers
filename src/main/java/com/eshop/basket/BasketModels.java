package com.eshop.basket;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public final class BasketModels {
    private BasketModels() {
    }

    public record CustomerBasket(@NotBlank String buyerId, @Valid List<BasketItem> items) {
        public CustomerBasket {
            items = items == null ? new ArrayList<>() : items;
        }
    }

    public record BasketItem(
            String id,
            int productId,
            @NotBlank String productName,
            @NotNull BigDecimal unitPrice,
            BigDecimal oldUnitPrice,
            @Min(1) int quantity,
            String pictureUrl
    ) {
    }

    public record BasketCheckout(
            @NotBlank String city,
            @NotBlank String street,
            @NotBlank String state,
            @NotBlank String country,
            @NotBlank String zipCode,
            @NotBlank String cardNumber,
            @NotBlank String cardHolderName,
            @NotNull OffsetDateTime cardExpiration,
            String cardSecurityNumber,
            int cardTypeId,
            String buyer,
            String requestId
    ) {
    }
}
