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

import nexus.market.domain.models.Inventory;
import nexus.market.domain.ports.out.InventoryRepositoryPort;
import nexus.market.domain.valueobjects.InventoryId;
import nexus.market.domain.valueobjects.InventoryMovement;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.Quantity;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * Entidad JPA de {@link Inventory} (tabla {@code inventories}) con su historial
 * de movimientos ({@code inventory_movements}).
 */
@Entity
@Table(name = "inventories")
class InventoryEntity {

    @Id
    @Column(name = "inventory_id", length = 36, updatable = false)
    String inventoryId;

    @Column(name = "product_id", length = 36, nullable = false)
    String productId;

    @Column(name = "warehouse_id", length = 36, nullable = false)
    String warehouseId;

    @Column(name = "quantity", nullable = false)
    int quantity;

    @Column(name = "reserved_quantity", nullable = false)
    int reservedQuantity;

    @Column(name = "status", length = 30, nullable = false)
    String status;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "inventory_movements", joinColumns = @JoinColumn(name = "inventory_id"))
    @OrderColumn(name = "position")
    List<JpaValueTypes.InventoryMovementValue> movements = new ArrayList<>();

    @Column(name = "updated_at", nullable = false)
    LocalDateTime updatedAt;

    protected InventoryEntity() {
    }

    static InventoryEntity from(Inventory inventory) {
        InventoryEntity entity = new InventoryEntity();
        entity.inventoryId = inventory.getInventoryId().value();
        entity.productId = inventory.getProductId().value();
        entity.warehouseId = inventory.getWarehouseId().value();
        entity.quantity = inventory.getQuantity().value();
        entity.reservedQuantity = inventory.getReservedQuantity().value();
        entity.status = inventory.getStatus().getCode();
        entity.movements = inventory.getMovements().stream()
                .map(JpaValueTypes.InventoryMovementValue::from)
                .collect(Collectors.toCollection(ArrayList::new));
        entity.updatedAt = inventory.getUpdatedAt();
        return entity;
    }

    Inventory toDomain() {
        List<InventoryMovement> domainMovements = movements.stream()
                .map(JpaValueTypes.InventoryMovementValue::toDomain)
                .collect(Collectors.toList());
        return new Inventory(InventoryId.of(inventoryId), ProductId.of(productId),
                WarehouseId.of(warehouseId), Quantity.of(quantity),
                Quantity.of(reservedQuantity), JpaCatalogCodecs.INSTANCE.inventoryStatus(status),
                domainMovements, updatedAt);
    }
}

/**
 * Adaptador de salida {@link InventoryRepositoryPort} sobre JPA (MySQL).
 */
@Component
@Profile("jpa")
class JpaInventoryRepositoryPort extends JpaRepositorySupport implements InventoryRepositoryPort {

    JpaInventoryRepositoryPort(jakarta.persistence.EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    public Inventory save(Inventory inventory) {
        return persist(InventoryEntity.from(inventory)).toDomain();
    }

    @Override
    public Optional<Inventory> findById(InventoryId inventoryId) {
        return find(InventoryEntity.class, inventoryId.value()).map(InventoryEntity::toDomain);
    }

    @Override
    public List<Inventory> findByProductId(ProductId productId) {
        return query(InventoryEntity.class,
                "select i from InventoryEntity i where i.productId = :pid",
                Map.of("pid", productId.value()))
                .stream().map(InventoryEntity::toDomain).collect(Collectors.toList());
    }

    @Override
    public Optional<Inventory> findByProductIdAndWarehouseId(ProductId productId, WarehouseId warehouseId) {
        return querySingle(InventoryEntity.class,
                "select i from InventoryEntity i where i.productId = :pid and i.warehouseId = :wid",
                Map.of("pid", productId.value(), "wid", warehouseId.value()))
                .map(InventoryEntity::toDomain);
    }
}