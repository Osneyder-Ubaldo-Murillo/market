package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import nexus.market.domain.valueobjects.SystemRole;

/**
 * Reportes administrativos (OBJ12): solo ADMIN y SUPERVISOR; son de lectura y reflejan los pedidos y el inventario reales.
 */
class ReportsIntegrationTest extends RestTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode get(String path, String token, int expected) throws Exception {
        return JSON.readTree(send(HttpMethod.GET, path, token, null, expected));
    }

    @Test
    void onlyAdminAndSupervisorCanReadReports_everyoneElseIsRefused() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        String supervisor = staffToken(SystemRole.SUPERVISOR);
        SellerCatalog catalog = seedSellerCatalog(admin, new BigDecimal("1500.00"));
        Account buyer = registerBuyer();

        for (String path : new String[] { "/api/reports/orders-by-status", "/api/reports/sales",
                "/api/reports/damaged-inventory" }) {
            get(path, admin, 200);
            get(path, supervisor, 200);
            get(path, buyer.token(), 403);
            get(path, catalog.seller().token(), 403);
            get(path, staffToken(SystemRole.LOGISTICS_OPERATOR), 403);
            send(HttpMethod.GET, path, null, null, 401);
        }
    }

    @Test
    void ordersByStatusAndSales_followARealPurchase() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        JsonNode beforeOrders = get("/api/reports/orders-by-status", admin, 200);
        JsonNode beforeSales = get("/api/reports/sales", admin, 200);
        long paidBefore = beforeOrders.at("/byStatus/PAID").asLong();
        long salesBefore = beforeSales.at("/orders").asLong();

        SellerCatalog catalog = seedSellerCatalog(admin, new BigDecimal("1500.00"));
        Account buyer = registerBuyer();
        send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                body("productId", catalog.productId(), "quantity", 2), 200);
        String orderId = read(send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders", buyer.token(),
                body("shippingAddress", addressJson()), 201), "$.orderId");

        // Sin pagar el pedido no cuenta como venta.
        assertThat(get("/api/reports/sales", admin, 200).at("/orders").asLong()).isEqualTo(salesBefore);

        send(HttpMethod.POST, "/api/orders/" + orderId + "/payment", buyer.token(), body("paymentMethod", "CARD"), 200);

        JsonNode orders = get("/api/reports/orders-by-status", staffToken(SystemRole.SUPERVISOR), 200);
        assertThat(orders.at("/byStatus/PAID").asLong()).isEqualTo(paidBefore + 1);
        for (String status : new String[] { "CART", "PENDING_PAYMENT", "PAID", "DISPATCHED", "DELIVERED", "CANCELLED" }) {
            assertThat(orders.at("/byStatus").has(status)).as("estado %s siempre presente", status).isTrue();
        }
        assertThat(orders.at("/total").asLong()).isGreaterThanOrEqualTo(orders.at("/byStatus/PAID").asLong());

        JsonNode sales = get("/api/reports/sales", admin, 200);
        assertThat(sales.at("/orders").asLong()).isEqualTo(salesBefore + 1);
        assertThat(sales.at("/byCurrency").size()).isGreaterThanOrEqualTo(1);
        assertThat(sales.at("/byCurrency/0/currency").asText()).isNotBlank();
        assertThat(sales.at("/byCurrency/0/total").decimalValue()).isGreaterThanOrEqualTo(new BigDecimal("3000"));
    }

    @Test
    void damagedInventory_listsOnlyDamagedRecords_andRejectsAnInvalidSize() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog catalog = seedSellerCatalog(admin, new BigDecimal("1500.00"));
        long before = Long.parseLong(headerOf("/api/reports/damaged-inventory", admin, "X-Total-Count"));

        send(HttpMethod.POST, "/api/inventory/" + catalog.inventoryId() + "/damaged", catalog.seller().token(), "{}", 200);

        assertThat(Long.parseLong(headerOf("/api/reports/damaged-inventory", admin, "X-Total-Count")))
                .isEqualTo(before + 1);
        JsonNode list = get("/api/reports/damaged-inventory", admin, 200);
        assertThat(list.get(0).get("inventoryId").asText()).isEqualTo(catalog.inventoryId());
        for (JsonNode item : list) {
            assertThat(item.get("status").asText()).isEqualTo("DAMAGED");
        }

        send(HttpMethod.GET, "/api/reports/damaged-inventory?size=0", admin, null, 400);
        send(HttpMethod.GET, "/api/reports/damaged-inventory?size=101", admin, null, 400);
    }

    private String headerOf(String path, String token, String header) throws Exception {
        return perform(HttpMethod.GET, path, token, null).getResponse().getHeader(header);
    }
}
