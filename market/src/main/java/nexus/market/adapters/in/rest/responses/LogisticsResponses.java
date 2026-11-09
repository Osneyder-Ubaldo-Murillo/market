package nexus.market.adapters.in.rest.responses;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;
import nexus.market.application.LogisticsViews.EventView;
import nexus.market.application.LogisticsViews.IncidentView;
import nexus.market.application.LogisticsViews.OrderSummaryView;
import nexus.market.application.LogisticsViews.PickupLineView;

/** Respuestas de la gestión logística y la supervisión de pedidos. */
public final class LogisticsResponses {

    private LogisticsResponses() {
    }

    /**
     * Detalle de un pedido: resumen, líneas con su avance de recogida, novedades e historial cronológico. La dirección de entrega
     * solo la recibe el operador asignado (y el administrador); el listado y el supervisor no la ven.
     */
    public record OrderDetailResponse(
            OrderSummaryView order,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "Dirección de entrega (solo operador asignado)") UserResponses.AddressResponse shippingAddress,
            @Schema(description = "Identificador del envío, si ya se creó") String shippingId,
            @Schema(description = "Estado del envío: PREPARING, DISPATCHED, IN_TRANSIT o DELIVERED") String shippingStatus,
            List<PickupLineView> lines,
            List<IncidentView> incidents,
            List<EventView> timeline) {
    }
}
