package nexus.market.domain.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import nexus.market.domain.enums.AuditSeverity;
import nexus.market.domain.enums.IncidentType;
import nexus.market.domain.enums.OrderEventType;
import nexus.market.domain.enums.PickupStatus;
import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.models.Order;
import nexus.market.domain.models.OrderEvent;
import nexus.market.domain.models.OrderIncident;
import nexus.market.domain.models.OrderPickup;
import nexus.market.domain.models.User;
import nexus.market.domain.ports.out.LogisticsRepositoryPort;
import nexus.market.domain.ports.out.OrderRepositoryPort;
import nexus.market.domain.ports.out.UserRepositoryPort;
import nexus.market.domain.valueobjects.OperationType;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.OrderItem;
import nexus.market.domain.valueobjects.OrderStatus;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.ProductType;
import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.UserId;
import nexus.market.domain.valueobjects.UserStatus;

/**
 * Reglas de la gestión logística de un pedido: asignación del operador, recogidas por producto, novedades con su
 * seguimiento e historial. No toca cantidades del pedido, pagos ni inventario: una novedad solo se registra y se atiende.
 * Quién puede invocar cada operación lo decide la capa REST ({@code AccessPolicy}); aquí solo se validan las reglas de negocio.
 */
public class LogisticsService {

    /** Una línea física del pedido con lo pedido y lo recogido hasta ahora. */
    public record PickupLine(ProductId productId, String productName, int requested, int picked) {
        public int pending() {
            return requested - picked;
        }

        public PickupStatus status() {
            return PickupStatus.of(requested, picked);
        }
    }

    private final LogisticsRepositoryPort logistics;
    private final OrderRepositoryPort orders;
    private final UserRepositoryPort users;
    private final AuditService audit;

    public LogisticsService(LogisticsRepositoryPort logistics, OrderRepositoryPort orders, UserRepositoryPort users,
                            AuditService audit) {
        this.logistics = Objects.requireNonNull(logistics, "logistics es obligatorio");
        this.orders = Objects.requireNonNull(orders, "orders es obligatorio");
        this.users = Objects.requireNonNull(users, "users es obligatorio");
        this.audit = Objects.requireNonNull(audit, "audit es obligatorio");
    }

    // ------------------------------------------------------------------
    // Asignación
    // ------------------------------------------------------------------

