package nexus.market.domain.services;

import java.util.Objects;
import java.util.Optional;

import nexus.market.domain.enums.AuditSeverity;
import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.exceptions.OrderNotModifiableException;
import nexus.market.domain.models.Buyer;
import nexus.market.domain.models.Cart;
import nexus.market.domain.models.Order;
import nexus.market.domain.ports.out.BuyerRepositoryPort;
import nexus.market.domain.ports.out.CartRepositoryPort;
import nexus.market.domain.ports.out.OrderRepositoryPort;
import nexus.market.domain.ports.out.PaymentServicePort;
import nexus.market.domain.specifications.ActiveBuyerSpecification;
import nexus.market.domain.specifications.OrderModifiableSpecification;
import nexus.market.domain.valueobjects.Address;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.CartItem;
import nexus.market.domain.valueobjects.OperationType;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.OrderItem;
import nexus.market.domain.valueobjects.PaymentConfirmation;
import nexus.market.domain.valueobjects.ProductType;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * Servicio de dominio núcleo de la compra (SDD - Domain Services:
 * {@code OrderService}): crea el pedido desde el carrito, reserva inventario,
 * confirma el pago (pasarela), despacha, entrega y cancela.
 */
public class OrderService {

    private final OrderRepositoryPort orders;
    private final CartRepositoryPort carts;
    private final BuyerRepositoryPort buyers;
    private final PaymentServicePort payments;
    private final ActiveBuyerSpecification activeBuyer;
    private final OrderModifiableSpecification orderModifiable;
    private final InventoryService inventoryService;
    private final ShippingService shippingService;
    private final InvoiceService invoiceService;
    private final AuditService audit;

    public OrderService(OrderRepositoryPort orders, CartRepositoryPort carts,
                        BuyerRepositoryPort buyers, PaymentServicePort payments,
                        ActiveBuyerSpecification activeBuyer,
                        OrderModifiableSpecification orderModifiable,
                        InventoryService inventoryService,
                        ShippingService shippingService,
                        InvoiceService invoiceService,
                        AuditService audit) {
        this.orders = Objects.requireNonNull(orders, "orders es obligatorio");
        this.carts = Objects.requireNonNull(carts, "carts es obligatorio");
        this.buyers = Objects.requireNonNull(buyers, "buyers es obligatorio");
        this.payments = Objects.requireNonNull(payments, "payments es obligatorio");
        this.activeBuyer = Objects.requireNonNull(activeBuyer, "activeBuyer es obligatorio");
        this.orderModifiable = Objects.requireNonNull(orderModifiable, "orderModifiable es obligatorio");
        this.inventoryService = Objects.requireNonNull(inventoryService, "inventoryService es obligatorio");
        this.shippingService = Objects.requireNonNull(shippingService, "shippingService es obligatorio");
        this.invoiceService = Objects.requireNonNull(invoiceService, "invoiceService es obligatorio");
        this.audit = Objects.requireNonNull(audit, "audit es obligatorio");
    }

    /**
     * Crea un pedido {@code PENDING_PAYMENT} congelando los ítems del carrito
     * y reserva el inventario de los productos físicos.
     */
    public Order createOrder(BuyerId buyerId, Address shippingAddress) {
        Objects.requireNonNull(buyerId, "buyerId es obligatorio");
        Objects.requireNonNull(shippingAddress, "shippingAddress es obligatorio");
        Buyer buyer = loadBuyer(buyerId);
        requireActiveBuyer(buyer);

        Cart cart = carts.findByBuyerId(buyerId)
                .orElseThrow(() -> new BusinessException("EMPTY_CART", "El comprador no tiene carrito."));
        if (cart.getItems().isEmpty()) {
            throw new BusinessException("EMPTY_CART", "No se puede crear un pedido desde un carrito vacío.");
        }

        java.util.List<OrderItem> frozen = new java.util.ArrayList<>();
        for (CartItem item : cart.getItems()) {
            frozen.add(OrderItem.from(item));
        }
        Order order = orders.save(Order.create(buyerId, frozen, shippingAddress));

        String reference = order.getOrderId().toString();
        for (CartItem item : inLockOrder(cart.getItems(), CartItem::getProductId)) {
            if (item.getProductType() == ProductType.PHYSICAL) {
                inventoryService.reserveForProduct(item.getProductId(), item.getQuantity(), reference);
            }
        }
        audit.record(OperationType.ORDER_CREATION, AuditSeverity.INFO,
                buyerId.toString(), "Pedido creado: " + order.getOrderId());
        return order;
    }

