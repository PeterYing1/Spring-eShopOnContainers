package com.eshop.ordering;

import com.eshop.basket.BasketModels.BasketItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public final class OrderModels {
    private OrderModels() {
    }

    public record CardType(int id, String name) {
    }

    public record DraftOrder(@Valid List<BasketItem> items) {
    }

    public record DraftOrderItem(int productId, String productName, BigDecimal unitPrice, BigDecimal discount, int units, String pictureUrl) {
    }

    public record DraftOrderResponse(List<DraftOrderItem> orderItems, BigDecimal total) {
    }

    public record OrderCommand(@NotNull Integer orderNumber) {
    }

    public record OrderSummary(int ordernumber, OffsetDateTime date, String status, BigDecimal total) {
    }

    public record OrderDetail(
            int ordernumber,
            OffsetDateTime date,
            String status,
            String description,
            String street,
            String city,
            String zipcode,
            String country,
            List<OrderDetailItem> orderitems,
            BigDecimal total
    ) {
    }

    public record OrderDetailItem(String productname, int units, BigDecimal unitprice, String pictureurl) {
    }
}
