package nexus.market.domain.enums;

/** Estado de la recogida de una línea del pedido, derivado de la cantidad pedida y la recogida. */
public enum PickupStatus {
    PENDING,
    PARTIAL,
    PICKED;

    public static PickupStatus of(int requested, int picked) {
        if (picked <= 0) {
            return PENDING;
        }
        return picked >= requested ? PICKED : PARTIAL;
    }
}
