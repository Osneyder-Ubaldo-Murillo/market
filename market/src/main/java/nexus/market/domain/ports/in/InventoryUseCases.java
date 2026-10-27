package nexus.market.domain.ports.in;

import nexus.market.domain.models.Inventory;
import nexus.market.domain.valueobjects.InventoryId;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.Quantity;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * Puerto de entrada (caso de uso) de inventario. Delega en
 * {@code InventoryService}. Las operaciones masivas por producto
 * (reserva/liberación/venta) quedan internas al flujo de pedidos.
 */
public interface InventoryUseCases {

    Inventory createInventory(ProductId productId, WarehouseId warehouseId);

    void addStock(InventoryId inventoryId, Quantity quantity);

    void reserve(InventoryId inventoryId, Quantity quantity);

    void release(InventoryId inventoryId, Quantity quantity);

    void adjust(InventoryId inventoryId, Quantity newQuantity);

    void markDamaged(InventoryId inventoryId);

    void markActive(InventoryId inventoryId);

    Inventory findById(InventoryId inventoryId);
}
