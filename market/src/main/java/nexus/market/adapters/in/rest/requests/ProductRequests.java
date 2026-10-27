package nexus.market.adapters.in.rest.requests;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import nexus.market.adapters.in.rest.requests.CommonRequests.MoneyRequest;

/**
 * DTOs de petición del catálogo de productos.
 */
public final class ProductRequests {

    private ProductRequests() {
    }

    /**
     * Creación de producto. {@code type} es {@code PHYSICAL} o {@code DIGITAL};
     * queda {@code INACTIVE} hasta publicarse.
     */
    public record CreateProductRequest(
            @NotBlank(message = "sellerId es obligatorio") String sellerId,
            @NotBlank(message = "name es obligatorio") @Size(max = 100, message = "name no puede exceder 100 caracteres") String name,
            @NotBlank(message = "description es obligatorio") @Size(max = 500, message = "description no puede exceder 500 caracteres") String description,
            @NotBlank(message = "type es obligatorio (PHYSICAL o DIGITAL)") String type,
            @NotNull(message = "price es obligatorio") @Valid MoneyRequest price) {
    }

    /** Actualización de precio. */
    public record UpdatePriceRequest(@NotNull(message = "price es obligatorio") @Valid MoneyRequest price) {
    }
}
