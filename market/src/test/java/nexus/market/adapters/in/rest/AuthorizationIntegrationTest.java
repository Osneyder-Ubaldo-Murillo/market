package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.HttpMethod;

import nexus.market.domain.valueobjects.SystemRole;

/**
 * Pruebas de autorización de la API: rol (matriz por endpoint), propiedad del
 * recurso (aislamiento entre compradores y entre vendedores), SUPERVISOR de
 * solo lectura, denegación por defecto y formato de errores 401/403.
 *
 * <p>Escenario compartido: dos vendedores (A y B) con producto, bodega e
 * inventario propios; dos compradores; un pedido de la compradora A con
 * productos de ambos vendedores y un pedido del comprador B (solo vendedor B)
 * ya pagado.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthorizationIntegrationTest extends RestTestSupport {

    private static final String BUYER = "BUYER";
    private static final String SELLER = "SELLER";
    private static final String OPERATOR = "LOGISTICS_OPERATOR";
    private static final String ADMIN = "ADMIN";
    private static final String SUPERVISOR = "SUPERVISOR";

    String admin;
    String operator;
    String supervisor;
    SellerCatalog catA;
    SellerCatalog catB;
    Account buyerA;
    Account buyerB;
    String orderAB;   // compradora A: 2 x productoA (100) + 1 x productoB (250)
    String orderB;    // comprador B: 1 x productoB, pagado
    String invoiceB;

    @BeforeAll
    void fixture() throws Exception {
        admin = staffToken(SystemRole.ADMIN);
        operator = staffToken(SystemRole.LOGISTICS_OPERATOR);
        supervisor = staffToken(SystemRole.SUPERVISOR);
        catA = seedSellerCatalog(admin, new BigDecimal("100.00"));
        catB = seedSellerCatalog(admin, new BigDecimal("250.00"));
        buyerA = registerBuyer();
        buyerB = registerBuyer();

        send(HttpMethod.POST, cart(buyerA, "/items"), buyerA.token(),
                body("productId", catA.productId(), "quantity", 2), 200);
        send(HttpMethod.POST, cart(buyerA, "/items"), buyerA.token(),
                body("productId", catB.productId(), "quantity", 1), 200);
        orderAB = read(send(HttpMethod.POST, "/api/buyers/" + buyerA.profileId() + "/orders", buyerA.token(),
                body("shippingAddress", addressJson()), 201), "$.orderId");

        send(HttpMethod.POST, cart(buyerB, "/items"), buyerB.token(),
                body("productId", catB.productId(), "quantity", 1), 200);
        orderB = read(send(HttpMethod.POST, "/api/buyers/" + buyerB.profileId() + "/orders", buyerB.token(),
                body("shippingAddress", addressJson()), 201), "$.orderId");
        send(HttpMethod.POST, "/api/orders/" + orderB + "/payment", buyerB.token(),
                body("paymentMethod", "CARD"), 200);
        invoiceB = read(send(HttpMethod.GET, "/api/orders/" + orderB + "/invoice", buyerB.token(), null, 200),
                "$.invoiceId");
    }

    private static String cart(Account buyer, String suffix) {
        return "/api/buyers/" + buyer.profileId() + "/cart" + suffix;
    }

    private void assertError(String json, String code) {
        assertThat((String) JsonPath.read(json, "$.code")).isEqualTo(code);
        assertThat((String) JsonPath.read(json, "$.message")).isNotBlank();
        assertThat((String) JsonPath.read(json, "$.timestamp")).isNotBlank();
    }

    private void expectForbidden(String token, HttpMethod method, String path, String json) throws Exception {
        assertError(send(method, path, token, json, 403), "ACCESS_DENIED");
    }

    // ------------------------------------------------------------------
    // 401 / 403: formato y denegación por defecto
    // ------------------------------------------------------------------

    @Test
    void anonymousRequestsToProtectedEndpoints_return401WithApiErrorFormat() throws Exception {
        String[][] endpoints = {
                {"GET", "/api/orders/x"}, {"GET", "/api/buyers/x/cart"}, {"POST", "/api/products"},
                {"GET", "/api/inventory/x"}, {"POST", "/api/auth/register/seller"},
                {"GET", "/api/users/x"}, {"GET", "/api/ruta-inexistente"}};
        for (String[] e : endpoints) {
            assertError(send(HttpMethod.valueOf(e[0]), e[1], null, "POST".equals(e[0]) ? "{}" : null, 401),
                    "UNAUTHORIZED");
        }
    }

    @Test
    void invalidToken_returns401WithApiErrorFormat() throws Exception {
        // Un token que no verifica se trata como petición sin autenticar
        assertError(send(HttpMethod.GET, "/api/orders/" + orderAB, "no.es.un-jwt", null, 401), "UNAUTHORIZED");
    }

    @Test
    void wrongRole_and_wrongOwner_return403WithSameApiErrorFormat() throws Exception {
        expectForbidden(buyerA.token(), HttpMethod.POST, "/api/products", "{}");            // rol
        expectForbidden(buyerA.token(), HttpMethod.GET, cart(buyerB, ""), null);            // propiedad
    }

    // ------------------------------------------------------------------
    // Matriz de roles: cada rol no autorizado recibe 403; el anónimo, 401
    // ------------------------------------------------------------------

    private record Rule(HttpMethod method, String path, Set<String> allowed) {
    }

    private static Rule rule(String method, String path, String... allowed) {
        return new Rule(HttpMethod.valueOf(method), path, allowed.length == 0 ? Set.of() : Set.of(allowed));
    }

    private static List<Rule> matrix() {
        return List.of(
                rule("POST", "/api/auth/register/seller", ADMIN),
                rule("POST", "/api/sellers/x/approve", ADMIN),
                rule("PATCH", "/api/users/x/block", ADMIN),
                rule("PATCH", "/api/users/x/activate", ADMIN),
                rule("PATCH", "/api/users/x/deactivate", ADMIN),
                rule("GET", "/api/users/x", BUYER, SELLER, OPERATOR, ADMIN, SUPERVISOR),
                rule("POST", "/api/buyers/x/addresses", BUYER),
                rule("GET", "/api/buyers/x/cart", BUYER),
                rule("DELETE", "/api/buyers/x/cart", BUYER),
                rule("POST", "/api/buyers/x/cart/items", BUYER),
                rule("PUT", "/api/buyers/x/cart/items/y", BUYER),
                rule("DELETE", "/api/buyers/x/cart/items/y", BUYER),
                rule("POST", "/api/buyers/x/orders", BUYER),
                rule("POST", "/api/orders/x/payment", BUYER),
                rule("POST", "/api/orders/x/cancel", BUYER),
                rule("POST", "/api/orders/x/returns", BUYER),
                rule("GET", "/api/orders/x", BUYER, SELLER, OPERATOR, ADMIN, SUPERVISOR),
                rule("GET", "/api/orders/x/shipping", BUYER, SELLER, OPERATOR, ADMIN, SUPERVISOR),
                rule("GET", "/api/shippings/x", BUYER, SELLER, OPERATOR, ADMIN, SUPERVISOR),
                rule("POST", "/api/orders/x/dispatch", OPERATOR),
                rule("POST", "/api/orders/x/deliver", OPERATOR),
                rule("POST", "/api/orders/x/shippings", OPERATOR),
                rule("POST", "/api/shippings/x/dispatch", OPERATOR),
                rule("POST", "/api/shippings/x/in-transit", OPERATOR),
                rule("POST", "/api/shippings/x/deliver", OPERATOR),
                rule("GET", "/api/invoices/x", BUYER, ADMIN, SUPERVISOR),
                rule("GET", "/api/orders/x/invoice", BUYER, ADMIN, SUPERVISOR),
                rule("POST", "/api/orders/x/invoice", ADMIN),
                rule("POST", "/api/invoices/x/cancel", ADMIN),
                rule("GET", "/api/returns/x", BUYER, OPERATOR, ADMIN, SUPERVISOR),
                rule("POST", "/api/returns/x/approve", ADMIN),
                rule("POST", "/api/returns/x/reject", ADMIN),
                rule("POST", "/api/refunds/x/process", ADMIN),
                rule("POST", "/api/returns/x/process", OPERATOR),
                rule("POST", "/api/products", SELLER),
                rule("POST", "/api/products/x/publish", SELLER),
                rule("POST", "/api/products/x/unpublish", SELLER),
                rule("PUT", "/api/products/x/price", SELLER),
                rule("POST", "/api/warehouses/marketplace", ADMIN),
                rule("POST", "/api/sellers/x/warehouses", ADMIN),
                rule("GET", "/api/warehouses/x", SELLER, OPERATOR, ADMIN, SUPERVISOR),
                rule("GET", "/api/sellers/x/warehouses", SELLER, OPERATOR, ADMIN, SUPERVISOR),
                rule("POST", "/api/inventory", SELLER),
                rule("POST", "/api/inventory/x/stock", SELLER),
                rule("POST", "/api/inventory/x/damaged", SELLER),
                rule("POST", "/api/inventory/x/active", SELLER),
                rule("PUT", "/api/inventory/x/quantity", SELLER),
                rule("GET", "/api/inventory/x", SELLER, ADMIN, SUPERVISOR),
                // reserve/release no se exponen a ningún rol externo
                rule("POST", "/api/inventory/x/reserve"),
                rule("POST", "/api/inventory/x/release"),
                // sin regla explícita: denegado por defecto
                rule("GET", "/api/ruta-inexistente"),
                rule("DELETE", "/api/products/x"),
                rule("PATCH", "/api/products/x"),
                rule("PUT", "/api/orders/x"),
                rule("POST", "/api/orders"),
                rule("GET", "/actuator/metrics", ADMIN));
    }

    private Map<String, String> tokens() {
        return Map.of(BUYER, buyerA.token(), SELLER, catA.seller().token(), OPERATOR, operator,
                ADMIN, admin, SUPERVISOR, supervisor);
    }

    @Test
    void roleMatrix_unauthorizedRolesGet403_andAnonymousGets401() throws Exception {
        List<String> failures = new ArrayList<>();
        for (Rule rule : matrix()) {
            String json = rule.method() == HttpMethod.GET || rule.method() == HttpMethod.DELETE ? null : "{}";
            int anonymous = status(rule.method(), rule.path(), null, json);
            if (anonymous != 401) {
                failures.add("anónimo " + rule.method() + " " + rule.path() + " -> " + anonymous);
            }
            for (Map.Entry<String, String> role : tokens().entrySet()) {
                if (rule.allowed().contains(role.getKey())) {
                    continue;
                }
                int status = status(rule.method(), rule.path(), role.getValue(), json);
                if (status != 403) {
                    failures.add(role.getKey() + " " + rule.method() + " " + rule.path() + " -> " + status);
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void supervisor_isReadOnly_everyWriteIsForbidden() throws Exception {
        List<String> failures = new ArrayList<>();
        for (Rule rule : matrix()) {
            if (rule.method() == HttpMethod.GET) {
                continue;
            }
            assertThat(rule.allowed()).as("%s %s", rule.method(), rule.path()).doesNotContain(SUPERVISOR);
            int status = status(rule.method(), rule.path(), supervisor, "{}");
            if (status != 403) {
                failures.add(rule.method() + " " + rule.path() + " -> " + status);
            }
        }
        assertThat(failures).isEmpty();
        // Lecturas permitidas al supervisor
        send(HttpMethod.GET, "/api/orders/" + orderAB, supervisor, null, 200);
        send(HttpMethod.GET, "/api/inventory/" + catA.inventoryId(), supervisor, null, 200);
        send(HttpMethod.GET, "/api/warehouses/" + catA.warehouseId(), supervisor, null, 200);
        send(HttpMethod.GET, "/api/invoices/" + invoiceB, supervisor, null, 200);
        // GET /cart crea el carrito si no existe (escribe): nunca concedido al supervisor
        expectForbidden(supervisor, HttpMethod.GET, cart(buyerA, ""), null);
    }

    // ------------------------------------------------------------------
    // Registro de vendedores: solo ADMIN
    // ------------------------------------------------------------------

    @Test
    void registerSeller_onlyAdminCanDo() throws Exception {
        String suffix = uniq();
        String email = "intruso-" + suffix + "@mail.test";
        String payload = body("fullName", "Intruso " + suffix, "email", email, "documentId", "NIT-" + suffix,
                "password", PASSWORD, "businessName", "Intruso SA", "taxId", "98765432");
        send(HttpMethod.POST, "/api/auth/register/seller", null, payload, 401);
        for (String token : List.of(buyerA.token(), catA.seller().token(), operator, supervisor)) {
            expectForbidden(token, HttpMethod.POST, "/api/auth/register/seller", payload);
        }
        // Ningún intento denegado creó la cuenta
        send(HttpMethod.POST, "/api/auth/login", null, body("email", email, "password", PASSWORD), 401);
        send(HttpMethod.POST, "/api/auth/register/seller", admin, payload, 201);
    }

    // ------------------------------------------------------------------
    // Aislamiento entre compradores
    // ------------------------------------------------------------------

    @Test
    void buyer_cannotTouchAnotherBuyersResources() throws Exception {
        String t = buyerA.token();
        expectForbidden(t, HttpMethod.GET, cart(buyerB, ""), null);
        expectForbidden(t, HttpMethod.DELETE, cart(buyerB, ""), null);
        expectForbidden(t, HttpMethod.POST, cart(buyerB, "/items"),
                body("productId", catA.productId(), "quantity", 1));
        expectForbidden(t, HttpMethod.PUT, cart(buyerB, "/items/" + catB.productId()), body("quantity", 3));
        expectForbidden(t, HttpMethod.POST, "/api/buyers/" + buyerB.profileId() + "/orders",
                body("shippingAddress", addressJson()));
        expectForbidden(t, HttpMethod.POST, "/api/buyers/" + buyerB.profileId() + "/addresses", addressJson());
        expectForbidden(t, HttpMethod.GET, "/api/orders/" + orderB, null);
        expectForbidden(t, HttpMethod.POST, "/api/orders/" + orderB + "/payment", body("paymentMethod", "CARD"));
        expectForbidden(t, HttpMethod.POST, "/api/orders/" + orderB + "/cancel", "{}");
        expectForbidden(t, HttpMethod.POST, "/api/orders/" + orderB + "/returns",
                "{\"items\":[{\"productId\":\"" + catB.productId() + "\",\"quantity\":1,\"reason\":\"DAMAGED\"}]}");
        expectForbidden(t, HttpMethod.GET, "/api/orders/" + orderB + "/invoice", null);
        expectForbidden(t, HttpMethod.GET, "/api/invoices/" + invoiceB, null);
        expectForbidden(t, HttpMethod.GET, "/api/orders/" + orderB + "/shipping", null);
        expectForbidden(t, HttpMethod.GET, "/api/users/" + buyerB.userId(), null);

        // El pedido ajeno no se alteró y su dueño sí accede
        String order = send(HttpMethod.GET, "/api/orders/" + orderB, buyerB.token(), null, 200);
        assertThat((String) JsonPath.read(order, "$.status")).isEqualTo("PAID");
        send(HttpMethod.GET, cart(buyerB, ""), buyerB.token(), null, 200);
        send(HttpMethod.GET, "/api/invoices/" + invoiceB, buyerB.token(), null, 200);
        send(HttpMethod.GET, "/api/users/" + buyerB.userId(), buyerB.token(), null, 200);
    }

    @Test
    void nonexistentResources_doNotRevealExistenceToRestrictedRoles() throws Exception {
        String missing = java.util.UUID.randomUUID().toString();
        expectForbidden(buyerA.token(), HttpMethod.GET, "/api/orders/" + missing, null);
        expectForbidden(catA.seller().token(), HttpMethod.GET, "/api/inventory/" + missing, null);
        expectForbidden(catA.seller().token(), HttpMethod.POST, "/api/products/" + missing + "/publish", "{}");
        // Roles sin restricción de propiedad sí reciben 404
        send(HttpMethod.GET, "/api/orders/" + missing, admin, null, 404);
    }

    // ------------------------------------------------------------------
    // Aislamiento entre vendedores
    // ------------------------------------------------------------------

    @Test
    void seller_cannotManageAnotherSellersResources() throws Exception {
        String a = catA.seller().token();
        expectForbidden(a, HttpMethod.POST, "/api/products/" + catB.productId() + "/publish", "{}");
        expectForbidden(a, HttpMethod.POST, "/api/products/" + catB.productId() + "/unpublish", "{}");
        expectForbidden(a, HttpMethod.PUT, "/api/products/" + catB.productId() + "/price",
                body("price", moneyJson(BigDecimal.ONE)));
        expectForbidden(a, HttpMethod.POST, "/api/products",
                body("sellerId", catB.seller().profileId(), "name", "Producto falso", "description",
                        "Descripcion valida", "type", "PHYSICAL", "price", moneyJson(BigDecimal.TEN)));
        // Inventario: producto ajeno o bodega ajena
        expectForbidden(a, HttpMethod.POST, "/api/inventory",
                body("productId", catB.productId(), "warehouseId", catA.warehouseId()));
        expectForbidden(a, HttpMethod.POST, "/api/inventory",
                body("productId", catA.productId(), "warehouseId", catB.warehouseId()));
        expectForbidden(a, HttpMethod.POST, "/api/inventory/" + catB.inventoryId() + "/stock",
                body("quantity", 5));
        expectForbidden(a, HttpMethod.PUT, "/api/inventory/" + catB.inventoryId() + "/quantity",
                body("quantity", 5));
        expectForbidden(a, HttpMethod.POST, "/api/inventory/" + catB.inventoryId() + "/damaged", "{}");
        expectForbidden(a, HttpMethod.POST, "/api/inventory/" + catB.inventoryId() + "/active", "{}");
        expectForbidden(a, HttpMethod.GET, "/api/inventory/" + catB.inventoryId(), null);
        expectForbidden(a, HttpMethod.GET, "/api/warehouses/" + catB.warehouseId(), null);
        expectForbidden(a, HttpMethod.GET, "/api/sellers/" + catB.seller().profileId() + "/warehouses", null);

        // El inventario ajeno no cambió y el propietario sí opera
        String inventoryB = send(HttpMethod.GET, "/api/inventory/" + catB.inventoryId(), catB.seller().token(),
                null, 200);
        // 10 iniciales - 1 vendido (pedido pagado): los intentos denegados no lo modificaron
        assertThat((int) JsonPath.read(inventoryB, "$.quantity")).isEqualTo(9);
        send(HttpMethod.GET, "/api/inventory/" + catA.inventoryId(), a, null, 200);
        send(HttpMethod.GET, "/api/warehouses/" + catA.warehouseId(), a, null, 200);
        send(HttpMethod.GET, "/api/sellers/" + catA.seller().profileId() + "/warehouses", a, null, 200);
    }

    @Test
    void operator_hasNoInventoryAccess_untilOperatorWarehouseScopeExists() throws Exception {
        expectForbidden(operator, HttpMethod.GET, "/api/inventory/" + catA.inventoryId(), null);
        expectForbidden(operator, HttpMethod.POST, "/api/inventory/" + catA.inventoryId() + "/stock",
                body("quantity", 1));
        expectForbidden(operator, HttpMethod.PUT, "/api/inventory/" + catA.inventoryId() + "/quantity",
                body("quantity", 1));
        // reserve/release tampoco están disponibles ni siquiera para ADMIN
        expectForbidden(admin, HttpMethod.POST, "/api/inventory/" + catA.inventoryId() + "/reserve",
                body("quantity", 1));
        expectForbidden(admin, HttpMethod.POST, "/api/inventory/" + catA.inventoryId() + "/release",
                body("quantity", 1));
    }

    @Test
    void onlyOperatorDispatches() throws Exception {
        String body = body("warehouseId", catB.warehouseId());
        for (String token : List.of(catB.seller().token(), buyerB.token(), admin, supervisor)) {
            expectForbidden(token, HttpMethod.POST, "/api/orders/" + orderB + "/dispatch", body);
            expectForbidden(token, HttpMethod.POST, "/api/orders/" + orderB + "/shippings", body);
        }
        String order = send(HttpMethod.GET, "/api/orders/" + orderB, admin, null, 200);
        assertThat((String) JsonPath.read(order, "$.status")).isEqualTo("PAID"); // sin cambios
    }

    // ------------------------------------------------------------------
    // Pedidos con productos de varios vendedores
    // ------------------------------------------------------------------

    @Test
    void sellerSeesOnlyOwnItemsAndSubtotal_ofMultiSellerOrder() throws Exception {
        String forA = send(HttpMethod.GET, "/api/orders/" + orderAB, catA.seller().token(), null, 200);
        assertThat((int) JsonPath.read(forA, "$.items.length()")).isEqualTo(1);
        assertThat((String) JsonPath.read(forA, "$.items[0].productId")).isEqualTo(catA.productId());
        assertThat(((Number) JsonPath.read(forA, "$.total.amount")).doubleValue()).isEqualTo(200.00);
        assertSellerViewHasNoPrivateData(forA);

        String forB = send(HttpMethod.GET, "/api/orders/" + orderAB, catB.seller().token(), null, 200);
        assertThat((int) JsonPath.read(forB, "$.items.length()")).isEqualTo(1);
        assertThat((String) JsonPath.read(forB, "$.items[0].productId")).isEqualTo(catB.productId());
        assertThat(((Number) JsonPath.read(forB, "$.total.amount")).doubleValue()).isEqualTo(250.00);
        assertSellerViewHasNoPrivateData(forB);
        assertThat(forA).doesNotContain(catB.productId()).doesNotContain(buyerA.profileId());
        assertThat(forB).doesNotContain(catA.productId()).doesNotContain(buyerA.profileId());

        // Un vendedor sin ítems en el pedido no puede verlo
        expectForbidden(catA.seller().token(), HttpMethod.GET, "/api/orders/" + orderB, null);
        send(HttpMethod.GET, "/api/orders/" + orderB, catB.seller().token(), null, 200);
    }

    private void assertSellerViewHasNoPrivateData(String json) {
        Map<String, Object> view = JsonPath.read(json, "$");
        assertThat(view).doesNotContainKeys("buyerId", "shippingAddress", "invoiceId");
        assertThat(view).containsKeys("orderId", "items", "status", "total");
    }

    @Test
    void otherRoles_keepTheFullOrderContract() throws Exception {
        for (String token : List.of(buyerA.token(), admin, operator, supervisor)) {
            String full = send(HttpMethod.GET, "/api/orders/" + orderAB, token, null, 200);
            assertThat((int) JsonPath.read(full, "$.items.length()")).isEqualTo(2);
            assertThat(((Number) JsonPath.read(full, "$.total.amount")).doubleValue()).isEqualTo(450.00);
            assertThat((String) JsonPath.read(full, "$.buyerId")).isEqualTo(buyerA.profileId());
            Map<String, Object> view = JsonPath.read(full, "$");
            assertThat(view).containsKeys("shippingAddress", "invoiceId");
        }
    }

    // ------------------------------------------------------------------
    // Facturas, productos no publicados y usuarios
    // ------------------------------------------------------------------

    @Test
    void invoices_notVisibleToSellers_norToOtherBuyers() throws Exception {
        for (String seller : List.of(catA.seller().token(), catB.seller().token())) {
            expectForbidden(seller, HttpMethod.GET, "/api/invoices/" + invoiceB, null);
            expectForbidden(seller, HttpMethod.GET, "/api/orders/" + orderB + "/invoice", null);
        }
        expectForbidden(operator, HttpMethod.GET, "/api/invoices/" + invoiceB, null);
        send(HttpMethod.GET, "/api/invoices/" + invoiceB, admin, null, 200);
        send(HttpMethod.GET, "/api/invoices/" + invoiceB, supervisor, null, 200);
    }

    @Test
    void unpublishedProduct_isHiddenFromThePublic() throws Exception {
        String a = catA.seller().token();
        String draft = read(send(HttpMethod.POST, "/api/products", a,
                body("sellerId", catA.seller().profileId(), "name", "Borrador " + uniq(),
                        "description", "Descripcion valida", "type", "PHYSICAL",
                        "price", moneyJson(BigDecimal.TEN)), 201), "$.productId");
        String path = "/api/products/" + draft;

        assertError(send(HttpMethod.GET, path, null, null, 404), "PRODUCT_NOT_FOUND");
        assertError(send(HttpMethod.GET, path, buyerA.token(), null, 404), "PRODUCT_NOT_FOUND");
        assertError(send(HttpMethod.GET, path, catB.seller().token(), null, 404), "PRODUCT_NOT_FOUND");
        send(HttpMethod.GET, path, a, null, 200);
        send(HttpMethod.GET, path, admin, null, 200);
        send(HttpMethod.GET, path, supervisor, null, 200);

        String listing = send(HttpMethod.GET, "/api/products", null, null, 200);
        assertThat(listing).doesNotContain(draft).contains(catA.productId());
        send(HttpMethod.GET, "/api/products/" + catA.productId(), null, null, 200);
    }

    @Test
    void users_canOnlyReadThemselves_exceptAdminAndSupervisor() throws Exception {
        expectForbidden(buyerA.token(), HttpMethod.GET, "/api/users/" + catA.seller().userId(), null);
        expectForbidden(catA.seller().token(), HttpMethod.GET, "/api/users/" + buyerA.userId(), null);
        send(HttpMethod.GET, "/api/users/" + buyerA.userId(), buyerA.token(), null, 200);
        send(HttpMethod.GET, "/api/users/" + buyerA.userId(), admin, null, 200);
        send(HttpMethod.GET, "/api/users/" + buyerA.userId(), supervisor, null, 200);
    }
}
