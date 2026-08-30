package com.nexusmarket.domain.enums;

/**
 * Tipo de movimiento de inventario. Todo cambio de existencias en un
 * {@code Inventory} debe quedar registrado con uno de estos movimientos.
 *
 * <p>Enum técnico: no hereda de {@code DomainCatalog}.</p>
 */
public enum InventoryMovementType {

    /** Ingreso de mercancía a la bodega. */
    INCOME,

    /** Reserva de existencias para un pedido en curso. */
    RESERVATION,

    /** Liberación de una reserva (pedido cancelado o reserva anulada). */
    RELEASE,

    /** Salida definitiva por venta confirmada. */
    SALE,

    /** Ajuste físico (merma, sobrante, corrección de conteo). */
    ADJUSTMENT,

    /** Devolución de productos a la bodega. */
    RETURN
}