    /** Asigna (o reasigna) el operador responsable. El usuario debe ser un operador logístico activo. */
    public Order assignOperator(OrderId orderId, UserId operatorId, String actorId, String actorRole) {
        Order order = loadOrder(orderId);
        User operator = users.findById(operatorId)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "Usuario inexistente."));
        if (!SystemRole.LOGISTICS_OPERATOR.equals(operator.getRole())) {
            throw new BusinessException("INVALID_OPERATOR", "La cuenta indicada no es un operador logístico.");
        }
        if (!UserStatus.ACTIVE.equals(operator.getStatus())) {
            throw new BusinessException("INVALID_OPERATOR", "El operador logístico no está activo.");
        }
        order.assignOperator(operatorId);
        orders.save(order);
        record(order.getOrderId(), null, OrderEventType.OPERATOR_ASSIGNED, actorId, actorRole,
                "Operador asignado: " + operator.getFullName().getValue());
        audit.record(OperationType.ORDER_OPERATOR_ASSIGN, AuditSeverity.INFO, actorId,
                "Pedido " + order.getOrderCode() + " asignado al operador " + operatorId);
        return order;
    }

    // ------------------------------------------------------------------
    // Recogidas
    // ------------------------------------------------------------------

    /** Líneas físicas del pedido con su avance de recogida. Los productos digitales no se recogen. */
    public List<PickupLine> pickupLines(Order order) {
        return pickupLines(order, logistics.findPickups(order.getOrderId()));
    }

    /** Igual que {@link #pickupLines(Order)} con las recogidas ya cargadas (para armar listados sin una consulta por pedido). */
    public static List<PickupLine> pickupLines(Order order, List<OrderPickup> picked) {
        List<PickupLine> lines = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            if (item.getProductType() != ProductType.PHYSICAL) {
                continue;
            }
            int done = picked.stream().filter(p -> p.productId().equals(item.getProductId()))
                    .mapToInt(OrderPickup::pickedQuantity).sum();
            lines.add(new PickupLine(item.getProductId(), item.getProductName().getValue(),
                    item.getQuantity().value(), Math.min(done, item.getQuantity().value())));
        }
        return lines;
    }

    /**
     * Registra unidades recogidas de un producto del pedido (se acumulan; nunca superan lo pedido). Solo mientras el
     * pedido está pagado y aún no despachado.
     */
    public OrderPickup registerPickup(OrderId orderId, ProductId productId, int quantity, String actorId,
                                      String actorRole) {
        Objects.requireNonNull(productId, "productId es obligatorio");
        if (quantity <= 0) {
            throw new IllegalArgumentException("La cantidad recogida debe ser un entero mayor que cero");
        }
        Order order = loadOrder(orderId);
        if (order.getStatus() != OrderStatus.PAID) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "Solo se registran recogidas de un pedido PAID (actual: " + order.getStatus().getCode() + ").");
        }
        PickupLine line = pickupLines(order).stream().filter(l -> l.productId().equals(productId)).findFirst()
                .orElseThrow(() -> new BusinessException("ORDER_ITEM_NOT_FOUND",
                        "El producto no es una línea física de este pedido."));
        if (quantity > line.pending()) {
            throw new BusinessException("PICKUP_EXCEEDS_REQUESTED", "Se pidieron " + line.requested() + " unidades, ya se"
                    + " recogieron " + line.picked() + " y no se pueden registrar " + quantity + " más.");
        }
        OrderPickup saved = logistics.savePickup(new OrderPickup(orderId, productId, line.picked() + quantity, actorId,
                java.time.LocalDateTime.now()));
        record(orderId, null, OrderEventType.PICKUP_REGISTERED, actorId, actorRole, "Recogidas " + quantity + " de "
                + line.productName() + " (" + saved.pickedQuantity() + "/" + line.requested() + ")");
        audit.record(OperationType.PICKUP_REGISTER, AuditSeverity.INFO, actorId,
                "Recogida en pedido " + order.getOrderCode() + ": " + line.productName() + " " + saved.pickedQuantity()
                        + "/" + line.requested());
        return saved;
    }

    /** No se despacha con unidades físicas por recoger ni con novedades sin resolver. */
    public void requireReadyToDispatch(Order order) {
        for (PickupLine line : pickupLines(order)) {
            if (line.pending() > 0) {
                throw new BusinessException("PICKUP_INCOMPLETE", "Faltan por recoger " + line.pending() + " unidades de "
                        + line.productName() + "; el pedido no puede despacharse.");
            }
        }
        requireNoPendingIncidents(order.getOrderId());
    }

    /** Un pedido con novedades sin resolver no avanza hasta que el supervisor las cierre. */
    public void requireNoPendingIncidents(OrderId orderId) {
        long pending = logistics.countPendingIncidents(orderId);
        if (pending > 0) {
            throw new BusinessException("INCIDENT_OPEN", "El pedido tiene " + pending
                    + " novedad(es) sin resolver; deben cerrarse antes de continuar.");
        }
    }

    // ------------------------------------------------------------------
    // Novedades
    // ------------------------------------------------------------------

    public OrderIncident reportIncident(OrderId orderId, ProductId productId, IncidentType type, String description,
                                        String actorId, String actorRole) {
        Objects.requireNonNull(type, "type es obligatorio");
        Order order = loadOrder(orderId);
        if (order.getStatus() != OrderStatus.PAID && order.getStatus() != OrderStatus.DISPATCHED) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "Solo se reportan novedades de un pedido PAID o DISPATCHED (actual: " + order.getStatus().getCode() + ").");
        }
        if (productId != null && order.getItems().stream().noneMatch(i -> i.getProductId().equals(productId))) {
            throw new BusinessException("ORDER_ITEM_NOT_FOUND", "El producto no pertenece a este pedido.");
        }
        OrderIncident incident = logistics.saveIncident(
                OrderIncident.report(orderId, productId, type, description, actorId));
        record(orderId, incident.getIncidentId(), OrderEventType.INCIDENT_REPORTED, actorId, actorRole,
                type + ": " + incident.getDescription());
        audit.record(OperationType.INCIDENT_UPDATE, AuditSeverity.WARNING, actorId,
                "Novedad " + type + " en pedido " + order.getOrderCode());
        return incident;
    }

    public OrderIncident addInstruction(String incidentId, String text, String actorId, String actorRole) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("La instrucción es obligatoria");
        }
        OrderIncident incident = loadIncident(incidentId);
        incident.startFollowUp();
        logistics.saveIncident(incident);
        record(incident.getOrderId(), incidentId, OrderEventType.INCIDENT_INSTRUCTION, actorId, actorRole, text.trim());
        audit.record(OperationType.INCIDENT_UPDATE, AuditSeverity.INFO, actorId, "Instrucción sobre la novedad " + incidentId);
        return incident;
    }

    public OrderIncident assignResponsible(String incidentId, UserId responsibleId, String actorId, String actorRole) {
        User responsible = users.findById(responsibleId)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "Usuario inexistente."));
        boolean staff = SystemRole.LOGISTICS_OPERATOR.equals(responsible.getRole())
                || SystemRole.SUPERVISOR.equals(responsible.getRole()) || SystemRole.ADMIN.equals(responsible.getRole());
        if (!staff || !UserStatus.ACTIVE.equals(responsible.getStatus())) {
            throw new BusinessException("INVALID_RESPONSIBLE",
                    "El responsable debe ser personal logístico, supervisor o administrador activo.");
        }
        OrderIncident incident = loadIncident(incidentId);
        incident.assignResponsible(responsibleId.value());
        logistics.saveIncident(incident);
        record(incident.getOrderId(), incidentId, OrderEventType.INCIDENT_RESPONSIBLE_ASSIGNED, actorId, actorRole,
                "Responsable: " + responsible.getFullName().getValue());
        audit.record(OperationType.INCIDENT_UPDATE, AuditSeverity.INFO, actorId,
                "Responsable asignado a la novedad " + incidentId);
        return incident;
    }

    public OrderIncident resolveIncident(String incidentId, String notes, String actorId, String actorRole) {
        OrderIncident incident = loadIncident(incidentId);
        incident.resolve(actorId, notes);
        logistics.saveIncident(incident);
        record(incident.getOrderId(), incidentId, OrderEventType.INCIDENT_RESOLVED, actorId, actorRole,
                incident.getResolutionNotes());
        audit.record(OperationType.INCIDENT_UPDATE, AuditSeverity.INFO, actorId, "Novedad resuelta " + incidentId);
        return incident;
    }

    // ------------------------------------------------------------------
    // Historial
    // ------------------------------------------------------------------

    /** Agrega una entrada al historial del pedido (no guardar contraseñas, tokens ni datos sensibles en el detalle). */
    public void record(OrderId orderId, String incidentId, OrderEventType type, String actorId, String actorRole,
                       String detail) {
        logistics.addEvent(OrderEvent.of(orderId, incidentId, type, actorId, actorRole, detail));
    }

    private Order loadOrder(OrderId orderId) {
        return orders.findById(orderId)
                .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Pedido inexistente."));
    }

    private OrderIncident loadIncident(String incidentId) {
        return logistics.findIncident(incidentId)
                .orElseThrow(() -> new BusinessException("INCIDENT_NOT_FOUND", "Novedad inexistente."));
    }
}
