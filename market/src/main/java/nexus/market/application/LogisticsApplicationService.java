package nexus.market.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import nexus.market.application.IdentityDirectory.ProductLabel;
import nexus.market.application.LogisticsViews.EventView;
import nexus.market.application.LogisticsViews.IncidentView;
import nexus.market.application.LogisticsViews.OrderDetailView;
import nexus.market.application.LogisticsViews.OrderSummaryView;
import nexus.market.application.LogisticsViews.PersonRef;
import nexus.market.application.LogisticsViews.PickupLineView;
import nexus.market.domain.enums.IncidentStatus;
import nexus.market.domain.enums.IncidentType;
import nexus.market.domain.enums.OrderEventType;
import nexus.market.domain.enums.PickupStatus;
import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.models.Order;
import nexus.market.domain.models.OrderIncident;
import nexus.market.domain.models.OrderPickup;
import nexus.market.domain.models.Shipping;
import nexus.market.domain.ports.in.UserUseCases;
import nexus.market.domain.ports.out.LogisticsRepositoryPort;
import nexus.market.domain.ports.out.LogisticsRepositoryPort.OrderSearch;
import nexus.market.domain.ports.out.OrderRepositoryPort;
import nexus.market.domain.ports.out.ShippingRepositoryPort;
import nexus.market.domain.services.LogisticsService;
import nexus.market.domain.services.LogisticsService.PickupLine;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.OrderItem;
import nexus.market.domain.valueobjects.OrderStatus;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.UserId;

/**
 * Casos de uso de la gestión logística: paneles del operador y del supervisor, recogidas, novedades y asignación. Arma las
 * vistas con nombres legibles (comprador, vendedores, operador) sin exponer más datos personales de los necesarios. Quién puede
 * invocar cada operación lo decide el controlador con {@code AccessPolicy}.
 */
@Service
@Profile("jpa")
public class LogisticsApplicationService {

    /** Una página de resultados con el total (para la cabecera X-Total-Count). */
    public record Page<T>(List<T> items, long total) {
    }

    private final LogisticsService logistics;
    private final LogisticsRepositoryPort repository;
    private final OrderRepositoryPort orders;
    private final ShippingRepositoryPort shippings;
    private final IdentityDirectory directory;
    private final UserUseCases users;

    public LogisticsApplicationService(LogisticsService logistics, LogisticsRepositoryPort repository,
                                       OrderRepositoryPort orders, ShippingRepositoryPort shippings,
                                       IdentityDirectory directory, UserUseCases users) {
        this.logistics = Objects.requireNonNull(logistics, "logistics es obligatorio");
        this.repository = Objects.requireNonNull(repository, "repository es obligatorio");
        this.orders = Objects.requireNonNull(orders, "orders es obligatorio");
        this.shippings = Objects.requireNonNull(shippings, "shippings es obligatorio");
        this.directory = Objects.requireNonNull(directory, "directory es obligatorio");
        this.users = Objects.requireNonNull(users, "users es obligatorio");
    }

    // ------------------------------------------------------------------
    // Consultas
    // ------------------------------------------------------------------

    public Page<OrderSummaryView> search(OrderSearch criteria, int offset, int size) {
        List<Order> found = repository.searchOrders(criteria, offset, size);
        return new Page<>(summaries(found), repository.countOrders(criteria));
    }

    public OrderDetailView detail(OrderId orderId) {
        return buildDetail(loadOrder(orderId));
    }

    public Page<IncidentView> listIncidents(IncidentStatus status, int offset, int size) {
        List<OrderIncident> found = repository.listIncidents(status, offset, size);
        return new Page<>(incidentViews(found), repository.countIncidents(status));
    }

    public IncidentView incident(String incidentId) {
        OrderIncident incident = repository.findIncident(incidentId)
                .orElseThrow(() -> new BusinessException("INCIDENT_NOT_FOUND", "Novedad inexistente."));
        return incidentViews(List.of(incident)).get(0);
    }

