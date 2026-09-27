package nexus.market.adapters.out.persistence.mysql;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import nexus.market.domain.models.Warehouse;
import nexus.market.domain.ports.out.WarehouseRepositoryPort;
import nexus.market.domain.valueobjects.SellerId;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * Entidad JPA de {@link Warehouse} (tabla {@code warehouses}).
 */
@Entity
@Table(name = "warehouses")
class WarehouseEntity {

    @Id
    @Column(name = "warehouse_id", length = 36, updatable = false)
    String warehouseId;

    @Column(name = "name", length = 120, nullable = false)
    String name;

    @Embedded
    JpaValueTypes.AddressValue address = new JpaValueTypes.AddressValue();

    @Column(name = "type", length = 30, nullable = false)
    String type;

    @Column(name = "seller_id", length = 36)
    String sellerId;

    @Column(name = "status", length = 30, nullable = false)
    String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    LocalDateTime updatedAt;

    protected WarehouseEntity() {
    }

    static WarehouseEntity from(Warehouse warehouse) {
        WarehouseEntity entity = new WarehouseEntity();
        entity.warehouseId = warehouse.getWarehouseId().value();
        entity.name = warehouse.getName();
        entity.address = JpaValueTypes.AddressValue.from(warehouse.getAddress());
        entity.type = warehouse.getType().getCode();
        entity.sellerId = warehouse.getSellerId() == null ? null : warehouse.getSellerId().value();
        entity.status = warehouse.getStatus().getCode();
        entity.createdAt = warehouse.getCreatedAt();
        entity.updatedAt = warehouse.getUpdatedAt();
        return entity;
    }

    Warehouse toDomain() {
        SellerId seller = sellerId == null ? null : SellerId.of(sellerId);
        return new Warehouse(WarehouseId.of(warehouseId), name, address.toDomain(),
                JpaCatalogCodecs.INSTANCE.warehouseType(type), seller,
                JpaCatalogCodecs.INSTANCE.warehouseStatus(status), createdAt, updatedAt);
    }
}

/**
 * Adaptador de salida {@link WarehouseRepositoryPort} sobre JPA (MySQL).
 */
@Component
@Profile("jpa")
class JpaWarehouseRepositoryPort extends JpaRepositorySupport implements WarehouseRepositoryPort {

    JpaWarehouseRepositoryPort(jakarta.persistence.EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    public Warehouse save(Warehouse warehouse) {
        return persist(WarehouseEntity.from(warehouse)).toDomain();
    }

    @Override
    public Optional<Warehouse> findById(WarehouseId warehouseId) {
        return find(WarehouseEntity.class, warehouseId.value()).map(WarehouseEntity::toDomain);
    }

    @Override
    public List<Warehouse> findBySellerId(SellerId sellerId) {
        return query(WarehouseEntity.class,
                "select w from WarehouseEntity w where w.sellerId = :sid",
                Map.of("sid", sellerId.value()))
                .stream().map(WarehouseEntity::toDomain).collect(Collectors.toList());
    }
}