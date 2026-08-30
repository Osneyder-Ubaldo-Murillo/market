package com.nexusmarket.domain.specifications;

import com.nexusmarket.domain.models.Order;
import com.nexusmarket.domain.valueobjects.OrderStatus;

/**
 * Determina si un pedido puede modificarse: no debe estar entregado
 * ({@code DELIVERED}) ni cancelado ({@code CANCELLED}).
 */
public class OrderModifiableSpecification {

    public boolean isSatisfiedBy(Order order) {
        return order != null
                && order.getStatus() != OrderStatus.DELIVERED
                && order.getStatus() != OrderStatus.CANCELLED;
    }
}