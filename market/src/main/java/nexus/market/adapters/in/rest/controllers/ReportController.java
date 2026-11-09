package nexus.market.adapters.in.rest.controllers;

import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import nexus.market.adapters.in.rest.PageParams;
import nexus.market.adapters.in.rest.mappers.RestMappers;
import nexus.market.adapters.in.rest.responses.CatalogResponses.InventoryResponse;
import nexus.market.adapters.in.rest.responses.CommerceResponses.OrdersByStatusResponse;
import nexus.market.adapters.in.rest.responses.CommerceResponses.SalesLineResponse;
import nexus.market.adapters.in.rest.responses.CommerceResponses.SalesReportResponse;
import nexus.market.domain.ports.in.ReportUseCases;

/**
 * Controlador REST de los reportes administrativos (solo lectura): pedidos por estado, ventas e inventario dañado.
 */
@Tag(name = "Reportes", description = "Reportes administrativos de solo lectura.")
@RestController
@RequestMapping("/api/reports")
@Profile("jpa")
public class ReportController {

    private final ReportUseCases reportUseCases;

    public ReportController(ReportUseCases reportUseCases) {
        this.reportUseCases = reportUseCases;
    }

    @Operation(summary = "Pedidos por estado",
            description = "Solo ADMIN y SUPERVISOR. Cantidad de pedidos en cada estado del ciclo (CART, PENDING_PAYMENT, PAID, "
                    + "DISPATCHED, DELIVERED, CANCELLED), con 0 en los estados sin pedidos.")
    @GetMapping("/orders-by-status")
    public ResponseEntity<OrdersByStatusResponse> ordersByStatus() {
        Map<String, Long> byStatus = reportUseCases.ordersByStatus();
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        return ResponseEntity.ok(new OrdersByStatusResponse(byStatus, total));
    }

    @Operation(summary = "Ventas",
            description = "Solo ADMIN y SUPERVISOR. Pedidos con venta efectiva (PAID, DISPATCHED y DELIVERED) y su suma, "
                    + "una línea por moneda. No incluye pedidos pendientes de pago, en carrito ni cancelados.")
    @GetMapping("/sales")
    public ResponseEntity<SalesReportResponse> sales() {
        List<SalesLineResponse> lines = reportUseCases.sales().stream()
                .map(line -> new SalesLineResponse(line.currency(), line.orders(), line.total())).toList();
        return ResponseEntity.ok(new SalesReportResponse(lines, lines.stream().mapToLong(SalesLineResponse::orders).sum()));
    }

    @Operation(summary = "Inventario dañado",
            description = "Solo ADMIN y SUPERVISOR. Registros de inventario marcados como dañados, el más reciente primero. "
                    + "Paginado con size (máximo 100); el total va en la cabecera X-Total-Count.")
    @GetMapping("/damaged-inventory")
    public ResponseEntity<List<InventoryResponse>> damagedInventory(
            @RequestParam(defaultValue = "20") int size) {
        PageParams params = PageParams.of(0, size);
        return ResponseEntity.ok()
                .header(PageParams.TOTAL_COUNT_HEADER, String.valueOf(reportUseCases.countDamagedInventory()))
                .body(reportUseCases.damagedInventory(params.size()).stream().map(RestMappers::inventory).toList());
    }
}
