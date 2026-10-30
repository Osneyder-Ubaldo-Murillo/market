package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import nexus.market.domain.valueobjects.SystemRole;

/**
 * El vendedor dueño edita el nombre y la descripción de su producto; el tipo, el vendedor, el precio y el estado no
 * cambian. Nadie más puede editarlo, y los pedidos ya hechos conservan el nombre con que se compraron.
 */
class ProductEditIntegrationTest extends RestTestSupport {

    private static final String NEW_DESCRIPTION = "Descripcion actualizada del producto";

    private String edit(String productId, String token, String name, String description, int expected)
            throws Exception {
        return send(HttpMethod.PUT, "/api/products/" + productId, token,
                body("name", name, "description", description), expected);
    }

    @Test
    void owner_editsNameAndDescription_keepingPriceStatusAndType() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog catalog = seedSellerCatalog(admin, new BigDecimal("42.50"));
        String before = send(HttpMethod.GET, "/api/products/" + catalog.productId(), null, null, 200);

        String updated = edit(catalog.productId(), catalog.seller().token(), "Nombre nuevo " + uniq(), NEW_DESCRIPTION,
                200);

        assertThat((String) JsonPath.read(updated, "$.description")).isEqualTo(NEW_DESCRIPTION);
        assertThat((String) JsonPath.read(updated, "$.name")).startsWith("Nombre nuevo");
        for (String field : new String[] { "$.status", "$.type", "$.sellerId", "$.price.amount", "$.price.currency" }) {
            assertThat((Object) JsonPath.read(updated, field)).as(field).isEqualTo(JsonPath.read(before, field));
        }
        // El cambio queda persistido y es visible en el catálogo público.
        String reloaded = send(HttpMethod.GET, "/api/products/" + catalog.productId(), null, null, 200);
        assertThat((String) JsonPath.read(reloaded, "$.name")).isEqualTo(JsonPath.read(updated, "$.name"));
    }

    @Test
    void onlyTheOwningSeller_canEdit() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog catalog = seedSellerCatalog(admin, new BigDecimal("10.00"));
        SellerCatalog other = seedSellerCatalog(admin, new BigDecimal("10.00"));
        Account buyer = registerBuyer();

        edit(catalog.productId(), other.seller().token(), "Intruso", NEW_DESCRIPTION, 403);
        edit(catalog.productId(), buyer.token(), "Intruso", NEW_DESCRIPTION, 403);
        edit(catalog.productId(), admin, "Intruso", NEW_DESCRIPTION, 403);
        edit(catalog.productId(), null, "Intruso", NEW_DESCRIPTION, 401);
        // Un producto inexistente no revela nada al vendedor: 403 como cualquier recurso ajeno.
        edit(java.util.UUID.randomUUID().toString(), catalog.seller().token(), "Nada", NEW_DESCRIPTION, 403);

        String current = send(HttpMethod.GET, "/api/products/" + catalog.productId(), null, null, 200);
        assertThat((String) JsonPath.read(current, "$.name")).doesNotContain("Intruso");
    }

    @Test
    void invalidData_is400_andChangesNothing() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog catalog = seedSellerCatalog(admin, new BigDecimal("10.00"));
        String original = send(HttpMethod.GET, "/api/products/" + catalog.productId(), null, null, 200);
        String token = catalog.seller().token();

        edit(catalog.productId(), token, "", NEW_DESCRIPTION, 400);
        edit(catalog.productId(), token, "   ", NEW_DESCRIPTION, 400);
        edit(catalog.productId(), token, "x".repeat(101), NEW_DESCRIPTION, 400);
        edit(catalog.productId(), token, "Nombre valido", "corta", 400);
        edit(catalog.productId(), token, "Nombre valido", "d".repeat(501), 400);
        send(HttpMethod.PUT, "/api/products/" + catalog.productId(), token, "{}", 400);
        send(HttpMethod.PUT, "/api/products/" + catalog.productId(), token, "{\"name\": ", 400);

        assertThat(send(HttpMethod.GET, "/api/products/" + catalog.productId(), null, null, 200)).isEqualTo(original);
    }

    @Test
    void fieldsThatAreNotEditable_areIgnored() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog catalog = seedSellerCatalog(admin, new BigDecimal("10.00"));
        String updated = send(HttpMethod.PUT, "/api/products/" + catalog.productId(), catalog.seller().token(),
                body("name", "Nombre " + uniq(), "description", NEW_DESCRIPTION, "price", moneyJson(new BigDecimal("1.00")),
                        "status", "INACTIVE", "sellerId", java.util.UUID.randomUUID().toString(), "type", "DIGITAL"),
                200);
        assertThat(((Number) JsonPath.read(updated, "$.price.amount")).doubleValue()).isEqualTo(10.00);
        assertThat((String) JsonPath.read(updated, "$.status")).isEqualTo("ACTIVE");
        assertThat((String) JsonPath.read(updated, "$.sellerId")).isEqualTo(catalog.seller().profileId());
        assertThat((String) JsonPath.read(updated, "$.type")).isEqualTo("PHYSICAL");
    }

    @Test
    void existingOrders_keepTheNameTheyWereBoughtWith() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog catalog = seedSellerCatalog(admin, new BigDecimal("10.00"));
        Account buyer = registerBuyer();
        send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                body("productId", catalog.productId(), "quantity", 1), 200);
        String orderId = read(send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders", buyer.token(),
                body("shippingAddress", addressJson()), 201), "$.orderId");
        String originalName = JsonPath.read(send(HttpMethod.GET, "/api/products/" + catalog.productId(), null, null, 200),
                "$.name");

        edit(catalog.productId(), catalog.seller().token(), "Renombrado " + uniq(), NEW_DESCRIPTION, 200);

        String order = send(HttpMethod.GET, "/api/orders/" + orderId, buyer.token(), null, 200);
        assertThat(order).contains(originalName).doesNotContain("Renombrado");
    }
}
