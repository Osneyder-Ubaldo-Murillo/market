package com.nexusmarket.domain.specifications;

import com.nexusmarket.domain.models.Inventory;
import com.nexusmarket.domain.valueobjects.InventoryStatus;
import com.nexusmarket.domain.valueobjects.Quantity;

/**
 * Verifica que un inventario tenga suficiente cantidad disponible (descontando
 * reservas) y no esté dañado.
 */
public class AvailableInventorySpecification {

    public boolean isSatisfiedBy(Inventory inventory, Quantity required) {
        if (inventory == null || required == null) {
            return false;
        }
        return inventory.getStatus() != InventoryStatus.DAMAGED
                && required.isLessThanOrEqual(inventory.getAvailableQuantity());
    }
}