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
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import nexus.market.domain.models.Invoice;
import nexus.market.domain.ports.out.InvoiceRepositoryPort;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.InvoiceId;
import nexus.market.domain.valueobjects.Money;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.OrderItem;
import nexus.market.domain.valueobjects.SellerId;

/**
 * Entidad JPA de {@link Invoice} (tabla {@code invoices}) con sus ítems
 * ({@code invoice_items}).
 */
@Entity
@Table(name = "invoices")
class InvoiceEntity {

    @Id
    @Column(name = "invoice_id", length = 36, updatable = false)
    String invoiceId;

    @Column(name = "order_id", length = 36, nullable = false, unique = true)
    String orderId;

    @Column(name = "buyer_id", length = 36, nullable = false)
    String buyerId;

    @Column(name = "seller_id", length = 36, nullable = false)
    String sellerId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "invoice_items", joinColumns = @JoinColumn(name = "invoice_id"))
    @OrderColumn(name = "position")
    List<JpaValueTypes.ItemValue> items = new ArrayList<>();

    @Column(name = "total_amount", precision = 15, scale = 2, nullable = false)
    BigDecimal totalAmount;

    @Column(name = "total_currency", length = 3, nullable = false)
    String totalCurrency;

    @Column(name = "issue_date", nullable = false, updatable = false)
    LocalDateTime issueDate;

    @Column(name = "status", length = 30, nullable = false)
    String status;

    protected InvoiceEntity() {
    }

    static InvoiceEntity from(Invoice invoice) {
        InvoiceEntity entity = new InvoiceEntity();
        entity.invoiceId = invoice.getInvoiceId().value();
        entity.orderId = invoice.getOrderId().value();
        entity.buyerId = invoice.getBuyerId().value();
        entity.sellerId = invoice.getSellerId().value();
        entity.items = invoice.getItems().stream()
                .map(JpaValueTypes.ItemValue::fromOrderItem)
                .collect(Collectors.toCollection(ArrayList::new));
        entity.totalAmount = invoice.getTotal().getAmount();
        entity.totalCurrency = invoice.getTotal().getCurrency().getCurrencyCode();
        entity.issueDate = invoice.getIssueDate();
        entity.status = invoice.getStatus().getCode();
        return entity;
    }

    Invoice toDomain() {
        List<OrderItem> domainItems = items.stream()
                .map(JpaValueTypes.ItemValue::toOrderItem)
                .collect(Collectors.toList());
        return new Invoice(InvoiceId.of(invoiceId), OrderId.of(orderId),
                BuyerId.of(buyerId), SellerId.of(sellerId), domainItems,
                Money.of(totalAmount, totalCurrency), issueDate,
                JpaCatalogCodecs.INSTANCE.invoiceStatus(status));
    }
}

/**
 * Adaptador de salida {@link InvoiceRepositoryPort} sobre JPA (MySQL).
 */
@Component
@Profile("jpa")
class JpaInvoiceRepositoryPort extends JpaRepositorySupport implements InvoiceRepositoryPort {

    JpaInvoiceRepositoryPort(jakarta.persistence.EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    public Invoice save(Invoice invoice) {
        return persist(InvoiceEntity.from(invoice)).toDomain();
    }

    @Override
    public Optional<Invoice> findById(InvoiceId invoiceId) {
        return find(InvoiceEntity.class, invoiceId.value()).map(InvoiceEntity::toDomain);
    }

    @Override
    public Optional<Invoice> findByOrderId(OrderId orderId) {
        return querySingle(InvoiceEntity.class,
                "select i from InvoiceEntity i where i.orderId = :orderId",
                Map.of("orderId", orderId.value()))
                .map(InvoiceEntity::toDomain);
    }
}