package nexus.market.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nexus.market.domain.models.Inventory;
import nexus.market.domain.ports.in.ReportUseCases;
import nexus.market.domain.ports.out.ReportQueryPort;
import nexus.market.domain.valueobjects.OrderStatus;
import nexus.market.domain.valueobjects.SalesLine;

/**
 * Servicio de aplicación de los reportes administrativos: solo lectura (transacciones de solo lectura, sin bloqueos).
 */
@Service
@Profile("jpa")
@Transactional(readOnly = true)
public class ReportApplicationService implements ReportUseCases {

    /** Estados del ciclo del pedido, en el orden en que ocurren. */
    private static final List<OrderStatus> LIFECYCLE = List.of(OrderStatus.CART, OrderStatus.PENDING_PAYMENT,
            OrderStatus.PAID, OrderStatus.DISPATCHED, OrderStatus.DELIVERED, OrderStatus.CANCELLED);

    private final ReportQueryPort reports;

    public ReportApplicationService(ReportQueryPort reports) {
        this.reports = Objects.requireNonNull(reports, "reports es obligatorio");
    }

    @Override
    public Map<String, Long> ordersByStatus() {
        Map<String, Long> counted = reports.countOrdersByStatus();
        Map<String, Long> all = new LinkedHashMap<>();
        for (OrderStatus status : LIFECYCLE) {
            all.put(status.getCode(), counted.getOrDefault(status.getCode(), 0L));
        }
        return all;
    }

    @Override
    public List<SalesLine> sales() {
        return reports.salesByCurrency();
    }

    @Override
    public List<Inventory> damagedInventory(int limit) {
        return reports.findDamagedInventory(limit);
    }

    @Override
    public long countDamagedInventory() {
        return reports.countDamagedInventory();
    }
}
