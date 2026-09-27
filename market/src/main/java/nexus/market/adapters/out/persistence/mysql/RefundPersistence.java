package nexus.market.adapters.out.persistence.mysql;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import nexus.market.domain.models.Refund;
import nexus.market.domain.ports.out.RefundRepositoryPort;
import nexus.market.domain.valueobjects.Money;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.RefundId;
import nexus.market.domain.valueobjects.ReturnId;

/**
 * Entidad JPA de {@link Refund} (tabla {@code refunds}). {@code return_id} es
 * opcional (reembolso directo o originado por devolución).
 */
@Entity
@Table(name = "refunds")
class RefundEntity {

    @Id
    @Column(name = "refund_id", length = 36, updatable = false)
    String refundId;

    @Column(name = "return_id", length = 36)
    String returnId;

    @Column(name = "order_id", length = 36, nullable = false)
    String orderId;

    @Column(name = "amount", precision = 15, scale = 2, nullable = false)
    BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    String currency;

    @Column(name = "status", length = 30, nullable = false)
    String status;

    @Column(name = "request_date", nullable = false, updatable = false)
    LocalDateTime requestDate;

    @Column(name = "processed_date")
    LocalDateTime processedDate;

    protected RefundEntity() {
    }

    static RefundEntity from(Refund refund) {
        RefundEntity entity = new RefundEntity();
        entity.refundId = refund.getRefundId().value();
        entity.returnId = refund.getReturnId() == null ? null : refund.getReturnId().value();
        entity.orderId = refund.getOrderId().value();
        entity.amount = refund.getAmount().getAmount();
        entity.currency = refund.getAmount().getCurrency().getCurrencyCode();
        entity.status = refund.getStatus().getCode();
        entity.requestDate = refund.getRequestDate();
        entity.processedDate = refund.getProcessedDate();
        return entity;
    }

    Refund toDomain() {
        ReturnId returnRef = returnId == null ? null : ReturnId.of(returnId);
        return new Refund(RefundId.of(refundId), returnRef, OrderId.of(orderId),
                Money.of(amount, currency), JpaCatalogCodecs.INSTANCE.refundStatus(status),
                requestDate, processedDate);
    }
}

/**
 * Adaptador de salida {@link RefundRepositoryPort} sobre JPA (MySQL).
 */
@Component
@Profile("jpa")
class JpaRefundRepositoryPort extends JpaRepositorySupport implements RefundRepositoryPort {

    JpaRefundRepositoryPort(jakarta.persistence.EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    public Refund save(Refund refund) {
        return persist(RefundEntity.from(refund)).toDomain();
    }

    @Override
    public Optional<Refund> findById(RefundId refundId) {
        return find(RefundEntity.class, refundId.value()).map(RefundEntity::toDomain);
    }

    @Override
    public List<Refund> findByOrderId(OrderId orderId) {
        return query(RefundEntity.class,
                "select r from RefundEntity r where r.orderId = :orderId",
                Map.of("orderId", orderId.value()))
                .stream().map(RefundEntity::toDomain).collect(Collectors.toList());
    }
}