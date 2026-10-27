package nexus.market.adapters.in.rest.requests;

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
            @NotNull(message = "shippingAddress es obligatoria") @Valid AddressRequest shippingAddress) {
    }

    /** Confirmación de pago (método: CARD, PSE, ... según la pasarela). */
    public record PaymentRequest(
            @NotBlank(message = "paymentMethod es obligatorio") String paymentMethod) {
    }

    /** Despacho del pedido desde una bodega. */
    public record DispatchOrderRequest(
            @NotBlank(message = "warehouseId es obligatorio") String warehouseId) {
    }
}
