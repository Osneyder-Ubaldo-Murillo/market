package nexus.market.adapters.out.persistence.mysql;

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

import nexus.market.domain.models.Return;
import nexus.market.domain.ports.out.ReturnRepositoryPort;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.ReturnId;
import nexus.market.domain.valueobjects.ReturnItem;

/**
 * Entidad JPA de {@link Return} (tabla {@code returns}) con sus ítems
 * devueltos ({@code return_items}).
 */
@Entity
@Table(name = "returns")
class ReturnEntity {

    @Id
    @Column(name = "return_id", length = 36, updatable = false)
    String returnId;

    @Column(name = "order_id", length = 36, nullable = false, unique = true)
    String orderId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "return_items", joinColumns = @JoinColumn(name = "return_id"))
    @OrderColumn(name = "position")
    List<JpaValueTypes.ReturnItemValue> items = new ArrayList<>();

    @Column(name = "status", length = 30, nullable = false)
    String status;

    @Column(name = "request_date", nullable = false, updatable = false)
    LocalDateTime requestDate;

    @Column(name = "resolution_date")
    LocalDateTime resolutionDate;

    protected ReturnEntity() {
    }

    static ReturnEntity from(Return aReturn) {
        ReturnEntity entity = new ReturnEntity();
        entity.returnId = aReturn.getReturnId().value();
        entity.orderId = aReturn.getOrderId().value();
        entity.items = aReturn.getItems().stream()
                .map(JpaValueTypes.ReturnItemValue::from)
                .collect(Collectors.toCollection(ArrayList::new));
        entity.status = aReturn.getStatus().getCode();
        entity.requestDate = aReturn.getRequestDate();
        entity.resolutionDate = aReturn.getResolutionDate();
        return entity;
    }

    Return toDomain() {
        List<ReturnItem> domainItems = items.stream()
                .map(JpaValueTypes.ReturnItemValue::toDomain)
                .collect(Collectors.toList());
        return new Return(ReturnId.of(returnId), OrderId.of(orderId), domainItems,
                JpaCatalogCodecs.INSTANCE.returnStatus(status), requestDate, resolutionDate);
    }
}

/**
 * Adaptador de salida {@link ReturnRepositoryPort} sobre JPA (MySQL).
 */
@Component
@Profile("jpa")
class JpaReturnRepositoryPort extends JpaRepositorySupport implements ReturnRepositoryPort {

    JpaReturnRepositoryPort(jakarta.persistence.EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    public Return save(Return aReturn) {
        return persist(ReturnEntity.from(aReturn)).toDomain();
    }

    @Override
    public Optional<Return> findById(ReturnId returnId) {
        return find(ReturnEntity.class, returnId.value()).map(ReturnEntity::toDomain);
    }

    @Override
    public Optional<Return> findByOrderId(OrderId orderId) {
        return querySingle(ReturnEntity.class,
                "select r from ReturnEntity r where r.orderId = :orderId",
                Map.of("orderId", orderId.value()))
                .map(ReturnEntity::toDomain);
    }
}