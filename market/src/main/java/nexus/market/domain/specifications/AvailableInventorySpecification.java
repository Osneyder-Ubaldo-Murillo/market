package nexus.market.domain.specifications;

import nexus.market.domain.models.Inventory;
import nexus.market.domain.valueobjects.InventoryStatus;
import nexus.market.domain.valueobjects.Quantity;

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