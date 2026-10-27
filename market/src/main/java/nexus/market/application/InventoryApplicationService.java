package nexus.market.application;

import java.util.Map;
import java.util.Objects;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import nexus.market.domain.ports.in.InventoryUseCases;
import nexus.market.domain.models.Inventory;
import nexus.market.domain.services.InventoryService;
import nexus.market.domain.valueobjects.InventoryId;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.Quantity;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * Servicio de aplicación de inventario: delega en {@code InventoryService}.
 * Los movimientos vistos desde la API no llevan metadatos de referencia
 * (los aporta internamente el flujo de pedidos).
 */
@Service
@Profile("jpa")
public class InventoryApplicationService implements InventoryUseCases {

    private static final Map<String, Object> NO_METADATA = Map.of();

    private final InventoryService delegate;

    public InventoryApplicationService(InventoryService delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate es obligatorio");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Inventory createInventory(ProductId productId, WarehouseId warehouseId) {
        return delegate.createInventory(productId, warehouseId);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void addStock(InventoryId inventoryId, Quantity quantity) {
        delegate.addStock(inventoryId, quantity, NO_METADATA);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void reserve(InventoryId inventoryId, Quantity quantity) {
        delegate.reserve(inventoryId, quantity, NO_METADATA);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void release(InventoryId inventoryId, Quantity quantity) {
        delegate.release(inventoryId, quantity, NO_METADATA);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void adjust(InventoryId inventoryId, Quantity newQuantity) {
        delegate.adjust(inventoryId, newQuantity, NO_METADATA);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void markDamaged(InventoryId inventoryId) {
        delegate.markDamaged(inventoryId);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void markActive(InventoryId inventoryId) {
        delegate.markActive(inventoryId);
    }

    @Override
    public Inventory findById(InventoryId inventoryId) {
        return delegate.findById(inventoryId);
    }
}
