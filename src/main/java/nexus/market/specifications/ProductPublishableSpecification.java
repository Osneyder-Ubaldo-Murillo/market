package com.nexusmarket.domain.specifications;

import java.util.List;
import java.util.Objects;

import com.nexusmarket.domain.models.Inventory;
import com.nexusmarket.domain.models.Product;
import com.nexusmarket.domain.valueobjects.InventoryStatus;
import com.nexusmarket.domain.valueobjects.ProductId;
import com.nexusmarket.domain.valueobjects.ProductType;
import com.nexusmarket.domain.valueobjects.Quantity;

/**
 * Verifica que un producto pueda publicarse: tener al menos una unidad
 * disponible en alguna bodega, o ser digital (los digitales se entregan sin
 * inventario ni despacho).
 *
 * <p>Define una interfaz funcional anidada {@link InventoryRepository} para
 * mantener el paquete {@code domain} libre de frameworks.</p>
 */
public class ProductPublishableSpecification {

    @FunctionalInterface
    public interface InventoryRepository {
        List<Inventory> findByProductId(ProductId productId);
    }

    private final InventoryRepository inventoryRepository;

    public ProductPublishableSpecification(InventoryRepository inventoryRepository) {
        this.inventoryRepository = Objects.requireNonNull(inventoryRepository, "inventoryRepository es obligatorio");
    }

    public boolean isSatisfiedBy(Product product) {
        if (product == null) {
            return false;
        }
        // Los productos digitales no requieren inventario ni despacho.
        if (product.getProductType() == ProductType.DIGITAL) {
            return true;
        }

        List<Inventory> inventories = inventoryRepository.findByProductId(product.getProductId());
        if (inventories == null || inventories.isEmpty()) {
            return false;
        }

        return inventories.stream().anyMatch(inv ->
                inv.getStatus() == InventoryStatus.ACTIVE
                        && inv.getAvailableQuantity().isGreaterThan(Quantity.ZERO));
    }
}