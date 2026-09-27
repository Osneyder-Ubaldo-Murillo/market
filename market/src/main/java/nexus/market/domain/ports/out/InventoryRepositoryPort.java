package nexus.market.domain.ports.out;

import java.util.List;
import java.util.Optional;

import nexus.market.domain.models.Inventory;
import nexus.market.domain.valueobjects.InventoryId;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * Puerto de salida para la persistencia de {@link Inventory}.
 * Implementado por {@code adapters.out.persistence.mysql} (JPA).
 */
public interface InventoryRepositoryPort {

    Inventory save(Inventory inventory);

    Optional<Inventory> findById(InventoryId inventoryId);

    List<Inventory> findByProductId(ProductId productId);

    Optional<Inventory> findByProductIdAndWarehouseId(ProductId productId, WarehouseId warehouseId);
}