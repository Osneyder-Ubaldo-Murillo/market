package nexus.market.adapters.out.persistence.mysql;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import nexus.market.domain.enums.IncidentStatus;
import nexus.market.domain.enums.IncidentType;
import nexus.market.domain.enums.OrderEventType;
import nexus.market.domain.models.Order;
import nexus.market.domain.models.OrderEvent;
import nexus.market.domain.models.OrderIncident;
import nexus.market.domain.models.OrderPickup;
import nexus.market.domain.ports.out.LogisticsRepositoryPort;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.ProductId;

/** Unidades recogidas de una línea de pedido (tabla {@code order_pickups}). */
@Entity
@Table(name = "order_pickups")
class OrderPickupEntity {

    @Id
    @Column(name = "pickup_id", length = 36, updatable = false)
    String pickupId;

    @Column(name = "order_id", length = 36, nullable = false)
    String orderId;

    @Column(name = "product_id", length = 36, nullable = false)
    String productId;

    @Column(name = "picked_quantity", nullable = false)
    int pickedQuantity;

    @Column(name = "updated_by", length = 36)
    String updatedBy;

    @Column(name = "updated_at", nullable = false)
    LocalDateTime updatedAt;

    protected OrderPickupEntity() {
    }

    static OrderPickupEntity from(OrderPickup pickup) {
        OrderPickupEntity entity = new OrderPickupEntity();
        // Clave determinista por línea: guardar de nuevo la misma línea actualiza la fila, nunca la duplica.
        entity.pickupId = UUID.nameUUIDFromBytes(
                (pickup.orderId().value() + ":" + pickup.productId().value()).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
        entity.orderId = pickup.orderId().value();
        entity.productId = pickup.productId().value();
        entity.pickedQuantity = pickup.pickedQuantity();
        entity.updatedBy = pickup.updatedBy();
        entity.updatedAt = pickup.updatedAt();
        return entity;
    }

    OrderPickup toDomain() {
        return new OrderPickup(OrderId.of(orderId), ProductId.of(productId), pickedQuantity, updatedBy, updatedAt);
    }
}

/** Novedad logística de un pedido (tabla {@code order_incidents}). */
@Entity
@Table(name = "order_incidents")
class OrderIncidentEntity {

    @Id
    @Column(name = "incident_id", length = 36, updatable = false)
    String incidentId;

    @Column(name = "order_id", length = 36, nullable = false, updatable = false)
    String orderId;

    @Column(name = "product_id", length = 36, updatable = false)
    String productId;

    @Column(name = "type", length = 40, nullable = false, updatable = false)
    String type;

    @Column(name = "description", length = 1000, nullable = false, updatable = false)
    String description;

    @Column(name = "reported_by", length = 36, nullable = false, updatable = false)
    String reportedBy;

    @Column(name = "reported_at", nullable = false, updatable = false)
    LocalDateTime reportedAt;

    @Column(name = "status", length = 20, nullable = false)
    String status;

    @Column(name = "responsible_id", length = 36)
    String responsibleId;

    @Column(name = "resolution_notes", length = 1000)
    String resolutionNotes;

    @Column(name = "resolved_by", length = 36)
    String resolvedBy;

    @Column(name = "resolved_at")
    LocalDateTime resolvedAt;

    @Column(name = "updated_at", nullable = false)
    LocalDateTime updatedAt;

    protected OrderIncidentEntity() {
    }

    static OrderIncidentEntity from(OrderIncident incident) {
        OrderIncidentEntity entity = new OrderIncidentEntity();
        entity.incidentId = incident.getIncidentId();
        entity.orderId = incident.getOrderId().value();
        entity.productId = incident.getProductId() == null ? null : incident.getProductId().value();
        entity.type = incident.getType().name();
        entity.description = incident.getDescription();
        entity.reportedBy = incident.getReportedBy();
        entity.reportedAt = incident.getReportedAt();
        entity.status = incident.getStatus().name();
        entity.responsibleId = incident.getResponsibleId();
        entity.resolutionNotes = incident.getResolutionNotes();
        entity.resolvedBy = incident.getResolvedBy();
        entity.resolvedAt = incident.getResolvedAt();
        entity.updatedAt = incident.getUpdatedAt();
        return entity;
    }

    OrderIncident toDomain() {
        return new OrderIncident(incidentId, OrderId.of(orderId), productId == null ? null : ProductId.of(productId),
                IncidentType.valueOf(type), description, reportedBy, reportedAt, IncidentStatus.valueOf(status),
                responsibleId, resolutionNotes, resolvedBy, resolvedAt, updatedAt);
    }
}

/** Entrada del historial de un pedido (tabla {@code order_events}); solo se insertan filas. */
@Entity
@Table(name = "order_events")
class OrderEventEntity {

