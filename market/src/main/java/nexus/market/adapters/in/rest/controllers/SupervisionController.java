package nexus.market.adapters.in.rest.controllers;

import java.time.LocalDate;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import nexus.market.adapters.in.rest.PageParams;
import nexus.market.adapters.in.rest.requests.LogisticsRequests.InstructionRequest;
import nexus.market.adapters.in.rest.requests.LogisticsRequests.ResolveIncidentRequest;
import nexus.market.adapters.in.rest.requests.LogisticsRequests.ResponsibleRequest;
import nexus.market.adapters.in.rest.responses.LogisticsResponses.OrderDetailResponse;
import nexus.market.application.AccessPolicy;
import nexus.market.application.LogisticsApplicationService;
import nexus.market.application.LogisticsApplicationService.Page;
import nexus.market.application.LogisticsViews.IncidentView;
import nexus.market.application.LogisticsViews.OrderSummaryView;
import nexus.market.domain.enums.IncidentStatus;
import nexus.market.domain.models.Order;
import nexus.market.domain.ports.in.UserUseCases;
import nexus.market.domain.ports.out.LogisticsRepositoryPort.OrderSearch;
import nexus.market.domain.valueobjects.OrderStatus;
import nexus.market.domain.valueobjects.UserId;

/**
 * Módulo del supervisor: "Supervisión de pedidos". Lectura de todos los pedidos con trabajo logístico y gestión de las novedades
 * (instrucciones, responsable, cierre). No modifica inventario, pagos ni información comercial, ni asigna operadores.
 */
@Tag(name = "Logística - supervisión", description = "Panel de supervisión de pedidos y atención de novedades (SUPERVISOR y ADMIN).")
@RestController
@RequestMapping("/api/supervision")
@Profile("jpa")
public class SupervisionController {

    private final LogisticsApplicationService logistics;
    private final AccessPolicy policy;
    private final UserUseCases users;

    public SupervisionController(LogisticsApplicationService logistics, AccessPolicy policy, UserUseCases users) {
        this.logistics = logistics;
        this.policy = policy;
        this.users = users;
    }

    @Operation(summary = "Panel de supervisión de pedidos",
            description = "SUPERVISOR y ADMIN. Filtros: q (fragmento del código NM-PED-...), operator (correo, documento o UUID), status "
                    + "(PAID, DISPATCHED, DELIVERED), unassigned=true (sin operador), withIncidents (true/false: con o sin novedades "
                    + "sin resolver), pickupsPending (true/false), from y to (fecha de creación AAAA-MM-DD). Paginado; el total va en "
                    + "X-Total-Count. Muestra el operador, las recogidas completadas y pendientes y las novedades abiertas.")
    @GetMapping("/orders")
    public ResponseEntity<List<OrderSummaryView>> orders(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean unassigned,
            @RequestParam(required = false) Boolean withIncidents,
            @RequestParam(required = false) Boolean pickupsPending,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageParams params = PageParams.of(page, size);
        UserId operatorId = operator == null || operator.isBlank() ? null : users.findUserByReference(operator).getUserId();
        OrderSearch criteria = new OrderSearch(PageParams.searchText(q), status(status), operatorId, unassigned,
                withIncidents, pickupsPending, from, to);
        Page<OrderSummaryView> result = logistics.search(criteria, params.offset(), params.size());
        return ResponseEntity.ok().header(PageParams.TOTAL_COUNT_HEADER, String.valueOf(result.total()))
                .body(result.items());
    }

    @Operation(summary = "Detalle de un pedido para supervisión",
            description = "SUPERVISOR y ADMIN. El pedido se indica por UUID o código NM-PED-... Muestra productos, cantidades pedidas y "
                    + "recogidas, vendedor de cada línea, operador asignado, novedades e historial cronológico. No incluye la "
                    + "dirección de entrega.")
    @GetMapping("/orders/{reference}")
    public ResponseEntity<OrderDetailResponse> detail(@PathVariable String reference) {
        Order order = policy.logisticsOrder(reference);
        return ResponseEntity.ok(LogisticsController.response(logistics.detail(order.getOrderId()), order, false));
    }

    @Operation(summary = "Listar novedades",
            description = "SUPERVISOR y ADMIN. Filtro opcional status (OPEN, IN_PROGRESS, RESOLVED). Más recientes primero; paginado.")
    @GetMapping("/incidents")
    public ResponseEntity<List<IncidentView>> incidents(@RequestParam(required = false) String status,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        PageParams params = PageParams.of(page, size);
        IncidentStatus filter = status == null || status.isBlank() ? null : incidentStatus(status);
        Page<IncidentView> result = logistics.listIncidents(filter, params.offset(), params.size());
        return ResponseEntity.ok().header(PageParams.TOTAL_COUNT_HEADER, String.valueOf(result.total()))
                .body(result.items());
    }

    @Operation(summary = "Consultar una novedad", description = "SUPERVISOR y ADMIN. Incluye quién la reportó, quién la atiende y quién la cerró.")
    @GetMapping("/incidents/{incidentId}")
    public ResponseEntity<IncidentView> incident(@PathVariable String incidentId) {
        return ResponseEntity.ok(logistics.incident(incidentId));
    }

    @Operation(summary = "Registrar una instrucción sobre una novedad",
            description = "SUPERVISOR y ADMIN. Deja la instrucción en el historial del pedido y pasa la novedad a IN_PROGRESS. 409 si ya "
                    + "estaba resuelta.")
    @PostMapping("/incidents/{incidentId}/instructions")
    public ResponseEntity<IncidentView> instruct(@PathVariable String incidentId,
                                                 @Valid @RequestBody InstructionRequest request) {
        return ResponseEntity.ok(logistics.addInstruction(incidentId, request.text(), actor(), policy.currentRoleCode()));
    }

    @Operation(summary = "Asignar el responsable del seguimiento de una novedad",
            description = "SUPERVISOR y ADMIN. El responsable (correo, documento o UUID) debe ser personal logístico, supervisor o "
                    + "administrador activo (400 INVALID_RESPONSIBLE).")
    @PostMapping("/incidents/{incidentId}/responsible")
    public ResponseEntity<IncidentView> responsible(@PathVariable String incidentId,
                                                    @Valid @RequestBody ResponsibleRequest request) {
        return ResponseEntity.ok(logistics.assignResponsible(incidentId, request.responsible(), actor(),
                policy.currentRoleCode()));
    }

    @Operation(summary = "Resolver una novedad",
            description = "SUPERVISOR y ADMIN. Cierra la novedad con observaciones de solución; el registro original se conserva con quién "
                    + "la reportó, quién la cerró y cuándo. Una novedad resuelta no se reabre (409).")
    @PostMapping("/incidents/{incidentId}/resolve")
    public ResponseEntity<IncidentView> resolve(@PathVariable String incidentId,
                                                @Valid @RequestBody ResolveIncidentRequest request) {
        return ResponseEntity.ok(logistics.resolveIncident(incidentId, request.notes(), actor(), policy.currentRoleCode()));
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

    private static IncidentStatus incidentStatus(String status) {
        try {
            return IncidentStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("status debe ser OPEN, IN_PROGRESS o RESOLVED.");
        }
    }
}
