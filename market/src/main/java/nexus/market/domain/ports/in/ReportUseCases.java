package nexus.market.domain.ports.in;

import java.util.List;
import java.util.Map;

import nexus.market.domain.models.Inventory;
import nexus.market.domain.valueobjects.SalesLine;

/**
 * Casos de uso de los reportes administrativos (consulta, sin efectos): pedidos por estado, ventas e inventario dañado.
 */
public interface ReportUseCases {

    /** Pedidos por estado; todos los estados del ciclo aparecen, con 0 si no tienen pedidos. */
    Map<String, Long> ordersByStatus();

    List<SalesLine> sales();

    List<Inventory> damagedInventory(int limit);

    long countDamagedInventory();
}
