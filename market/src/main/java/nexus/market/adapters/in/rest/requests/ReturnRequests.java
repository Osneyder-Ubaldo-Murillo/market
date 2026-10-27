package nexus.market.adapters.in.rest.requests;

import jakarta.validation.Valid;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * DTOs de petición de devoluciones y reembolsos.
 */
public final class ReturnRequests {

    private ReturnRequests() {
    }

    /**
     * Línea de devolución: producto, cantidad y motivo
     * ({@code DAMAGED}, {@code WRONG_ITEM}, {@code NOT_AS_DESCRIBED}, {@code OTHER}).
     */
    public record ReturnLineRequest(
            @NotBlank(message = "productId es obligatorio") String productId,
            @Positive(message = "quantity debe ser mayor a cero") int quantity,
            @NotBlank(message = "reason es obligatorio") String reason) {
    }

    /** Solicitud de devolución de un pedido (al menos una línea). */
    public record CreateReturnRequest(
            @NotNull(message = "items es obligatorio") @NotEmpty(message = "items no puede estar vacío") List<@Valid ReturnLineRequest> items) {
    }
}
