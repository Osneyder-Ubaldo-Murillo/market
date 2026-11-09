package nexus.market.domain.ports.out;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import nexus.market.domain.enums.IncidentStatus;
import nexus.market.domain.models.Order;
import nexus.market.domain.models.OrderEvent;
import nexus.market.domain.models.OrderIncident;
import nexus.market.domain.models.OrderPickup;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.OrderStatus;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.UserId;

/**
 * Puerto de salida de la gestión logística de pedidos: recogidas, novedades, historial y las búsquedas de los paneles del
 * operador y del supervisor. Las altas del historial y de las novedades solo se agregan; nada se borra.
 */
public interface LogisticsRepositoryPort {

    /**
     * Criterios de búsqueda de pedidos. Cada campo nulo (o falso) no filtra. Solo se consideran pedidos con trabajo
     * logístico: pagados, despachados o entregados.
     *
     * @param codeContains   fragmento del código legible del pedido
     * @param operatorId     operador asignado
     * @param unassigned     solo pedidos sin operador
     * @param withOpenIncidents  {@code true}: con novedades sin resolver; {@code false}: sin ellas
     * @param pickupsPending {@code true}: con unidades físicas por recoger; {@code false}: con todo recogido
     * @param createdFrom    creados desde (incluido)
     * @param createdTo      creados hasta (incluido)
     */
    record OrderSearch(String codeContains, OrderStatus status, UserId operatorId, boolean unassigned,
                       Boolean withOpenIncidents, Boolean pickupsPending, LocalDate createdFrom, LocalDate createdTo) {
    }

    List<Order> searchOrders(OrderSearch criteria, int offset, int limit);

    long countOrders(OrderSearch criteria);

    // Recogidas

    List<OrderPickup> findPickups(OrderId orderId);

    Map<String, List<OrderPickup>> findPickups(Collection<OrderId> orderIds);

    Optional<OrderPickup> findPickup(OrderId orderId, ProductId productId);

    OrderPickup savePickup(OrderPickup pickup);

    // Novedades

    OrderIncident saveIncident(OrderIncident incident);

    Optional<OrderIncident> findIncident(String incidentId);

    List<OrderIncident> findIncidents(OrderId orderId);

    Map<String, List<OrderIncident>> findIncidents(Collection<OrderId> orderIds);

    /** Novedades de todos los pedidos, las más recientes primero; {@code status} nulo no filtra. */
    List<OrderIncident> listIncidents(IncidentStatus status, int offset, int limit);

    long countIncidents(IncidentStatus status);

    long countPendingIncidents(OrderId orderId);

    // Historial

    OrderEvent addEvent(OrderEvent event);

    /** Historial cronológico (el más antiguo primero). */
    List<OrderEvent> findEvents(OrderId orderId);
}
