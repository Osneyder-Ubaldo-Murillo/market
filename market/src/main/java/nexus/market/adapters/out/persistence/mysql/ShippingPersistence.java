package nexus.market.adapters.out.persistence.mysql;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import nexus.market.domain.models.Shipping;
import nexus.market.domain.ports.out.ShippingRepositoryPort;
import nexus.market.domain.valueobjects.DeliveryInfo;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.ShippingId;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * Entidad JPA de {@link Shipping} (tabla {@code shippings}). La información de
 * seguimiento ({@link DeliveryInfo}) es opcional hasta el despacho.
 */
@Entity
@Table(name = "shippings")
class ShippingEntity {

    @Id
    @Column(name = "shipping_id", length = 36, updatable = false)
    String shippingId;

    @Column(name = "order_id", length = 36, nullable = false, unique = true)
    String orderId;

    @Column(name = "warehouse_id", length = 36, nullable = false)
    String warehouseId;

    @Embedded
    JpaValueTypes.DeliveryInfoValue deliveryInfo = new JpaValueTypes.DeliveryInfoValue();

    @Column(name = "status", length = 30, nullable = false)
    String status;

    protected ShippingEntity() {
    }

    static ShippingEntity from(Shipping shipping) {
        ShippingEntity entity = new ShippingEntity();
        entity.shippingId = shipping.getShippingId().value();
        entity.orderId = shipping.getOrderId().value();
        entity.warehouseId = shipping.getWarehouseId().value();
        entity.deliveryInfo = shipping.getDeliveryInfo() == null
                ? new JpaValueTypes.DeliveryInfoValue()
                : JpaValueTypes.DeliveryInfoValue.from(shipping.getDeliveryInfo());
        entity.status = shipping.getStatus().getCode();
        return entity;
    }

    Shipping toDomain() {
        DeliveryInfo info = deliveryInfo == null ? null : deliveryInfo.toDomain();
        return new Shipping(ShippingId.of(shippingId), OrderId.of(orderId),
                WarehouseId.of(warehouseId), info,
                JpaCatalogCodecs.INSTANCE.shippingStatus(status));
    }
}

/**
 * Adaptador de salida {@link ShippingRepositoryPort} sobre JPA (MySQL).
 */
@Component
@Profile("jpa")
class JpaShippingRepositoryPort extends JpaRepositorySupport implements ShippingRepositoryPort {

    JpaShippingRepositoryPort(jakarta.persistence.EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    public Shipping save(Shipping shipping) {
        return persist(ShippingEntity.from(shipping)).toDomain();
    }

    @Override
    public Optional<Shipping> findById(ShippingId shippingId) {
        return find(ShippingEntity.class, shippingId.value()).map(ShippingEntity::toDomain);
    }

    @Override
    public Optional<Shipping> findByOrderId(OrderId orderId) {
        return querySingle(ShippingEntity.class,
                "select s from ShippingEntity s where s.orderId = :orderId",
                Map.of("orderId", orderId.value()))
                .map(ShippingEntity::toDomain);
    }
}