    // ------------------------------------------------------------------
    // Operaciones
    // ------------------------------------------------------------------

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderDetailView assignOperator(OrderId orderId, String operatorReference, String actorId, String actorRole) {
        UserId operator = users.findUserByReference(operatorReference).getUserId();
        logistics.assignOperator(orderId, operator, actorId, actorRole);
        return buildDetail(loadOrder(orderId));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderDetailView registerPickup(OrderId orderId, ProductId productId, int quantity, String actorId,
                                          String actorRole) {
        logistics.registerPickup(orderId, productId, quantity, actorId, actorRole);
        return buildDetail(loadOrder(orderId));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public IncidentView reportIncident(OrderId orderId, ProductId productId, IncidentType type, String description,
                                       String actorId, String actorRole) {
        OrderIncident incident = logistics.reportIncident(orderId, productId, type, description, actorId, actorRole);
        return incidentViews(List.of(incident)).get(0);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public IncidentView addInstruction(String incidentId, String text, String actorId, String actorRole) {
        return incidentViews(List.of(logistics.addInstruction(incidentId, text, actorId, actorRole))).get(0);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public IncidentView assignResponsible(String incidentId, String responsibleReference, String actorId,
                                          String actorRole) {
        UserId responsible = users.findUserByReference(responsibleReference).getUserId();
        return incidentViews(List.of(logistics.assignResponsible(incidentId, responsible, actorId, actorRole))).get(0);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public IncidentView resolveIncident(String incidentId, String notes, String actorId, String actorRole) {
        return incidentViews(List.of(logistics.resolveIncident(incidentId, notes, actorId, actorRole))).get(0);
    }

    /** Deja constancia en el historial de un paso del envío (despacho, tránsito, entrega). */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void recordTransition(OrderId orderId, OrderEventType type, String actorId, String actorRole, String detail) {
        logistics.record(orderId, null, type, actorId, actorRole, detail);
    }

    // ------------------------------------------------------------------
    // Armado de vistas
    // ------------------------------------------------------------------

    private Order loadOrder(OrderId orderId) {
        return orders.findById(orderId)
                .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Pedido inexistente."));
    }

    private OrderDetailView buildDetail(Order order) {
        OrderSummaryView summary = summaries(List.of(order)).get(0);
        Optional<Shipping> shipping = shippings.findByOrderId(order.getOrderId());
        List<PickupLineView> lines = lineViews(order, repository.findPickups(order.getOrderId()));
        List<IncidentView> incidents = incidentViews(repository.findIncidents(order.getOrderId()));
        List<nexus.market.domain.models.OrderEvent> events = repository.findEvents(order.getOrderId());
        Map<String, String> names = directory.userNames(events.stream().map(e -> e.actorId()).toList());
        List<EventView> timeline = events.stream().map(e -> new EventView(e.type().name(), e.incidentId(),
                e.actorId() == null ? null : new PersonRef(e.actorId(), names.get(e.actorId())), e.actorRole(),
                e.detail(), e.createdAt())).toList();
        return new OrderDetailView(summary, shipping.map(s -> s.getShippingId().value()).orElse(null),
                shipping.map(s -> s.getStatus().getCode()).orElse(null), lines, incidents, timeline);
    }

    private List<PickupLineView> lineViews(Order order, List<OrderPickup> pickups) {
        Map<String, ProductLabel> labels = directory.productLabels(
                order.getItems().stream().map(OrderItem::getProductId).toList());
        return LogisticsService.pickupLines(order, pickups).stream().map(line -> {
            ProductLabel label = labels.get(line.productId().value());
            return new PickupLineView(line.productId().value(), line.productName(),
                    label == null ? null : label.sellerSku(), label == null ? null : label.sellerId(),
                    label == null ? null : label.sellerName(), line.requested(), line.picked(), line.pending(),
                    line.status().name());
        }).toList();
    }

    private List<OrderSummaryView> summaries(List<Order> found) {
        List<OrderId> ids = found.stream().map(Order::getOrderId).toList();
        Map<String, List<OrderPickup>> pickups = repository.findPickups(ids);
        Map<String, List<OrderIncident>> incidents = repository.findIncidents(ids);
        Map<String, ProductLabel> labels = directory.productLabels(found.stream()
                .flatMap(o -> o.getItems().stream()).map(OrderItem::getProductId).toList());
        Map<String, String> operatorNames = directory.userNames(found.stream()
                .filter(o -> o.getAssignedOperatorId() != null).map(o -> o.getAssignedOperatorId().value()).toList());
        List<OrderSummaryView> views = new ArrayList<>();
        for (Order order : found) {
            String id = order.getOrderId().value();
            List<PickupLine> lines = LogisticsService.pickupLines(order, pickups.getOrDefault(id, List.of()));
            int open = (int) incidents.getOrDefault(id, List.of()).stream().filter(i -> i.getStatus().isPending()).count();
            Shipping shipping = shippings.findByOrderId(order.getOrderId()).orElse(null);
            String stage = stage(order, shipping, lines);
            Set<String> sellers = new LinkedHashSet<>();
            for (OrderItem item : order.getItems()) {
                ProductLabel label = labels.get(item.getProductId().value());
                if (label != null) {
                    sellers.add(label.sellerName() != null ? label.sellerName() : label.sellerId());
                }
            }
            String buyerName = directory.buyer(order.getBuyerId()).map(p -> p.fullName()).orElse(null);
            int unitsRequested = lines.stream().mapToInt(PickupLine::requested).sum();
            int unitsPicked = lines.stream().mapToInt(PickupLine::picked).sum();
            int linesPicked = (int) lines.stream().filter(l -> l.status() == PickupStatus.PICKED).count();
            PersonRef operator = order.getAssignedOperatorId() == null ? null
                    : new PersonRef(order.getAssignedOperatorId().value(),
                            operatorNames.get(order.getAssignedOperatorId().value()));
            views.add(new OrderSummaryView(id, order.getOrderCode() == null ? null : order.getOrderCode().value(),
                    order.getStatus().getCode(), stage, order.getCreatedAt(), order.getUpdatedAt(), buyerName,
                    order.getItems().size(), order.getItems().stream().mapToInt(i -> i.getQuantity().value()).sum(),
                    List.copyOf(sellers), productsSummary(order), linesPicked, lines.size() - linesPicked,
                    unitsRequested, unitsPicked, open, operator, order.getAssignedAt(),
                    actions(order, stage, open)));
        }
        return views;
    }

    private static String stage(Order order, Shipping shipping, List<PickupLine> lines) {
        if (order.getStatus() == OrderStatus.DELIVERED) {
            return "DELIVERED";
        }
        if (shipping != null) {
            return shipping.getStatus().getCode();
        }
        if (order.getStatus() == OrderStatus.DISPATCHED) {
            return "DISPATCHED";
        }
        if (lines.stream().allMatch(l -> l.pending() == 0)) {
            return "PICKED";
        }
        return lines.stream().anyMatch(l -> l.picked() > 0) ? "PARTIALLY_PICKED" : "PENDING_PICKUP";
    }

    /** Acciones que el operador asignado puede intentar ahora (el servidor vuelve a validar cada una). */
    private static List<String> actions(Order order, String stage, int openIncidents) {
        List<String> actions = new ArrayList<>();
        boolean blocked = openIncidents > 0;
        switch (stage) {
            case "PENDING_PICKUP", "PARTIALLY_PICKED" -> actions.add("REGISTER_PICKUP");
            case "PICKED" -> {
                if (!blocked) {
                    actions.add("CREATE_SHIPPING");
                }
            }
            case "PREPARING" -> {
                if (!blocked) {
                    actions.add("DISPATCH_SHIPPING");
                }
            }
            case "DISPATCHED" -> actions.add("MARK_IN_TRANSIT");
            case "IN_TRANSIT" -> {
                if (!blocked) {
                    actions.add("CONFIRM_DELIVERY");
                }
            }
            default -> { }
        }
        if (order.getStatus() == OrderStatus.PAID || order.getStatus() == OrderStatus.DISPATCHED) {
            actions.add("REPORT_INCIDENT");
        }
        return actions;
    }

    private static String productsSummary(Order order) {
        String text = order.getItems().stream()
                .map(i -> i.getQuantity().value() + " x " + i.getProductName().getValue())
                .collect(Collectors.joining(", "));
        return text.length() <= 200 ? text : text.substring(0, 197) + "...";
    }

    private List<IncidentView> incidentViews(List<OrderIncident> found) {
        Set<String> userIds = new LinkedHashSet<>();
        for (OrderIncident i : found) {
            userIds.add(i.getReportedBy());
            if (i.getResponsibleId() != null) {
                userIds.add(i.getResponsibleId());
            }
            if (i.getResolvedBy() != null) {
                userIds.add(i.getResolvedBy());
            }
        }
        Map<String, String> names = directory.userNames(userIds);
        Map<String, String> codes = new java.util.HashMap<>();
        Map<String, String> productNames = new java.util.HashMap<>();
        for (OrderIncident i : found) {
            orders.findById(i.getOrderId()).ifPresent(order -> {
                codes.put(order.getOrderId().value(), order.getOrderCode() == null ? null : order.getOrderCode().value());
                if (i.getProductId() != null) {
                    order.getItems().stream().filter(item -> item.getProductId().equals(i.getProductId())).findFirst()
                            .ifPresent(item -> productNames.put(i.getIncidentId(), item.getProductName().getValue()));
                }
            });
        }
        return found.stream().map(i -> new IncidentView(i.getIncidentId(), i.getOrderId().value(),
                codes.get(i.getOrderId().value()), i.getProductId() == null ? null : i.getProductId().value(),
                productNames.get(i.getIncidentId()), i.getType().name(), i.getDescription(), i.getStatus().name(),
                ref(i.getReportedBy(), names), i.getReportedAt(), ref(i.getResponsibleId(), names),
                i.getResolutionNotes(), ref(i.getResolvedBy(), names), i.getResolvedAt(), i.getUpdatedAt())).toList();
    }

    private static PersonRef ref(String userId, Map<String, String> names) {
        return userId == null ? null : new PersonRef(userId, names.get(userId));
    }
}
