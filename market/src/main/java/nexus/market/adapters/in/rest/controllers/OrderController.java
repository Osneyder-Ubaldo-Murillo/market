package nexus.market.adapters.in.rest.controllers;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Pedidos y pagos", description = "Creación, pago (simulado), despacho, entrega y cancelación de pedidos.")
@RestController
@RequestMapping("/api")
@Profile("jpa")
public class OrderController {

    private final OrderUseCases orderUseCases;
    private final AccessPolicy policy;

    private final nexus.market.application.IdentityDirectory directory;

    public OrderController(OrderUseCases orderUseCases, AccessPolicy policy,
                           nexus.market.application.IdentityDirectory directory) {
        this.orderUseCases = orderUseCases;
        this.policy = policy;
        this.directory = directory;
    }

    /** Pedido con el vendedor y el código de cada línea y el nombre del comprador (el correo, solo a quien corresponde). */
    private OrderResponse view(nexus.market.domain.models.Order order) {
        return RestMappers.order(order,
                directory.productLabels(order.getItems().stream().map(i -> i.getProductId()).toList()),
                directory.buyer(order.getBuyerId()).orElse(null), policy.mayReadBuyerEmail());
    }

    @Operation(summary = "Crear un pedido desde el carrito", description = "Solo COMPRADOR dueño. Un solo pedido puede incluir productos de varios vendedores; reserva el stock.")
    @ApiResponse(responseCode = "201", description = "Pedido creado.")
    @PostMapping("/buyers/{buyerId}/orders")
    public ResponseEntity<OrderResponse> create(@PathVariable String buyerId,
                                                @Valid @RequestBody CreateOrderRequest request) {
        policy.requireOwnBuyer(buyerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(
                orderUseCases.createOrder(BuyerId.of(buyerId), request.shippingAddress().toDomain())));
    }

    /** Pedidos del comprador autenticado, paginados (más recientes primero). Total en la cabecera X-Total-Count. */
    @Operation(summary = "Listar los pedidos del comprador", description = "Solo COMPRADOR dueño. Paginado: page y size (máximo 100); el total va en la cabecera X-Total-Count.")
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
                        .map(this::view).toList());
    }

    @Operation(summary = "Consultar un pedido", description = "COMPRADOR dueño, VENDEDOR, OPERADOR, ADMIN y SUPERVISOR. Un vendedor ve solo sus propias líneas y su subtotal.")
    @GetMapping("/orders/{orderId}")
    public ResponseEntity<?> findById(@PathVariable String orderId) {
        nexus.market.domain.models.Order order = policy.readableOrder(orderId);
        if (policy.hasRole(nexus.market.domain.valueobjects.SystemRole.SELLER)) {
            // El vendedor solo recibe sus ítems y su subtotal (sin comprador, dirección ni factura).
            java.util.List<nexus.market.domain.valueobjects.OrderItem> mine = policy.sellerItems(order);
            return ResponseEntity.ok(RestMappers.orderForSeller(order, mine,
                    directory.productLabels(mine.stream().map(i -> i.getProductId()).toList())));
        }
        return ResponseEntity.ok(view(order));
    }

    @Operation(summary = "Pagar un pedido", description = "Solo COMPRADOR dueño. La pasarela es SIMULADA: no cobra. Responde con un transactionId TXN- y emite la factura.")
    @PostMapping("/orders/{orderId}/payment")
    public ResponseEntity<PaymentResponse> confirmPayment(@PathVariable String orderId,
                                                          @Valid @RequestBody PaymentRequest request) {
        policy.requireOwnOrder(orderId);
        return ResponseEntity.ok(RestMappers.payment(
                orderUseCases.confirmPayment(OrderId.of(orderId), request.paymentMethod())));
    }

    @Operation(summary = "Despachar un pedido", description = "Solo el OPERADOR logístico asignado al pedido. El pedido debe estar pagado, con todo lo físico recogido y sin novedades abiertas.")
    @PostMapping("/orders/{orderId}/dispatch")
    public ResponseEntity<MessageResponse> dispatch(@PathVariable String orderId,
                                                    @Valid @RequestBody DispatchOrderRequest request) {
        policy.requireAssignedOperatorOfOrder(orderId);
        orderUseCases.dispatch(OrderId.of(orderId), WarehouseId.of(request.warehouseId()));
        return ResponseEntity.ok(new MessageResponse("Pedido despachado."));
    }

    @Operation(summary = "Marcar el pedido como entregado", description = "Solo el OPERADOR logístico asignado al pedido. Cuerpo "
            + "opcional con observaciones de la entrega. No avanza si el pedido tiene novedades sin resolver. Queda en el historial "
            + "con el operador y la hora.")
    @PostMapping("/orders/{orderId}/deliver")
    public ResponseEntity<MessageResponse> deliver(@PathVariable String orderId,
                                                   @Valid @RequestBody(required = false) nexus.market.adapters.in.rest.requests.LogisticsRequests.DeliveryRequest request) {
        policy.requireAssignedOperatorOfOrder(orderId);
        orderUseCases.deliver(OrderId.of(orderId), request == null ? null : request.notes());
        return ResponseEntity.ok(new MessageResponse("Pedido entregado."));
    }

    @Operation(summary = "Cancelar un pedido", description = "Solo COMPRADOR dueño. Si ya estaba pagado, reembolsa y repone el stock. Sin cuerpo.")
    @PostMapping("/orders/{orderId}/cancel")
    public ResponseEntity<MessageResponse> cancel(@PathVariable String orderId) {
        policy.requireOwnOrder(orderId);
        orderUseCases.cancel(OrderId.of(orderId));
        return ResponseEntity.ok(new MessageResponse("Pedido cancelado."));
    }
}
