package nexus.market.adapters.in.rest.requests;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * DTOs de petición del carrito.
 */
public final class CartRequests {

    private CartRequests() {
    }

    /** Agregar producto al carrito (precio y nombre se resuelven del catálogo). */
    public record AddCartItemRequest(
            @NotBlank(message = "productId es obligatorio") @Schema(description = "Identificador del producto", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") String productId,
            @Positive(message = "quantity debe ser mayor a cero") @Schema(description = "Cantidad (mayor que cero)", example = "2") int quantity) {
    }
}