    @Id
    @Column(name = "event_id", length = 36, updatable = false)
    String eventId;

    @Column(name = "order_id", length = 36, nullable = false, updatable = false)
    String orderId;

    @Column(name = "incident_id", length = 36, updatable = false)
    String incidentId;

    @Column(name = "event_type", length = 40, nullable = false, updatable = false)
    String eventType;

    @Column(name = "actor_id", length = 36, updatable = false)
    String actorId;

    @Column(name = "actor_role", length = 30, updatable = false)
    String actorRole;

    @Column(name = "detail", length = 1000, updatable = false)
    String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    LocalDateTime createdAt;

    protected OrderEventEntity() {
    }

    static OrderEventEntity from(OrderEvent event) {
        OrderEventEntity entity = new OrderEventEntity();
        entity.eventId = event.eventId();
        entity.orderId = event.orderId().value();
        entity.incidentId = event.incidentId();
        entity.eventType = event.type().name();
        entity.actorId = event.actorId();
        entity.actorRole = event.actorRole();
        entity.detail = event.detail() == null ? null : truncate(event.detail(), 1000);
        entity.createdAt = event.createdAt();
        return entity;
    }

    OrderEvent toDomain() {
        return new OrderEvent(eventId, OrderId.of(orderId), incidentId, OrderEventType.valueOf(eventType), actorId,
                actorRole, detail, createdAt);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}

/** Adaptador de salida {@link LogisticsRepositoryPort} sobre JPA (MySQL). */
@Component
@Profile("jpa")
class JpaLogisticsRepositoryPort extends JpaRepositorySupport implements LogisticsRepositoryPort {

