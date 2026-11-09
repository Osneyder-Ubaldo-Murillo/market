package nexus.market.adapters.out.persistence.mysql;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import nexus.market.domain.models.Inventory;
import nexus.market.domain.ports.out.ReportQueryPort;
import nexus.market.domain.valueobjects.InventoryStatus;
import nexus.market.domain.valueobjects.SalesLine;

/**
 * Adaptador de salida {@link ReportQueryPort} sobre JPA (MySQL): agregaciones de solo lectura (sin
 * {@code SELECT ... FOR UPDATE}) sobre pedidos e inventario.
 */
@Component
@Profile("jpa")
class JpaReportQueryPort extends JpaRepositorySupport implements ReportQueryPort {

    JpaReportQueryPort(jakarta.persistence.EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    public Map<String, Long> countOrdersByStatus() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Object[] row : query(Object[].class,
                "select o.status, count(o) from OrderEntity o group by o.status", Map.of())) {
            result.put((String) row[0], ((Number) row[1]).longValue());
        }
        return result;
    }

    @Override
    public List<SalesLine> salesByCurrency() {
        return query(Object[].class,
                "select o.totalCurrency, count(o), sum(o.totalAmount) from OrderEntity o "
                        + "where o.status in ('PAID', 'DISPATCHED', 'DELIVERED') group by o.totalCurrency "
                        + "order by o.totalCurrency", Map.of())
                .stream()
                .map(row -> new SalesLine((String) row[0], ((Number) row[1]).longValue(), (BigDecimal) row[2]))
                .toList();
    }

    @Override
    public List<Inventory> findDamagedInventory(int limit) {
        return queryPage(InventoryEntity.class,
                "select i from InventoryEntity i where i.status = :status order by i.updatedAt desc, i.inventoryId",
                Map.of("status", InventoryStatus.DAMAGED.getCode()), 0, limit)
                .stream().map(InventoryEntity::toDomain).toList();
    }

    @Override
    public long countDamagedInventory() {
        return count("select count(i) from InventoryEntity i where i.status = :status",
                Map.of("status", InventoryStatus.DAMAGED.getCode()));
    }
}
