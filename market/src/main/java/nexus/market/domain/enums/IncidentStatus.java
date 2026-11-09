package nexus.market.domain.enums;

/** Estado de atención de una novedad: {@code OPEN → IN_PROGRESS → RESOLVED}. Una novedad resuelta no se reabre. */
public enum IncidentStatus {
    OPEN,
    IN_PROGRESS,
    RESOLVED;

    /** Sigue sin cerrarse y detiene el avance del pedido. */
    public boolean isPending() {
        return this != RESOLVED;
    }
}