    /**
     * Confirma el pago ({@code PENDING_PAYMENT → PAID}): cobra en la pasarela,
     * confirma la venta del inventario reservado y genera la factura.
     */
    public PaymentConfirmation confirmPayment(OrderId orderId, String paymentMethod) {
        Objects.requireNonNull(orderId, "orderId es obligatorio");
        Order order = loadOrder(orderId);
        if (!orderModifiable.isSatisfiedBy(order)) {
            throw new OrderNotModifiableException("El pedido no puede modificarse.");
        }

        // El estado se valida ANTES del cobro externo (un cobro no se puede deshacer con un rollback).
        order.requirePendingPayment();
        PaymentConfirmation confirmation =
                payments.charge(orderId, order.getTotal(), paymentMethod);
        try {
            order.confirmPayment();
            order.recordPaymentTransaction(confirmation.getTransactionId());
            orders.save(order);

            String reference = orderId.toString();
            for (OrderItem item : inLockOrder(order.getItems(), OrderItem::getProductId)) {
                if (item.getProductType() == ProductType.PHYSICAL) {
                    inventoryService.confirmSaleForProduct(item.getProductId(), item.getQuantity(), reference);
                }
            }
            invoiceService.generateInvoice(orderId);
        } catch (RuntimeException ex) {
            // El rollback de la base de datos no deshace el cobro externo: se intenta anular el cobro para que el cliente
            // no pague un pedido que queda PENDING_PAYMENT. Si la anulación también falla, queda constancia para conciliar.
            reverseChargeAfterFailure(order, confirmation);
            throw ex;
        }

        audit.record(OperationType.ORDER_PAYMENT, AuditSeverity.INFO,
                order.getBuyerId().toString(), "Pago confirmado: " + orderId);
        return confirmation;
    }

    private void reverseChargeAfterFailure(Order order, PaymentConfirmation confirmation) {
        OrderId orderId = order.getOrderId();
        try {
            payments.refund(orderId, order.getTotal(), confirmation.getTransactionId());
            audit.record(OperationType.REFUND_PROCESS, AuditSeverity.WARNING, order.getBuyerId().toString(),
                    "Cobro anulado porque el pago del pedido " + orderId + " no pudo completarse");
        } catch (RuntimeException reversalFailure) {
            audit.record(OperationType.REFUND_PROCESS, AuditSeverity.ERROR, order.getBuyerId().toString(),
                    "CONCILIACIÓN: cobro " + confirmation.getTransactionId() + " del pedido " + orderId
                            + " sin pedido pagado y sin anular (" + reversalFailure.getClass().getSimpleName() + ")");
        }
    }

    /** Despacha el pedido ({@code PAID → DISPATCHED}) creando el envío físico. */
    public void dispatch(OrderId orderId, WarehouseId warehouseId) {
        Objects.requireNonNull(orderId, "orderId es obligatorio");
        Objects.requireNonNull(warehouseId, "warehouseId es obligatorio");
        Order order = loadOrder(orderId);

        shippingService.createShipping(orderId, warehouseId);
        order.dispatch();
        orders.save(order);
        audit.record(OperationType.ORDER_DISPATCH, AuditSeverity.INFO,
                order.getBuyerId().toString(), "Pedido despachado: " + orderId);
    }

    /** Entrega el pedido ({@code DISPATCHED → DELIVERED}) y asigna {@code deliveredAt}. */
    public void deliver(OrderId orderId) {
        Objects.requireNonNull(orderId, "orderId es obligatorio");
        Order order = loadOrder(orderId);
        order.deliver();
        orders.save(order);
        audit.record(OperationType.ORDER_DELIVERY, AuditSeverity.INFO,
                order.getBuyerId().toString(), "Pedido entregado: " + orderId);
    }

