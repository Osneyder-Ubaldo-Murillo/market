package com.nexusmarket.domain.specifications;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.nexusmarket.domain.exceptions.BusinessException;
import com.nexusmarket.domain.models.Order;
import com.nexusmarket.domain.valueobjects.OrderStatus;
import com.nexusmarket.domain.valueobjects.Quantity;
import com.nexusmarket.domain.valueobjects.ReturnItem;

/**
 * Valida si un pedido entregado es elegible para devolución/reembolso (dentro
 * del plazo {@code maxDays} definido en la configuración de negocio).
 */
public class RefundEligibilitySpecification {

    private final int maxDays;

    public RefundEligibilitySpecification(int maxDays) {
        if (maxDays <= 0) {
            throw new BusinessException("INVALID_CONFIGURATION", "maxDays debe ser mayor que cero.");
        }
        this.maxDays = maxDays;
    }

    public boolean isSatisfiedBy(Order order, ReturnItem returnItem) {
        if (order == null || returnItem == null) {
            return false;
        }
        if (order.getStatus() != OrderStatus.DELIVERED) {
            return false;
        }
        if (order.getDeliveredAt() == null) {
            return false;
        }

        LocalDate deliveryDate = order.getDeliveredAt().toLocalDate();
        if (deliveryDate.isAfter(LocalDate.now())) {
            return false;
        }

        long days = ChronoUnit.DAYS.between(deliveryDate, LocalDate.now());
        return days <= maxDays && returnItem.getQuantity().isGreaterThan(Quantity.ZERO);
    }
}