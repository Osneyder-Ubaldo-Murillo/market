package nexus.market.adapters.in.rest.requests;

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
            @NotBlank(message = "productId es obligatorio") String productId,
            @Positive(message = "quantity debe ser mayor a cero") int quantity) {
    }
}
