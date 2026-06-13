package com.eshop.ordering;

import com.eshop.basket.BasketModels.BasketCheckout;
import com.eshop.basket.BasketModels.BasketItem;
import com.eshop.basket.BasketModels.CustomerBasket;
import com.eshop.catalog.CatalogRepository;
import com.eshop.ordering.OrderModels.CardType;
import com.eshop.ordering.OrderModels.DraftOrderItem;
import com.eshop.ordering.OrderModels.DraftOrderResponse;
import com.eshop.ordering.OrderModels.OrderDetail;
import com.eshop.ordering.OrderModels.OrderDetailItem;
import com.eshop.ordering.OrderModels.OrderSummary;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
    private static final int SUBMITTED = 1;
    private static final int PAID = 4;
    private static final int SHIPPED = 5;
    private static final int CANCELLED = 6;

    private final JdbcTemplate jdbc;
    private final CatalogRepository catalog;

    public OrderService(JdbcTemplate jdbc, CatalogRepository catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    public List<CardType> cardTypes() {
        return jdbc.query("SELECT Id, Name FROM ordering.cardtypes ORDER BY Id", (rs, row) -> new CardType(rs.getInt("Id"), rs.getString("Name")));
    }

    public DraftOrderResponse draft(List<BasketItem> items) {
        List<DraftOrderItem> orderItems = items.stream()
                .map(item -> new DraftOrderItem(item.productId(), item.productName(), item.unitPrice(), BigDecimal.ZERO, item.quantity(), item.pictureUrl()))
                .toList();
        return new DraftOrderResponse(orderItems, total(items));
    }

    @Transactional
    public int createFromCheckout(String userId, String requestId, BasketCheckout checkout, CustomerBasket basket) {
        int buyerId = findOrCreateBuyer(userId);
        int paymentMethodId = createPaymentMethod(buyerId, checkout);
        int orderId = createOrder(buyerId, paymentMethodId, checkout);
        for (BasketItem item : basket.items()) {
            jdbc.update("""
                    INSERT INTO ordering.orderItems (Discount, OrderId, PictureUrl, ProductId, ProductName, UnitPrice, Units)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, BigDecimal.ZERO, orderId, item.pictureUrl(), item.productId(), item.productName(), item.unitPrice(), item.quantity());
        }
        return orderId;
    }

    public List<OrderSummary> summariesForUser(String userId) {
        return jdbc.query("""
                SELECT o.Id, o.OrderDate, s.Name AS Status, COALESCE(SUM(i.UnitPrice * i.Units), 0) AS Total
                FROM ordering.orders o
                JOIN ordering.buyers b ON b.Id = o.BuyerId
                JOIN ordering.orderstatus s ON s.Id = o.OrderStatusId
                LEFT JOIN ordering.orderItems i ON i.OrderId = o.Id
                WHERE b.IdentityGuid = ?
                GROUP BY o.Id, o.OrderDate, s.Name
                ORDER BY o.OrderDate DESC
                """, (rs, row) -> new OrderSummary(
                rs.getInt("Id"),
                rs.getTimestamp("OrderDate").toInstant().atOffset(ZoneOffset.UTC),
                rs.getString("Status"),
                rs.getBigDecimal("Total")), userId);
    }

    public Optional<OrderDetail> detail(int orderId) {
        List<OrderDetail> orders = jdbc.query("""
                SELECT o.*, s.Name AS Status, COALESCE(SUM(i.UnitPrice * i.Units), 0) AS Total
                FROM ordering.orders o
                JOIN ordering.orderstatus s ON s.Id = o.OrderStatusId
                LEFT JOIN ordering.orderItems i ON i.OrderId = o.Id
                WHERE o.Id = ?
                GROUP BY o.Id, s.Name
                """, (rs, row) -> {
            List<OrderDetailItem> items = jdbc.query("""
                    SELECT ProductName, Units, UnitPrice, PictureUrl
                    FROM ordering.orderItems
                    WHERE OrderId = ?
                    ORDER BY Id
                    """, (itemRs, itemRow) -> new OrderDetailItem(
                    itemRs.getString("ProductName"),
                    itemRs.getInt("Units"),
                    itemRs.getBigDecimal("UnitPrice"),
                    itemRs.getString("PictureUrl")), orderId);
            return new OrderDetail(
                    rs.getInt("Id"),
                    rs.getTimestamp("OrderDate").toInstant().atOffset(ZoneOffset.UTC),
                    rs.getString("Status"),
                    rs.getString("Description"),
                    rs.getString("Street"),
                    rs.getString("City"),
                    rs.getString("ZipCode"),
                    rs.getString("Country"),
                    items,
                    rs.getBigDecimal("Total"));
        }, orderId);
        return orders.stream().findFirst();
    }

    @Transactional
    public boolean cancel(int orderId) {
        Integer status = currentStatus(orderId);
        if (status == null || status == PAID || status == SHIPPED || status == CANCELLED) {
            return false;
        }
        return jdbc.update("UPDATE ordering.orders SET OrderStatusId = ? WHERE Id = ?", CANCELLED, orderId) == 1;
    }

    @Transactional
    public boolean ship(int orderId) {
        Integer status = currentStatus(orderId);
        if (status == null || status != PAID) {
            return false;
        }
        return jdbc.update("UPDATE ordering.orders SET OrderStatusId = ? WHERE Id = ?", SHIPPED, orderId) == 1;
    }

    @Transactional
    public boolean markPaid(int orderId) {
        Integer status = currentStatus(orderId);
        if (status == null || status == CANCELLED || status == SHIPPED) {
            return false;
        }
        List<BasketItem> items = jdbc.query("""
                SELECT ProductId, ProductName, UnitPrice, Units, PictureUrl
                FROM ordering.orderItems WHERE OrderId = ?
                """, (rs, row) -> new BasketItem(null, rs.getInt("ProductId"), rs.getString("ProductName"),
                rs.getBigDecimal("UnitPrice"), BigDecimal.ZERO, rs.getInt("Units"), rs.getString("PictureUrl")), orderId);
        jdbc.update("UPDATE ordering.orders SET OrderStatusId = ? WHERE Id = ?", PAID, orderId);
        items.forEach(item -> catalog.decrementStock(item.productId(), item.quantity()));
        return true;
    }

    private int findOrCreateBuyer(String userId) {
        List<Integer> existing = jdbc.query("SELECT Id FROM ordering.buyers WHERE IdentityGuid = ?", (rs, row) -> rs.getInt("Id"), userId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ordering.buyers (IdentityGuid, Name) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, userId);
            statement.setString(2, userId);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().intValue();
    }

    private int createPaymentMethod(int buyerId, BasketCheckout checkout) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO ordering.paymentmethods (Alias, BuyerId, CardHolderName, CardNumber, CardTypeId, Expiration)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "Payment method for " + checkout.cardHolderName());
            statement.setInt(2, buyerId);
            statement.setString(3, checkout.cardHolderName());
            statement.setString(4, checkout.cardNumber());
            statement.setInt(5, checkout.cardTypeId());
            statement.setTimestamp(6, Timestamp.from(checkout.cardExpiration().toInstant()));
            return statement;
        }, keyHolder);
        return keyHolder.getKey().intValue();
    }

    private int createOrder(int buyerId, int paymentMethodId, BasketCheckout checkout) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO ordering.orders (BuyerId, Description, OrderDate, OrderStatusId, PaymentMethodId, Street, City, State, Country, ZipCode)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setInt(1, buyerId);
            statement.setString(2, "Submitted order");
            statement.setTimestamp(3, Timestamp.from(OffsetDateTime.now(ZoneOffset.UTC).toInstant()));
            statement.setInt(4, SUBMITTED);
            statement.setInt(5, paymentMethodId);
            statement.setString(6, checkout.street());
            statement.setString(7, checkout.city());
            statement.setString(8, checkout.state());
            statement.setString(9, checkout.country());
            statement.setString(10, checkout.zipCode());
            return statement;
        }, keyHolder);
        return keyHolder.getKey().intValue();
    }

    private Integer currentStatus(int orderId) {
        List<Integer> statuses = jdbc.query("SELECT OrderStatusId FROM ordering.orders WHERE Id = ?", (rs, row) -> rs.getInt("OrderStatusId"), orderId);
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    private BigDecimal total(List<BasketItem> items) {
        return items.stream()
                .map(item -> item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