    /** Cancela el pedido: libera reservas y anula la factura si existía. */
    public void cancel(OrderId orderId) {
        Objects.requireNonNull(orderId, "orderId es obligatorio");
        Order order = loadOrder(orderId);
        if (!orderModifiable.isSatisfiedBy(order)) {
            throw new OrderNotModifiableException("El pedido no puede modificarse.");
        }

        boolean wasPaid = order.getStatus() == nexus.market.domain.valueobjects.OrderStatus.PAID;
        order.cancel();
        orders.save(order);
        if (order.getInvoiceId() != null) {
            invoiceService.cancelInvoice(order.getInvoiceId());
        }
        if (wasPaid) {
            // El cliente ya pago: se le devuelve el dinero. Si la pasarela falla, la excepcion revierte TODO (el pedido
            // sigue PAID y no se repone nada); nunca queda un pedido cancelado y cobrado.
            try {
                if (order.hasLegacyChargeReference()) {
                    audit.record(OperationType.REFUND_PROCESS, AuditSeverity.WARNING, order.getBuyerId().toString(),
                            "Reembolso con referencia de cobro derivada (pedido sin transacción registrada): " + orderId);
                }
                payments.refund(orderId, order.getTotal(), order.chargeReference());
            } catch (RuntimeException ex) {
                audit.record(OperationType.REFUND_PROCESS, AuditSeverity.ERROR, order.getBuyerId().toString(),
                        "Reembolso fallido al cancelar el pedido " + orderId + " (" + ex.getClass().getSimpleName() + ")");
                throw new BusinessException("REFUND_FAILED",
                        "No se pudo reembolsar el pago; el pedido no se canceló.");
            }
        }
        for (OrderItem item : inLockOrder(order.getItems(), OrderItem::getProductId)) {
            if (item.getProductType() == ProductType.PHYSICAL) {
                if (wasPaid) {
                    // La venta ya estaba confirmada (no hay reserva que liberar): las unidades vuelven al inventario.
                    inventoryService.receiveReturnForProduct(item.getProductId(), item.getQuantity(), orderId.toString());
                } else {
                    inventoryService.releaseForProduct(item.getProductId(), item.getQuantity(), orderId.toString());
                }
            }
        }
        audit.record(OperationType.ORDER_CANCELLATION, AuditSeverity.WARNING,
                order.getBuyerId().toString(), "Pedido cancelado: " + orderId);
    }

    /**
     * Orden de bloqueo de inventario: los productos de una operación se procesan SIEMPRE por
     * {@code productId} ascendente (y, dentro de un producto, por {@code inventory_id} ascendente en el
     * adaptador). Dos pedidos con los mismos productos en distinto orden adquieren los bloqueos en el
     * mismo orden y no se interbloquean. Ver docs/CONCURRENCIA_BLOQUEOS.md.
     */
    static <T> java.util.List<T> inLockOrder(java.util.Collection<T> items,
                                             java.util.function.Function<T, nexus.market.domain.valueobjects.ProductId> productId) {
        java.util.List<T> sorted = new java.util.ArrayList<>(items);
        sorted.sort(java.util.Comparator.comparing(item -> productId.apply(item).value()));
        return sorted;
    }

    /** Una página de los pedidos del comprador, del más reciente al más antiguo. */
    public java.util.List<Order> findByBuyer(BuyerId buyerId, int offset, int limit) {
        Objects.requireNonNull(buyerId, "buyerId es obligatorio");
        return orders.findByBuyerId(buyerId, offset, limit);
    }

    public long countByBuyer(BuyerId buyerId) {
        Objects.requireNonNull(buyerId, "buyerId es obligatorio");
        return orders.countByBuyerId(buyerId);
    }

    public Optional<Order> getOrder(OrderId orderId) {
        return orders.findById(orderId);
    }

    private Order loadOrder(OrderId orderId) {
        Objects.requireNonNull(orderId, "orderId es obligatorio");
        return orders.findById(orderId)
                .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Pedido inexistente."));
    }

    private Buyer loadBuyer(BuyerId buyerId) {
        return buyers.findById(buyerId)
                .orElseThrow(() -> new BusinessException("BUYER_NOT_FOUND", "Comprador inexistente."));
    }

    private void requireActiveBuyer(Buyer buyer) {
        if (!activeBuyer.isSatisfiedBy(buyer)) {
            throw new BusinessException("INACTIVE_BUYER", "El comprador no puede crear pedidos.");
        }
    }
}