package nexus.market.application;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Vistas de solo lectura de la gestión logística de un pedido. Los listados llevan el nombre del comprador y no su correo,
 * dirección ni documento: solo el detalle del operador asignado incluye la dirección de entrega (la respuesta REST la agrega).
 */
public final class LogisticsViews {

    private LogisticsViews() {
    }

    /** Persona identificada por su cuenta y su nombre. */
    public record PersonRef(String userId, String name) {
    }

    /** Línea física del pedido con su avance de recogida. */
    public record PickupLineView(String productId, String productName, String sellerSku, String sellerId,
                                 String sellerName, int requested, int picked, int pending, String status) {
    }

    public record IncidentView(String incidentId, String orderId, String orderCode, String productId,
                               String productName, String type, String description, String status,
                               PersonRef reportedBy, LocalDateTime reportedAt, PersonRef responsible,
                               String resolutionNotes, PersonRef resolvedBy, LocalDateTime resolvedAt,
                               LocalDateTime updatedAt) {
    }

    public record EventView(String type, String incidentId, PersonRef actor, String actorRole, String detail,
                            LocalDateTime at) {
    }

    /**
     * Resumen para los listados. {@code logisticsStage} resume el avance real: {@code PENDING_PICKUP},
     * {@code PARTIALLY_PICKED}, {@code PICKED}, {@code PREPARING}, {@code DISPATCHED}, {@code IN_TRANSIT},
     * {@code DELIVERED}.
     */
    public record OrderSummaryView(String orderId, String orderCode, String status, String logisticsStage,
                                   LocalDateTime createdAt, LocalDateTime updatedAt, String buyerName, int productLines,
                                   int totalUnits, List<String> sellers, String productsSummary, int linesPicked,
                                   int linesPending, int unitsRequested, int unitsPicked, int openIncidents,
                                   PersonRef operator, LocalDateTime assignedAt, List<String> availableActions) {
    }

    public record OrderDetailView(OrderSummaryView order, String shippingId, String shippingStatus,
                                  List<PickupLineView> lines, List<IncidentView> incidents, List<EventView> timeline) {
    }
}
