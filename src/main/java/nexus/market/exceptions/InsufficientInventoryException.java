package com.nexusmarket.domain.exceptions;

/**
 * Se lanza cuando se intenta reservar o vender más existencias de las
 * disponibles (descontando reservas) en un inventario.
 */
public class InsufficientInventoryException extends BusinessException {

    public InsufficientInventoryException(String message) {
        super("INSUFFICIENT_INVENTORY", message);
    }
}