package nexus.market.adapters.in.rest.controllers;

import java.time.LocalDate;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import nexus.market.adapters.in.rest.PageParams;
import nexus.market.adapters.in.rest.requests.LogisticsRequests.AssignOperatorRequest;
import nexus.market.adapters.in.rest.requests.LogisticsRequests.RegisterPickupRequest;
import nexus.market.adapters.in.rest.requests.LogisticsRequests.ReportIncidentRequest;
import nexus.market.adapters.in.rest.responses.LogisticsResponses.OrderDetailResponse;
import nexus.market.adapters.in.rest.responses.UserResponses;
import nexus.market.application.AccessPolicy;
import nexus.market.application.LogisticsApplicationService;
import nexus.market.application.LogisticsApplicationService.Page;
import nexus.market.application.LogisticsViews.IncidentView;
import nexus.market.application.LogisticsViews.OrderDetailView;
import nexus.market.application.LogisticsViews.OrderSummaryView;
import nexus.market.domain.models.Order;
import nexus.market.domain.ports.out.LogisticsRepositoryPort.OrderSearch;
import nexus.market.domain.valueobjects.OrderStatus;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.UserId;

/**
 * Módulo del operador logístico: "Gestión de pedidos". El operador solo ve y gestiona los pedidos que un administrador le
 * asignó (un pedido ajeno o inexistente responde igual: 403). El pedido se nombra por su UUID o por su código legible
 * ({@code NM-PED-AAAA-XXXXXX}); el código no concede acceso por sí solo: se exige autenticación y asignación.
 */
@Tag(name = "Logística - operador", description = "Gestión de pedidos del operador logístico: listado, detalle, recogidas y novedades.")
@RestController
@RequestMapping("/api/logistics")
@Profile("jpa")
public class LogisticsController {

    private final LogisticsApplicationService logistics;
    private final AccessPolicy policy;

    public LogisticsController(LogisticsApplicationService logistics, AccessPolicy policy) {
        this.logistics = logistics;
        this.policy = policy;
    }

    @Operation(summary = "Listar mis pedidos asignados",
            description = "Solo OPERADOR logístico: únicamente los pedidos que tiene asignados. Filtros: q (fragmento del código), "
                    + "status (PAID, DISPATCHED, DELIVERED), from y to (fecha de creación, AAAA-MM-DD). Paginado (page, size ≤ 100); "
                    + "el total va en X-Total-Count. No incluye correo ni dirección del comprador.")
    @GetMapping("/orders")
    public ResponseEntity<List<OrderSummaryView>> myOrders(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageParams params = PageParams.of(page, size);
        UserId me = UserId.of(policy.currentUserId().orElseThrow());
        OrderSearch criteria = new OrderSearch(PageParams.searchText(q), status(status), me, false, null, null, from, to);
        Page<OrderSummaryView> result = logistics.search(criteria, params.offset(), params.size());
        return ResponseEntity.ok().header(PageParams.TOTAL_COUNT_HEADER, String.valueOf(result.total()))
                .body(result.items());
    }

    @Operation(summary = "Detalle de un pedido asignado",
            description = "Solo el OPERADOR asignado. Productos con código, vendedor, cantidad pedida, recogida y pendiente, estado de "
                    + "cada recogida, novedades, historial y dirección de entrega. 403 si el pedido es de otro operador o no existe.")
    @GetMapping("/orders/{reference}")
    public ResponseEntity<OrderDetailResponse> detail(@PathVariable String reference) {
        Order order = policy.logisticsOrder(reference);
        return ResponseEntity.ok(response(logistics.detail(order.getOrderId()), order, true));
    }

    @Operation(summary = "Registrar una recogida",
            description = "Solo el OPERADOR asignado. Suma las unidades recogidas de un producto del pedido (nunca más de las pedidas). "
                    + "Solo con el pedido PAID. Errores: 409 PICKUP_EXCEEDS_REQUESTED, 404 ORDER_ITEM_NOT_FOUND.")
    @PostMapping("/orders/{reference}/pickups")
    public ResponseEntity<OrderDetailResponse> registerPickup(@PathVariable String reference,
                                                              @Valid @RequestBody RegisterPickupRequest request) {
        Order order = policy.logisticsOrder(reference);
        return ResponseEntity.ok(response(logistics.registerPickup(order.getOrderId(), ProductId.of(request.productId()),
                request.quantity(), actor(), policy.currentRoleCode()), order, true));
    }

    @Operation(summary = "Reportar una novedad",
            description = "Solo el OPERADOR asignado. Tipos: PICKUP_DELAY, MISSING_PRODUCT, INCOMPLETE_QUANTITY, DAMAGED_PRODUCT, "
                    + "SELLER_WAREHOUSE_ISSUE, TRANSPORT_ISSUE, DELIVERY_DIFFICULTY, OTHER. No modifica cantidades, pagos ni inventario. "
                    + "Mientras haya novedades sin resolver el pedido no se despacha ni se entrega.")
    @ApiResponse(responseCode = "201", description = "Novedad registrada.")
    @PostMapping("/orders/{reference}/incidents")
    public ResponseEntity<IncidentView> reportIncident(@PathVariable String reference,
                                                       @Valid @RequestBody ReportIncidentRequest request) {
        Order order = policy.logisticsOrder(reference);
        ProductId product = request.productId() == null || request.productId().isBlank() ? null
                : ProductId.of(request.productId());
        return ResponseEntity.status(HttpStatus.CREATED).body(logistics.reportIncident(order.getOrderId(), product,
                request.type(), request.description(), actor(), policy.currentRoleCode()));
    }

    @Operation(summary = "Asignar el operador de un pedido",
            description = "Solo ADMIN. El operador se indica por correo, documento o UUID. El pedido debe estar PAID o DISPATCHED y la "
                    + "cuenta debe ser un operador logístico activo (400 INVALID_OPERATOR). Reasignar conserva el historial.")
    @PutMapping("/orders/{reference}/operator")
    public ResponseEntity<OrderDetailResponse> assignOperator(@PathVariable String reference,
                                                              @Valid @RequestBody AssignOperatorRequest request) {
        Order order = policy.logisticsOrder(reference);
        return ResponseEntity.ok(response(logistics.assignOperator(order.getOrderId(), request.operator(), actor(),
                policy.currentRoleCode()), order, false));
    }

    private String actor() {
        return policy.currentUserId().orElseThrow();
    }

    private static OrderStatus status(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return switch (status.trim().toUpperCase()) {
            case "PAID" -> OrderStatus.PAID;
            case "DISPATCHED" -> OrderStatus.DISPATCHED;
            case "DELIVERED" -> OrderStatus.DELIVERED;
            default -> throw new IllegalArgumentException("status debe ser PAID, DISPATCHED o DELIVERED.");
        };
    }

    static OrderDetailResponse response(OrderDetailView detail, Order order, boolean withAddress) {
        return new OrderDetailResponse(detail.order(),
                withAddress ? UserResponses.AddressResponse.of(order.getShippingAddress()) : null,
                detail.shippingId(), detail.shippingStatus(), detail.lines(), detail.incidents(), detail.timeline());
    }
}
