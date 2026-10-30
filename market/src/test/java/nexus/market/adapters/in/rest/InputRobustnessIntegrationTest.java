package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import nexus.market.domain.valueobjects.SystemRole;

/**
 * Ningún endpoint puede responder 5xx por una entrada mal formada: identificadores que no son UUID, recursos
 * inexistentes, cantidades y precios absurdos, cuerpos vacíos o con tipos equivocados. Se prueba con cada rol y cada ruta
 * de la API; la respuesta correcta es siempre 4xx (o 2xx si la operación es legítima).
 */
class InputRobustnessIntegrationTest extends RestTestSupport {

    private record Route(HttpMethod method, String path) {
    }

    private static final String[] BAD_IDS = { "no-es-un-uuid", "00000000-0000-0000-0000-000000000000", "%20", "1" };

    private List<Route> routes(String id) {
        List<Route> routes = new ArrayList<>();
        for (String get : new String[] { "/api/users/", "/api/orders/", "/api/shippings/", "/api/invoices/",
                "/api/returns/", "/api/warehouses/", "/api/inventory/", "/api/products/" }) {
            routes.add(new Route(HttpMethod.GET, get + id));
        }
        for (String get : new String[] { "/api/orders/%s/shipping", "/api/orders/%s/invoice",
                "/api/sellers/%s/warehouses", "/api/buyers/%s/cart", "/api/buyers/%s/orders",
                "/api/buyers/%s/invoices" }) {
            routes.add(new Route(HttpMethod.GET, String.format(get, id)));
        }
        for (String post : new String[] { "/api/users/%s/block", "/api/users/%s/activate", "/api/users/%s/deactivate" }) {
            routes.add(new Route(HttpMethod.PATCH, String.format(post, id)));
        }
        for (String post : new String[] { "/api/sellers/%s/approve", "/api/orders/%s/payment", "/api/orders/%s/dispatch",
                "/api/orders/%s/deliver", "/api/orders/%s/cancel", "/api/orders/%s/returns",
                "/api/orders/%s/shippings", "/api/orders/%s/invoice", "/api/shippings/%s/dispatch",
                "/api/shippings/%s/in-transit", "/api/shippings/%s/deliver", "/api/invoices/%s/cancel",
                "/api/returns/%s/approve", "/api/returns/%s/reject", "/api/returns/%s/process",
                "/api/refunds/%s/process", "/api/products/%s/publish", "/api/products/%s/unpublish",
                "/api/inventory/%s/stock", "/api/inventory/%s/damaged", "/api/inventory/%s/active",
                "/api/buyers/%s/orders", "/api/buyers/%s/addresses", "/api/buyers/%s/cart/items",
                "/api/sellers/%s/warehouses" }) {
            routes.add(new Route(HttpMethod.POST, String.format(post, id)));
        }
        routes.add(new Route(HttpMethod.PUT, "/api/products/" + id + "/price"));
        routes.add(new Route(HttpMethod.PUT, "/api/products/" + id));
        routes.add(new Route(HttpMethod.PUT, "/api/inventory/" + id + "/quantity"));
        routes.add(new Route(HttpMethod.PUT, "/api/buyers/" + id + "/cart/items/" + id));
        routes.add(new Route(HttpMethod.DELETE, "/api/buyers/" + id + "/cart/items/" + id));
        routes.add(new Route(HttpMethod.DELETE, "/api/buyers/" + id + "/cart"));
        return routes;
    }

    @Test
    void malformedAndUnknownIds_neverProduceAServerError_forAnyRole() throws Exception {
        String[] tokens = { staffToken(SystemRole.ADMIN), staffToken(SystemRole.LOGISTICS_OPERATOR),
                staffToken(SystemRole.SUPERVISOR), registerBuyer().token(),
                registerApprovedSeller(staffToken(SystemRole.ADMIN)).token(), null };
        List<String> serverErrors = new ArrayList<>();
        int calls = 0;
        for (String id : BAD_IDS) {
            for (Route route : routes(id)) {
                for (String token : tokens) {
                    String json = route.method() == HttpMethod.GET || route.method() == HttpMethod.DELETE
                            ? null : "{}";
                    int status = status(route.method(), route.path(), token, json);
                    calls++;
                    if (status >= 500) {
                        serverErrors.add(status + " " + route.method() + " " + route.path()
                                + (token == null ? " (anónimo)" : ""));
                    }
                }
            }
        }
        assertThat(calls).isGreaterThan(1000);
        assertThat(serverErrors).as("respuestas 5xx de %d llamadas", calls).isEmpty();
    }

    @Test
    void absurdValues_areRejectedWith4xx_andChangeNothing() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog catalog = seedSellerCatalog(admin, new BigDecimal("10.00"));
        Account buyer = registerBuyer();
        String cart = "/api/buyers/" + buyer.profileId() + "/cart/items";

