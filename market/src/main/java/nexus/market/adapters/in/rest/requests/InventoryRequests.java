package nexus.market.adapters.in.rest.requests;

import jakarta.validation.constraints.NotBlank;

/**
 * DTOs de petición de inventario y bodegas.
 */
public final class InventoryRequests {

    private InventoryRequests() {
    }

    /** Creación del par (producto, bodega) en cero. */
    public record CreateInventoryRequest(
            @NotBlank(message = "productId es obligatorio") String productId,
            @NotBlank(message = "warehouseId es obligatorio") String warehouseId) {
    }
}
