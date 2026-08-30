package com.nexusmarket.domain.exceptions;

/**
 * Se lanza cuando se intenta modificar un pedido en un estado que no lo
 * permite (por ejemplo, {@code DELIVERED} o {@code CANCELLED}).
 */
public class OrderNotModifiableException extends BusinessException {

    public OrderNotModifiableException(String message) {
        super("ORDER_NOT_MODIFIABLE", message);
    }
}