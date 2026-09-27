package nexus.market.adapters.out.persistence.mysql;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import nexus.market.domain.models.Order;
import nexus.market.domain.ports.out.OrderRepositoryPort;
import nexus.market.domain.valueobjects.Address;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.InvoiceId;
import nexus.market.domain.valueobjects.Money;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.OrderItem;

/**
 * Entidad JPA de {@link Order} (tabla {@code orders}) con sus ítems congelados
 * ({@code order_items}) y la dirección de entrega embebida.
 */
@Entity
@Table(name = "orders")
class OrderEntity {

    @Id
    @Column(name = "order_id", length = 36, updatable = false)
    String orderId;

    @Column(name = "buyer_id", length = 36, nullable = false)
    String buyerId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "order_items", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "position")
    List<JpaValueTypes.ItemValue> items = new ArrayList<>();

    @Column(name = "status", length = 30, nullable = false)
    String status;

    @Column(name = "total_amount", precision = 15, scale = 2, nullable = false)
    BigDecimal totalAmount;

    @Column(name = "total_currency", length = 3, nullable = false)
    String totalCurrency;

    @Embedded
    JpaValueTypes.AddressValue shippingAddress = new JpaValueTypes.AddressValue();

    @Column(name = "invoice_id", length = 36)
    String invoiceId;

    @Column(name = "delivered_at")
    LocalDateTime deliveredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    LocalDateTime updatedAt;

    protected OrderEntity() {
    }

    static OrderEntity from(Order order) {
        OrderEntity entity = new OrderEntity();
        entity.orderId = order.getOrderId().value();
        entity.buyerId = order.getBuyerId().value();
        entity.items = order.getItems().stream()
                .map(JpaValueTypes.ItemValue::fromOrderItem)
                .collect(Collectors.toCollection(ArrayList::new));
        entity.status = order.getStatus().getCode();
        entity.totalAmount = order.getTotal().getAmount();
        entity.totalCurrency = order.getTotal().getCurrency().getCurrencyCode();
        entity.shippingAddress = JpaValueTypes.AddressValue.from(order.getShippingAddress());
        entity.invoiceId = order.getInvoiceId() == null ? null : order.getInvoiceId().value();
        entity.deliveredAt = order.getDeliveredAt();
        entity.createdAt = order.getCreatedAt();
        entity.updatedAt = order.getUpdatedAt();
        return entity;
    }

    Order toDomain() {
        List<OrderItem> domainItems = items.stream()
                .map(JpaValueTypes.ItemValue::toOrderItem)
                .collect(Collectors.toList());
        Address address = shippingAddress.toDomain();
        InvoiceId invoice = invoiceId == null ? null : InvoiceId.of(invoiceId);
        return new Order(OrderId.of(orderId), BuyerId.of(buyerId), domainItems,
                JpaCatalogCodecs.INSTANCE.orderStatus(status), address, invoice,
                deliveredAt, createdAt, updatedAt);
    }
}

/**
 * Adaptador de salida {@link OrderRepositoryPort} sobre JPA (MySQL).
 */
@Component
@Profile("jpa")
class JpaOrderRepositoryPort extends JpaRepositorySupport implements OrderRepositoryPort {

    JpaOrderRepositoryPort(jakarta.persistence.EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    public Order save(Order order) {
        return persist(OrderEntity.from(order)).toDomain();
    }

    @Override
    public Optional<Order> findById(OrderId orderId) {
        return find(OrderEntity.class, orderId.value()).map(OrderEntity::toDomain);
    }

    @Override
    public List<Order> findByBuyerId(BuyerId buyerId) {
        return query(OrderEntity.class,
                "select o from OrderEntity o where o.buyerId = :buyerId",
                Map.of("buyerId", buyerId.value()))
                .stream().map(OrderEntity::toDomain).collect(Collectors.toList());
    }
}