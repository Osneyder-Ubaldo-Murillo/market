package nexus.market.adapters.in.rest.controllers;

import nexus.market.application.AccessPolicy;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import nexus.market.adapters.in.rest.PageParams;
import nexus.market.adapters.in.rest.mappers.RestMappers;
import nexus.market.adapters.in.rest.requests.OrderRequests.CreateOrderRequest;
import nexus.market.adapters.in.rest.requests.OrderRequests.DispatchOrderRequest;
import nexus.market.adapters.in.rest.requests.OrderRequests.PaymentRequest;
import nexus.market.adapters.in.rest.responses.CommerceResponses.OrderResponse;
import nexus.market.adapters.in.rest.responses.CommerceResponses.PaymentResponse;
import nexus.market.adapters.in.rest.responses.UserResponses.MessageResponse;
import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.ports.in.OrderUseCases;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * Controlador REST del ciclo de compra: creación del pedido desde el carrito,
 * confirmación de pago (pasarela), despacho, entrega, cancelación y consulta.
 */
@RestController
@RequestMapping("/api")
@Profile("jpa")
public class OrderController {

    private final OrderUseCases orderUseCases;
    private final AccessPolicy policy;

    public OrderController(OrderUseCases orderUseCases, AccessPolicy policy) {
        this.orderUseCases = orderUseCases;
        this.policy = policy;
    }

    @PostMapping("/buyers/{buyerId}/orders")
    public ResponseEntity<OrderResponse> create(@PathVariable String buyerId,
                                                @Valid @RequestBody CreateOrderRequest request) {
        policy.requireOwnBuyer(buyerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(RestMappers.order(
                orderUseCases.createOrder(BuyerId.of(buyerId), request.shippingAddress().toDomain())));
    }

    /** Pedidos del comprador autenticado, paginados (más recientes primero). Total en la cabecera X-Total-Count. */
    @GetMapping("/buyers/{buyerId}/orders")
    public ResponseEntity<java.util.List<OrderResponse>> listByBuyer(
            @PathVariable String buyerId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size) {
        policy.requireOwnBuyer(buyerId);
        PageParams params = PageParams.of(page, size);
        BuyerId id = BuyerId.of(buyerId);
        return ResponseEntity.ok()
                .header(PageParams.TOTAL_COUNT_HEADER, String.valueOf(orderUseCases.countByBuyer(id)))
                .body(orderUseCases.findByBuyer(id, params.offset(), params.size()).stream()
                        .map(RestMappers::order).toList());
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<?> findById(@PathVariable String orderId) {
        nexus.market.domain.models.Order order = policy.readableOrder(orderId);
        if (policy.hasRole(nexus.market.domain.valueobjects.SystemRole.SELLER)) {
            // El vendedor solo recibe sus ítems y su subtotal (sin comprador, dirección ni factura).
            return ResponseEntity.ok(RestMappers.orderForSeller(order, policy.sellerItems(order)));
        }
        return ResponseEntity.ok(RestMappers.order(order));
    }

    @PostMapping("/orders/{orderId}/payment")
    public ResponseEntity<PaymentResponse> confirmPayment(@PathVariable String orderId,
                                                          @Valid @RequestBody PaymentRequest request) {
        policy.requireOwnOrder(orderId);
        return ResponseEntity.ok(RestMappers.payment(
                orderUseCases.confirmPayment(OrderId.of(orderId), request.paymentMethod())));
    }

    @PostMapping("/orders/{orderId}/dispatch")
    public ResponseEntity<MessageResponse> dispatch(@PathVariable String orderId,
                                                    @Valid @RequestBody DispatchOrderRequest request) {
        orderUseCases.dispatch(OrderId.of(orderId), WarehouseId.of(request.warehouseId()));
        return ResponseEntity.ok(new MessageResponse("Pedido despachado."));
    }

    @PostMapping("/orders/{orderId}/deliver")
    public ResponseEntity<MessageResponse> deliver(@PathVariable String orderId) {
        orderUseCases.deliver(OrderId.of(orderId));
        return ResponseEntity.ok(new MessageResponse("Pedido entregado."));
    }

    @PostMapping("/orders/{orderId}/cancel")
    public ResponseEntity<MessageResponse> cancel(@PathVariable String orderId) {
        policy.requireOwnOrder(orderId);
        orderUseCases.cancel(OrderId.of(orderId));
        return ResponseEntity.ok(new MessageResponse("Pedido cancelado."));
    }
}
