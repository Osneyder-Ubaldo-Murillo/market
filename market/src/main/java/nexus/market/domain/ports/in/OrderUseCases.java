package nexus.market.domain.ports.in;

import java.util.Optional;

import nexus.market.domain.models.Order;
import nexus.market.domain.valueobjects.Address;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.PaymentConfirmation;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * Puerto de entrada (caso de uso) del ciclo de compra. Delega en
 * {@code OrderService}: creación desde el carrito, pago (pasarela),
 * despacho, entrega y cancelación.
 */
public interface OrderUseCases {

    Order createOrder(BuyerId buyerId, Address shippingAddress);

    PaymentConfirmation confirmPayment(OrderId orderId, String paymentMethod);

    void dispatch(OrderId orderId, WarehouseId warehouseId);

    void deliver(OrderId orderId);

    void cancel(OrderId orderId);

    Optional<Order> getOrder(OrderId orderId);
}
