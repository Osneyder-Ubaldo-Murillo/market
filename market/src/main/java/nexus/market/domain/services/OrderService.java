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
        for (CartItem item : cart.getItems()) {
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

        PaymentConfirmation confirmation =
                payments.charge(orderId, order.getTotal(), paymentMethod);
        order.confirmPayment();
        orders.save(order);

        String reference = orderId.toString();
        for (OrderItem item : order.getItems()) {
            if (item.getProductType() == ProductType.PHYSICAL) {
                inventoryService.confirmSaleForProduct(item.getProductId(), item.getQuantity(), reference);
            }
        }
        invoiceService.generateInvoice(orderId);

        audit.record(OperationType.ORDER_PAYMENT, AuditSeverity.INFO,
                order.getBuyerId().toString(), "Pago confirmado: " + orderId);
        return confirmation;
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

        order.cancel();
        orders.save(order);

        if (order.getInvoiceId() != null) {
            invoiceService.cancelInvoice(order.getInvoiceId());
        }
        for (OrderItem item : order.getItems()) {
            if (item.getProductType() == ProductType.PHYSICAL) {
                inventoryService.releaseForProduct(item.getProductId(), item.getQuantity(), orderId.toString());
            }
        }
        audit.record(OperationType.ORDER_CANCELLATION, AuditSeverity.WARNING,
                order.getBuyerId().toString(), "Pedido cancelado: " + orderId);
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