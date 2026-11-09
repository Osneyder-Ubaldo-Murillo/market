package nexus.market.adapters.in.rest.requests;

import io.swagger.v3.oas.annotations.media.Schema;
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
            @NotBlank(message = "productId es obligatorio") @Schema(description = "Identificador del producto", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") String productId,
            @Positive(message = "quantity debe ser mayor a cero") @Schema(description = "Cantidad (mayor que cero)", example = "2") int quantity,
            @NotBlank(message = "reason es obligatorio") @Schema(description = "Motivo de la devolución: DAMAGED, WRONG_ITEM, NOT_AS_DESCRIBED u OTHER", example = "DAMAGED", allowableValues = {"DAMAGED", "WRONG_ITEM", "NOT_AS_DESCRIBED", "OTHER"}) String reason) {
    }

    /** Solicitud de devolución de un pedido (al menos una línea). */
    public record CreateReturnRequest(
            @NotNull(message = "items es obligatorio") @NotEmpty(message = "items no puede estar vacío") List<@Valid ReturnLineRequest> items) {
    }
}
