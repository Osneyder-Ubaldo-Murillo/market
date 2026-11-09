package nexus.market.domain.models;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import nexus.market.domain.enums.IncidentStatus;
import nexus.market.domain.enums.IncidentType;
import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.ProductId;

/**
 * Novedad logística de un pedido (retraso, faltante, daño...). Conserva quién la reportó, quién tiene su seguimiento y quién
 * la cerró; resolverla no borra el registro original. Flujo: {@code OPEN -> IN_PROGRESS -> RESOLVED} (no se reabre).
 */
public class OrderIncident {

    private final String incidentId;
    private final OrderId orderId;
    private final ProductId productId;
    private final IncidentType type;
    private final String description;
    private final String reportedBy;
    private final LocalDateTime reportedAt;
    private IncidentStatus status;
    private String responsibleId;
    private String resolutionNotes;
    private String resolvedBy;
    private LocalDateTime resolvedAt;
    private LocalDateTime updatedAt;

    public OrderIncident(String incidentId, OrderId orderId, ProductId productId, IncidentType type, String description,
                         String reportedBy, LocalDateTime reportedAt, IncidentStatus status, String responsibleId,
                         String resolutionNotes, String resolvedBy, LocalDateTime resolvedAt, LocalDateTime updatedAt) {
        this.incidentId = Objects.requireNonNull(incidentId, "incidentId es obligatorio");
        this.orderId = Objects.requireNonNull(orderId, "orderId es obligatorio");
        this.productId = productId;
        this.type = Objects.requireNonNull(type, "type es obligatorio");
        this.description = Objects.requireNonNull(description, "description es obligatoria");
        this.reportedBy = Objects.requireNonNull(reportedBy, "reportedBy es obligatorio");
        this.reportedAt = Objects.requireNonNull(reportedAt, "reportedAt es obligatorio");
        this.status = Objects.requireNonNull(status, "status es obligatorio");
        this.responsibleId = responsibleId;
        this.resolutionNotes = resolutionNotes;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = resolvedAt;
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt es obligatorio");
    }

    public static OrderIncident report(OrderId orderId, ProductId productId, IncidentType type, String description,
                                       String reportedBy) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("La descripción de la novedad es obligatoria");
        }
        LocalDateTime now = LocalDateTime.now();
        return new OrderIncident(UUID.randomUUID().toString(), orderId, productId, type, description.trim(), reportedBy,
                now, IncidentStatus.OPEN, null, null, null, null, now);
    }

    /** El supervisor dejó una instrucción: la novedad pasa a {@code IN_PROGRESS} si seguía abierta. */
    public void startFollowUp() {
        requireNotResolved();
        if (status == IncidentStatus.OPEN) {
            status = IncidentStatus.IN_PROGRESS;
        }
        touch();
    }

    public void assignResponsible(String userId) {
        requireNotResolved();
        this.responsibleId = Objects.requireNonNull(userId, "userId es obligatorio");
        if (status == IncidentStatus.OPEN) {
            status = IncidentStatus.IN_PROGRESS;
        }
        touch();
    }

    public void resolve(String resolver, String notes) {
        requireNotResolved();
        if (notes == null || notes.isBlank()) {
            throw new IllegalArgumentException("Las observaciones de solución son obligatorias");
        }
        this.status = IncidentStatus.RESOLVED;
        this.resolutionNotes = notes.trim();
        this.resolvedBy = Objects.requireNonNull(resolver, "resolver es obligatorio");
        this.resolvedAt = LocalDateTime.now();
        touch();
    }

    private void requireNotResolved() {
        if (status == IncidentStatus.RESOLVED) {
            throw new BusinessException("INVALID_STATE_TRANSITION", "La novedad ya fue resuelta y no admite cambios.");
        }
    }

    private void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    public String getIncidentId() {
        return incidentId;
    }

    public OrderId getOrderId() {
        return orderId;
    }

    public ProductId getProductId() {
        return productId;
    }

    public IncidentType getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public String getReportedBy() {
        return reportedBy;
    }

    public LocalDateTime getReportedAt() {
        return reportedAt;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public String getResponsibleId() {
        return responsibleId;
    }

    public String getResolutionNotes() {
        return resolutionNotes;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
