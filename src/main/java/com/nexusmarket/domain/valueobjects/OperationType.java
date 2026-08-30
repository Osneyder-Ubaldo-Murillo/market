package com.nexusmarket.domain.valueobjects;

/**
 * Catálogo de negocio: operaciones de negocio registradas en auditoría.
 * Se mantienen coherentes con {@code InventoryMovementType} (p. ej.
 * {@code INVENTORY_RELEASE} corresponde con {@code RELEASE}).
 */
public final class OperationType extends DomainCatalog {

    public static final OperationType USER_REGISTRATION = new OperationType("USER_REGISTRATION", "Registro de Usuario", "Registro de un usuario en la plataforma");
    public static final OperationType SELLER_REGISTRATION = new OperationType("SELLER_REGISTRATION", "Registro de Vendedor", "Registro de un vendedor");
    public static final OperationType BUYER_REGISTRATION = new OperationType("BUYER_REGISTRATION", "Registro de Comprador", "Registro de un comprador");

    public static final OperationType PRODUCT_CREATION = new OperationType("PRODUCT_CREATION", "Creación de Producto", "Creación de un producto");
    public static final OperationType PRODUCT_UPDATE = new OperationType("PRODUCT_UPDATE", "Actualización de Producto", "Actualización de un producto");
    public static final OperationType PRODUCT_PUBLISH = new OperationType("PRODUCT_PUBLISH", "Publicación de Producto", "Publicación de un producto");
    public static final OperationType PRODUCT_UNPUBLISH = new OperationType("PRODUCT_UNPUBLISH", "Despublicación de Producto", "Despublicación de un producto");

    public static final OperationType INVENTORY_ADD = new OperationType("INVENTORY_ADD", "Ingreso de Inventario", "Ingreso de mercancía a bodega");
    public static final OperationType INVENTORY_RESERVE = new OperationType("INVENTORY_RESERVE", "Reserva de Inventario", "Reserva de existencias para un pedido");
    public static final OperationType INVENTORY_CONFIRM_SALE = new OperationType("INVENTORY_CONFIRM_SALE", "Confirmación de Venta", "Salida definitiva por venta");
    public static final OperationType INVENTORY_RELEASE = new OperationType("INVENTORY_RELEASE", "Liberación de Inventario", "Liberación de una reserva");
    public static final OperationType INVENTORY_ADJUST = new OperationType("INVENTORY_ADJUST", "Ajuste de Inventario", "Ajuste físico de existencias");

    public static final OperationType CART_ADD_ITEM = new OperationType("CART_ADD_ITEM", "Agregar Ítem al Carrito", "Se agrega un producto al carrito");
    public static final OperationType CART_REMOVE_ITEM = new OperationType("CART_REMOVE_ITEM", "Eliminar Ítem del Carrito", "Se elimina un producto del carrito");
    public static final OperationType CART_CLEAR = new OperationType("CART_CLEAR", "Vaciar Carrito", "Se vacía el carrito");

    public static final OperationType ORDER_CREATION = new OperationType("ORDER_CREATION", "Creación de Pedido", "Se crea un pedido");
    public static final OperationType ORDER_PAYMENT = new OperationType("ORDER_PAYMENT", "Pago de Pedido", "Confirmación de pago del pedido");
    public static final OperationType ORDER_DISPATCH = new OperationType("ORDER_DISPATCH", "Despacho de Pedido", "Salida física del pedido");
    public static final OperationType ORDER_DELIVERY = new OperationType("ORDER_DELIVERY", "Entrega de Pedido", "Entrega confirmada del pedido");
    public static final OperationType ORDER_CANCELLATION = new OperationType("ORDER_CANCELLATION", "Cancelación de Pedido", "Cancelación del pedido");

    public static final OperationType INVOICE_GENERATION = new OperationType("INVOICE_GENERATION", "Generación de Factura", "Se emite una factura");
    public static final OperationType INVOICE_CANCELLATION = new OperationType("INVOICE_CANCELLATION", "Cancelación de Factura", "Se anula una factura emitida");

    public static final OperationType SHIPPING_CREATION = new OperationType("SHIPPING_CREATION", "Creación de Envío", "Se crea un envío");
    public static final OperationType SHIPPING_UPDATE = new OperationType("SHIPPING_UPDATE", "Actualización de Envío", "Se actualiza la información del envío");

    public static final OperationType RETURN_REQUEST = new OperationType("RETURN_REQUEST", "Solicitud de Devolución", "Se solicita una devolución");
    public static final OperationType RETURN_APPROVAL = new OperationType("RETURN_APPROVAL", "Aprobación de Devolución", "Se aprueba una devolución");
    public static final OperationType RETURN_REJECTION = new OperationType("RETURN_REJECTION", "Rechazo de Devolución", "Se rechaza una devolución");

    public static final OperationType REFUND_PROCESS = new OperationType("REFUND_PROCESS", "Procesamiento de Reembolso", "Se procesa un reembolso");

    private OperationType(String code, String name, String description) {
        super(code, name, description);
    }
}