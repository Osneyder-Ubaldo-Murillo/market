package nexus.market.adapters.in.rest.requests;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * DTOs de petición de inventario y bodegas.
 */
public final class InventoryRequests {

    private InventoryRequests() {
    }

    /** Creación del par (producto, bodega) en cero. */
    public record CreateInventoryRequest(
            @NotBlank(message = "productId es obligatorio") @Schema(description = "Identificador del producto", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") String productId,
            @NotBlank(message = "warehouseId es obligatorio") @Schema(description = "Identificador de la bodega", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") String warehouseId) {
    }
}
