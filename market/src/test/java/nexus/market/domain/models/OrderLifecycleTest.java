package nexus.market.domain.models;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.valueobjects.Address;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.InvoiceId;
import nexus.market.domain.valueobjects.Money;
import nexus.market.domain.valueobjects.OrderItem;
import nexus.market.domain.valueobjects.OrderStatus;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.ProductName;
import nexus.market.domain.valueobjects.ProductType;
import nexus.market.domain.valueobjects.Quantity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Prueba la máquina de estados del {@code Order}:
 * {@code PENDING_PAYMENT → PAID → DISPATCHED → DELIVERED}, cancelaciones y
 * validación de moneda.
 */
class OrderLifecycleTest {

    private static final BuyerId BUYER = BuyerId.generate();
    private static final Address ADDRESS = Address.of(
            "Av. Siempre Viva", "742", null, "Springfield", "Ciudad",
            "Provincia", "12345", "País");

    private static OrderItem item(double price, int quantity) {
        return OrderItem.of(ProductId.generate(), ProductName.of("Producto"),
                Quantity.of(quantity), Money.of(price, "USD"), ProductType.PHYSICAL);
    }

    private static Order newOrder() {
        return Order.create(BUYER, java.util.List.of(item(10.00, 2)), ADDRESS);
    }

    @Test
    void pedidoSeCreaPendienteDePagoConTotalCalculado() {
        Order order = newOrder();
        assertEquals(OrderStatus.PENDING_PAYMENT, order.getStatus());
        assertEquals(BUYER, order.getBuyerId());
        assertEquals(0, order.getTotal().compareTo(Money.of(20.00, "USD")));
        assertNull(order.getInvoiceId());
        assertNull(order.getDeliveredAt());
    }

    @Test
    void flujoCompletoHastaLaEntrega() {
        Order order = newOrder();
        order.confirmPayment();
        assertEquals(OrderStatus.PAID, order.getStatus());

        order.dispatch();
        assertEquals(OrderStatus.DISPATCHED, order.getStatus());

        order.deliver();
        assertEquals(OrderStatus.DELIVERED, order.getStatus());
        assertNotNull(order.getDeliveredAt());
    }

    @Test
    void transicionesInvalidasLanzanBusinessException() {
        Order order = newOrder();
        assertThrows(BusinessException.class, () -> order.dispatch());
        assertThrows(BusinessException.class, () -> order.deliver());

        order.confirmPayment();
        assertThrows(BusinessException.class, () -> order.confirmPayment());
        assertThrows(BusinessException.class, () -> order.deliver());
    }

    @Test
    void pedidoPendienteOPagadoPuedeCancelarse() {
        Order order = newOrder();
        order.cancel();
        assertEquals(OrderStatus.CANCELLED, order.getStatus());

        Order paid = newOrder();
        paid.confirmPayment();
        paid.cancel();
        assertEquals(OrderStatus.CANCELLED, paid.getStatus());
    }

    @Test
    void pedidoEntregadoNoPuedeCancelarse() {
        Order order = newOrder();
        order.confirmPayment();
        order.dispatch();
        order.deliver();
        assertThrows(BusinessException.class, () -> order.cancel());
    }

    @Test
    void asignarFacturaAlPedido() {
        Order order = newOrder();
        InvoiceId invoiceId = InvoiceId.generate();
        order.assignInvoice(invoiceId);
        assertEquals(invoiceId, order.getInvoiceId());
    }

    @Test
    void pedidoSinItemsEsInvalido() {
        assertThrows(BusinessException.class,
                () -> Order.create(BUYER, java.util.List.of(), ADDRESS));
    }

    @Test
    void pedidoConItemsEnMonedasDistintasEsInvalido() {
        OrderItem usd = OrderItem.of(ProductId.generate(), ProductName.of("Producto A"),
                Quantity.of(1), Money.of(1.00, "USD"), ProductType.PHYSICAL);
        OrderItem cop = OrderItem.of(ProductId.generate(), ProductName.of("Producto B"),
                Quantity.of(1), Money.of(1000.00, "COP"), ProductType.PHYSICAL);
        assertThrows(BusinessException.class,
                () -> Order.create(BUYER, java.util.List.of(usd, cop), ADDRESS));
    }

    @Test
    void totalRecalculaConMultiplesItems() {
        Order order = Order.create(BUYER, java.util.List.of(item(10.00, 2), item(3.50, 3)), ADDRESS);
        assertTrueEqualsOrderTotal(order, new BigDecimal("30.50"));
    }

    private static void assertTrueEqualsOrderTotal(Order order, BigDecimal expected) {
        assertEquals(0, order.getTotal().compareTo(Money.of(expected, "USD")));
    }
}