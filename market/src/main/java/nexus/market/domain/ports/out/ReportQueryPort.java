package nexus.market.domain.ports.out;

import java.util.List;
import java.util.Map;

import nexus.market.domain.models.Inventory;
import nexus.market.domain.valueobjects.SalesLine;

/**
 * Puerto de salida de solo lectura para los reportes administrativos: consultas agregadas que no bloquean filas ni
 * modifican nada. Implementado por {@code adapters.out.persistence.mysql} (JPA).
 */
public interface ReportQueryPort {

    /** Cantidad de pedidos por código de estado (solo aparecen los estados con al menos un pedido). */
    Map<String, Long> countOrdersByStatus();

    /** Ventas por moneda: pedidos PAID, DISPATCHED y DELIVERED. */
    List<SalesLine> salesByCurrency();

    /** Inventario marcado como dañado, el más reciente primero, hasta {@code limit} registros. */
    List<Inventory> findDamagedInventory(int limit);

    long countDamagedInventory();
}