    JpaLogisticsRepositoryPort(jakarta.persistence.EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    // ------------------------------------------------------------------
    // Búsqueda de pedidos
    // ------------------------------------------------------------------

    /** Pedidos con trabajo logístico: pagados, despachados o entregados. */
    private static final String LOGISTICS_STATUSES = "o.status in ('PAID', 'DISPATCHED', 'DELIVERED')";

    private static final String PENDING_INCIDENT = "exists (select 1 from OrderIncidentEntity i "
            + "where i.orderId = o.orderId and i.status <> 'RESOLVED')";

    /** Alguna línea física con menos unidades recogidas que pedidas. */
    private static final String PENDING_PICKUP = "exists (select 1 from OrderEntity o2 join o2.items it "
            + "where o2.orderId = o.orderId and it.productType = 'PHYSICAL' and it.quantity > "
            + "coalesce((select sum(p.pickedQuantity) from OrderPickupEntity p "
            + "where p.orderId = o.orderId and p.productId = it.productId), 0))";

    private static String where(OrderSearch c, Map<String, Object> params) {
        StringBuilder jpql = new StringBuilder(" where ").append(LOGISTICS_STATUSES);
        if (c.status() != null) {
            jpql.append(" and o.status = :status");
            params.put("status", c.status().getCode());
        }
        if (c.codeContains() != null && !c.codeContains().isBlank()) {
            jpql.append(" and o.orderCode like :code");
            params.put("code", "%" + c.codeContains().trim().toUpperCase(Locale.ROOT) + "%");
        }
        if (c.operatorId() != null) {
            jpql.append(" and o.assignedOperatorId = :operator");
            params.put("operator", c.operatorId().value());
        }
        if (c.unassigned()) {
            jpql.append(" and o.assignedOperatorId is null");
        }
        if (c.withOpenIncidents() != null) {
            jpql.append(c.withOpenIncidents() ? " and " : " and not ").append(PENDING_INCIDENT);
        }
        if (c.pickupsPending() != null) {
            if (c.pickupsPending()) {
                jpql.append(" and o.status = 'PAID' and ").append(PENDING_PICKUP);
            } else {
                jpql.append(" and not ").append(PENDING_PICKUP);
            }
        }
        if (c.createdFrom() != null) {
            jpql.append(" and o.createdAt >= :from");
            params.put("from", c.createdFrom().atStartOfDay());
        }
        if (c.createdTo() != null) {
            jpql.append(" and o.createdAt < :to");
            params.put("to", c.createdTo().plusDays(1).atStartOfDay());
        }
        return jpql.toString();
    }

    @Override
    public List<Order> searchOrders(OrderSearch criteria, int offset, int limit) {
        Map<String, Object> params = new HashMap<>();
        String jpql = "select o from OrderEntity o" + where(criteria, params) + " order by o.updatedAt desc, o.orderId";
        return queryPage(OrderEntity.class, jpql, params, offset, limit).stream().map(OrderEntity::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public long countOrders(OrderSearch criteria) {
        Map<String, Object> params = new HashMap<>();
        return count("select count(o) from OrderEntity o" + where(criteria, params), params);
    }

    // ------------------------------------------------------------------
    // Recogidas
    // ------------------------------------------------------------------

    @Override
    public List<OrderPickup> findPickups(OrderId orderId) {
        return query(OrderPickupEntity.class, "select p from OrderPickupEntity p where p.orderId = :id",
                Map.of("id", orderId.value())).stream().map(OrderPickupEntity::toDomain).collect(Collectors.toList());
    }

    @Override
    public Map<String, List<OrderPickup>> findPickups(Collection<OrderId> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return query(OrderPickupEntity.class, "select p from OrderPickupEntity p where p.orderId in :ids",
                Map.of("ids", orderIds.stream().map(OrderId::value).toList())).stream()
                .map(OrderPickupEntity::toDomain).collect(Collectors.groupingBy(p -> p.orderId().value()));
    }

    @Override
    public Optional<OrderPickup> findPickup(OrderId orderId, ProductId productId) {
        return querySingle(OrderPickupEntity.class,
                "select p from OrderPickupEntity p where p.orderId = :o and p.productId = :p",
                Map.of("o", orderId.value(), "p", productId.value())).map(OrderPickupEntity::toDomain);
    }

    @Override
    public OrderPickup savePickup(OrderPickup pickup) {
        return persist(OrderPickupEntity.from(pickup)).toDomain();
    }

    // ------------------------------------------------------------------
    // Novedades
    // ------------------------------------------------------------------

    @Override
    public OrderIncident saveIncident(OrderIncident incident) {
        return persist(OrderIncidentEntity.from(incident)).toDomain();
    }

    @Override
    public Optional<OrderIncident> findIncident(String incidentId) {
        return find(OrderIncidentEntity.class, incidentId).map(OrderIncidentEntity::toDomain);
    }

    @Override
    public List<OrderIncident> findIncidents(OrderId orderId) {
        return query(OrderIncidentEntity.class,
                "select i from OrderIncidentEntity i where i.orderId = :id order by i.reportedAt, i.incidentId",
                Map.of("id", orderId.value())).stream().map(OrderIncidentEntity::toDomain).collect(Collectors.toList());
    }

    @Override
    public Map<String, List<OrderIncident>> findIncidents(Collection<OrderId> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return query(OrderIncidentEntity.class,
                "select i from OrderIncidentEntity i where i.orderId in :ids order by i.reportedAt, i.incidentId",
                Map.of("ids", orderIds.stream().map(OrderId::value).toList())).stream()
                .map(OrderIncidentEntity::toDomain).collect(Collectors.groupingBy(i -> i.getOrderId().value()));
    }

    @Override
    public List<OrderIncident> listIncidents(IncidentStatus status, int offset, int limit) {
        Map<String, Object> params = new HashMap<>();
        String jpql = "select i from OrderIncidentEntity i" + incidentFilter(status, params)
                + " order by i.reportedAt desc, i.incidentId";
        return queryPage(OrderIncidentEntity.class, jpql, params, offset, limit).stream()
                .map(OrderIncidentEntity::toDomain).collect(Collectors.toList());
    }

    @Override
    public long countIncidents(IncidentStatus status) {
        Map<String, Object> params = new HashMap<>();
        return count("select count(i) from OrderIncidentEntity i" + incidentFilter(status, params), params);
    }

    private static String incidentFilter(IncidentStatus status, Map<String, Object> params) {
        if (status == null) {
            return "";
        }
        params.put("status", status.name());
        return " where i.status = :status";
    }

    @Override
    public long countPendingIncidents(OrderId orderId) {
        return count("select count(i) from OrderIncidentEntity i where i.orderId = :id and i.status <> 'RESOLVED'",
                Map.of("id", orderId.value()));
    }

    // ------------------------------------------------------------------
    // Historial
    // ------------------------------------------------------------------

    @Override
    public OrderEvent addEvent(OrderEvent event) {
        return persist(OrderEventEntity.from(event)).toDomain();
    }

    @Override
    public List<OrderEvent> findEvents(OrderId orderId) {
        return query(OrderEventEntity.class,
                "select e from OrderEventEntity e where e.orderId = :id order by e.createdAt, e.eventId",
                Map.of("id", orderId.value())).stream().map(OrderEventEntity::toDomain).collect(Collectors.toList());
    }
}
