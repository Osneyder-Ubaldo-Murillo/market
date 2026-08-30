package com.nexusmarket.domain.valueobjects;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Value Object inmutable que representa la confirmación de un pago.
 */
public final class PaymentConfirmation {

    private final String transactionId;
    private final LocalDateTime date;
    private final String paymentMethod;

    private PaymentConfirmation(String transactionId, LocalDateTime date, String paymentMethod) {
        this.transactionId = transactionId;
        this.date = date;
        this.paymentMethod = paymentMethod;
    }

    public static PaymentConfirmation of(String transactionId, LocalDateTime date, String paymentMethod) {
        if (transactionId == null || transactionId.isBlank()) {
            throw new IllegalArgumentException("transactionId es obligatorio");
        }
        if (date == null) {
            throw new IllegalArgumentException("date es obligatorio");
        }
        if (paymentMethod == null || paymentMethod.isBlank()) {
            throw new IllegalArgumentException("paymentMethod es obligatorio");
        }
        return new PaymentConfirmation(transactionId.trim(), date, paymentMethod.trim());
    }

    public String getTransactionId() {
        return transactionId;
    }

    public LocalDateTime getDate() {
        return date;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PaymentConfirmation that = (PaymentConfirmation) o;
        return transactionId.equals(that.transactionId)
                && date.equals(that.date)
                && paymentMethod.equals(that.paymentMethod);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transactionId, date, paymentMethod);
    }

    @Override
    public String toString() {
        return paymentMethod + " #" + transactionId + " @" + date;
    }
}