        // Cantidades inválidas en el carrito.
        for (Object quantity : new Object[] { 0, -1, 1001, 2147483647, 1000000000000L, "null", "\"abc\"", "'1'" }) {
            int status = status(HttpMethod.POST, cart, buyer.token(),
                    "{\"productId\":\"" + catalog.productId() + "\",\"quantity\":" + quantity + "}");
            assertThat(status).as("cantidad %s", quantity).isBetween(400, 499);
        }
        // Un decimal se trunca a entero (comportamiento de Jackson): válido o 4xx, nunca 5xx.
        assertThat(status(HttpMethod.POST, cart, buyer.token(),
                "{\"productId\":\"" + catalog.productId() + "\",\"quantity\":1.5}")).isLessThan(500);
        // Sumar más allá del tope por producto se rechaza sin desbordar ni cambiar el carrito.
        send(HttpMethod.PUT, cart + "/" + catalog.productId(), buyer.token(), "{\"quantity\":1000}", 200);
        assertThat(status(HttpMethod.POST, cart, buyer.token(),
                "{\"productId\":\"" + catalog.productId() + "\",\"quantity\":2147483647}")).isEqualTo(400);
        assertThat(status(HttpMethod.POST, cart, buyer.token(),
                "{\"productId\":\"" + catalog.productId() + "\",\"quantity\":1}")).isEqualTo(400);
        send(HttpMethod.PUT, cart + "/" + catalog.productId(), buyer.token(), "{\"quantity\":1001}", 400);
        String cartAfter = send(HttpMethod.GET, "/api/buyers/" + buyer.profileId() + "/cart", buyer.token(), null, 200);
        assertThat((int) com.jayway.jsonpath.JsonPath.read(cartAfter, "$.items[0].quantity")).isEqualTo(1000);

        // Precios inválidos.
        for (String amount : new String[] { "-5", "0", "1e400", "99999999999999999999999999", "\"abc\"", "null" }) {
            int status = status(HttpMethod.PUT, "/api/products/" + catalog.productId() + "/price",
                    catalog.seller().token(),
                    "{\"price\":{\"amount\":" + amount + ",\"currency\":\"USD\"}}");
            assertThat(status).as("precio %s", amount).isBetween(200, 499);
        }
        for (String currency : new String[] { "\"\"", "\"ZZZ\"", "\"usd1\"", "null", "123" }) {
            int status = status(HttpMethod.PUT, "/api/products/" + catalog.productId() + "/price",
                    catalog.seller().token(), "{\"price\":{\"amount\":10,\"currency\":" + currency + "}}");
            assertThat(status).as("moneda %s", currency).isBetween(400, 499);
        }

        // Textos enormes o con caracteres extraños.
        String huge = "x".repeat(100_000);
        assertThat(status(HttpMethod.POST, "/api/auth/login", null, body("email", huge + "@a.co", "password", "x")))
                .isBetween(400, 499);
        assertThat(status(HttpMethod.POST, "/api/auth/password/forgot", null, body("email", huge))).isBetween(400, 499);
        assertThat(status(HttpMethod.GET, "/api/products?q=" + UUID.randomUUID() + "%00%0d%0a", null, null))
                .isLessThan(500);
        assertThat(status(HttpMethod.PUT, "/api/products/" + catalog.productId(), catalog.seller().token(),
                body("name", "N\u0000ombre‮raro", "description", "Descripcion de prueba")))
                .isLessThan(500);
    }

    @Test
    void stockQuantities_neverOverflow() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog catalog = seedSellerCatalog(admin, new BigDecimal("10.00"));
        String token = catalog.seller().token();
        String stock = "/api/inventory/" + catalog.inventoryId() + "/stock";
        for (String quantity : new String[] { "2147483647", "2147483647", "2147483647", "1000000000000", "-5", "0" }) {
            int status = status(HttpMethod.POST, stock, token, "{\"quantity\":" + quantity + "}");
            assertThat(status).as("stock %s", quantity).isLessThan(500);
        }
        for (String quantity : new String[] { "2147483647", "-1", "9999999999" }) {
            int status = status(HttpMethod.PUT, "/api/inventory/" + catalog.inventoryId() + "/quantity", token,
                    "{\"quantity\":" + quantity + "}");
            assertThat(status).as("ajuste %s", quantity).isLessThan(500);
        }
        String inventory = send(HttpMethod.GET, "/api/inventory/" + catalog.inventoryId(), token, null, 200);
        assertThat((int) com.jayway.jsonpath.JsonPath.read(inventory, "$.quantity")).isGreaterThanOrEqualTo(0);
    }
}
