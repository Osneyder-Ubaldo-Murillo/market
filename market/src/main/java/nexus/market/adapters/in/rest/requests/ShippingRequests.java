package nexus.market.adapters.in.rest.requests;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;

/**
 * DTOs de petición de envíos.
 */
public final class ShippingRequests {

    private ShippingRequests() {
    }

    /** Despacho del envío; si no se envía número de guía, la transportadora lo genera. */
    public record DispatchShippingRequest(
            @NotBlank(message = "trackingNumber es obligatorio") @Size(max = 64, message = "trackingNumber no puede exceder 64 caracteres") String trackingNumber,
            @NotBlank(message = "carrier es obligatorio") @Size(max = 80, message = "carrier no puede exceder 80 caracteres") String carrier) {
    }
}
