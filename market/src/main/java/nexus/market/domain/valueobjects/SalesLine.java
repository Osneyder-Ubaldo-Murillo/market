package nexus.market.domain.valueobjects;

import java.math.BigDecimal;

/**
 * Ventas de una moneda: cantidad de pedidos con venta efectiva (pagados, despachados o entregados) y su suma.
 */
public record SalesLine(String currency, long orders, BigDecimal total) {
}
