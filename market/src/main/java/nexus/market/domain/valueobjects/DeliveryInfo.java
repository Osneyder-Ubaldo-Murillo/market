package nexus.market.domain.valueobjects;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Value Object inmutable que agrupa la información de seguimiento de un envío.
 * {@code trackingNumber} y {@code estimatedDate} son opcionales (se asignan al
 * despachar con la transportadora); {@code carrier} es obligatorio.
 */
public final class DeliveryInfo {

    private final String trackingNumber;
    private final String carrier;
    private final LocalDate estimatedDate;

    private DeliveryInfo(String trackingNumber, String carrier, LocalDate estimatedDate) {
        this.trackingNumber = trackingNumber;
        this.carrier = carrier;
        this.estimatedDate = estimatedDate;
    }

    public static DeliveryInfo of(String trackingNumber, String carrier, LocalDate estimatedDate) {
        if (carrier == null || carrier.isBlank()) {
            throw new IllegalArgumentException("carrier es obligatorio");
        }
        return new DeliveryInfo(
                (trackingNumber == null) ? "" : trackingNumber.trim(),
                carrier.trim(),
                estimatedDate);
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public boolean hasTrackingNumber() {
        return trackingNumber != null && !trackingNumber.isBlank();
    }

    public String getCarrier() {
        return carrier;
    }

    public LocalDate getEstimatedDate() {
        return estimatedDate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DeliveryInfo that = (DeliveryInfo) o;
        return trackingNumber.equals(that.trackingNumber)
                && carrier.equals(that.carrier)
                && Objects.equals(estimatedDate, that.estimatedDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(trackingNumber, carrier, estimatedDate);
    }

    @Override
    public String toString() {
        return carrier + (hasTrackingNumber() ? " #" + trackingNumber : "")
                + (estimatedDate != null ? " est. " + estimatedDate : "");
    }
}