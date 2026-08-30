package com.nexusmarket.domain.valueobjects;

/**
 * Catálogo de negocio: estado del {@code Order}.
 */
public final class OrderStatus extends DomainCatalog {

    public static final OrderStatus CART = new OrderStatus("CART", "Carrito", "Selección provisional");
    public static final OrderStatus PENDING_PAYMENT = new OrderStatus("PENDING_PAYMENT", "Pendiente de Pago", "Espera confirmación financiera");
    public static final OrderStatus PAID = new OrderStatus("PAID", "Pagado", "Inicio de alistamiento");
    public static final OrderStatus DISPATCHED = new OrderStatus("DISPATCHED", "Despachado", "Salida física de la bodega");
    public static final OrderStatus DELIVERED = new OrderStatus("DELIVERED", "Entregado", "Conclusión satisfactoria");
    public static final OrderStatus CANCELLED = new OrderStatus("CANCELLED", "Cancelado", "Pedido anulado");

    private OrderStatus(String code, String name, String description) {
        super(code, name, description);
    }

    public static OrderStatus cart() {
        return CART;
    }

    public static OrderStatus pendingPayment() {
        return PENDING_PAYMENT;
    }

    public static OrderStatus paid() {
        return PAID;
    }

    public static OrderStatus dispatched() {
        return DISPATCHED;
    }

    public static OrderStatus delivered() {
        return DELIVERED;
    }

    public static OrderStatus cancelled() {
        return CANCELLED;
    }
}