package nexus.market.adapters.in.rest.requests;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import nexus.market.adapters.in.rest.requests.CommonRequests.AddressRequest;

/**
 * DTOs de petición del ciclo de compra.
 */
public final class OrderRequests {

    private OrderRequests() {
    }

    /** Creación de pedido desde el carrito (dirección de envío). */
    public record CreateOrderRequest(
            @NotNull(message = "shippingAddress es obligatoria") @Valid @Schema(description = "Dirección de envío") AddressRequest shippingAddress) {
    }

    /** Confirmación de pago (método: CARD, PSE, ... según la pasarela). */
    public record PaymentRequest(
            @NotBlank(message = "paymentMethod es obligatorio") @Schema(description = "Método de pago (la pasarela es simulada)", example = "CARD") String paymentMethod) {
    }

    /** Despacho del pedido desde una bodega. */
    public record DispatchOrderRequest(
            @NotBlank(message = "warehouseId es obligatorio") @Schema(description = "Identificador de la bodega", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") String warehouseId) {
    }
}
