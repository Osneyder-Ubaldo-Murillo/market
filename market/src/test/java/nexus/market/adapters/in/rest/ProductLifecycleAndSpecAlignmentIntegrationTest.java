package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

import nexus.market.domain.valueobjects.SystemRole;

/**
 * Alineación con la especificación funcional: el vendedor que incorpora el administrador queda activo; el producto pasa por
 * Publicado, Suspendido y Descontinuado; tiene variantes; y el operador logístico administra inventario.
 */
class ProductLifecycleAndSpecAlignmentIntegrationTest extends RestTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Un producto no publicado solo lo ve su vendedor (o administración): se consulta con el token del dueño. */
    private JsonNode product(String productId, String ownerToken) throws Exception {
        return JSON.readTree(send(HttpMethod.GET, "/api/products/" + productId, ownerToken, null, 200));
    }

    private boolean inPublicCatalog(String productId) throws Exception {
        JsonNode list = JSON.readTree(send(HttpMethod.GET, "/api/products?size=100&q=", null, null, 200));
        for (JsonNode item : list) {
            if (productId.equals(item.get("productId").asText())) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aSellerRegisteredByTheAdmin_isActiveFromTheStart_andThereIsNothingLeftToApprove() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        String suffix = uniq();
        String registered = send(HttpMethod.POST, "/api/auth/register/seller", admin,
                body("fullName", "Vendedor " + suffix, "email", "v-" + suffix + "@mail.test", "documentId", "NIT-" + suffix,
                        "password", PASSWORD, "businessName", "Comercial " + suffix,
                        "taxId", String.valueOf(ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999))), 201);
        String sellerId = JsonPath.read(registered, "$.sellerId");

        // Ya activo: una segunda aprobación no tiene sentido.
        send(HttpMethod.POST, "/api/sellers/" + sellerId + "/approve", admin, "{}", 409);
        // Y puede operar en cuanto verifica el correo y el administrador le crea la bodega.
        verifyEmail(JsonPath.read(registered, "$.userId"));
    }

    @Test
    void aProductCanBeSuspendedAndRepublished_butOnceDiscontinuedItIsFinal() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog cat = seedSellerCatalog(admin, new BigDecimal("1500.00"));
        String id = cat.productId();
        String seller = cat.seller().token();
        Account buyer = registerBuyer();
        assertThat(product(id, seller).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(inPublicCatalog(id)).isTrue();

        // Suspendido: sale del catálogo y no se puede comprar.
        send(HttpMethod.POST, "/api/products/" + id + "/suspend", seller, "{}", 200);
        assertThat(product(id, seller).get("status").asText()).isEqualTo("SUSPENDED");
        assertThat(inPublicCatalog(id)).isFalse();
        send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                body("productId", id, "quantity", 1), 400);
        send(HttpMethod.POST, "/api/products/" + id + "/suspend", seller, "{}", 409);

        // Publicar de nuevo lo reactiva.
        send(HttpMethod.POST, "/api/products/" + id + "/publish", seller, "{}", 200);
        assertThat(inPublicCatalog(id)).isTrue();
        send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                body("productId", id, "quantity", 1), 200);

        // Descontinuado: definitivo (no se publica, no se edita, no se compra).
        send(HttpMethod.POST, "/api/products/" + id + "/discontinue", seller, "{}", 200);
        assertThat(product(id, seller).get("status").asText()).isEqualTo("DISCONTINUED");
        assertThat(inPublicCatalog(id)).isFalse();
        send(HttpMethod.POST, "/api/products/" + id + "/publish", seller, "{}", 409);
        send(HttpMethod.POST, "/api/products/" + id + "/suspend", seller, "{}", 409);
        send(HttpMethod.POST, "/api/products/" + id + "/discontinue", seller, "{}", 409);
        send(HttpMethod.PUT, "/api/products/" + id + "/price", seller,
                "{\"price\":{\"amount\":10.00,\"currency\":\"COP\"}}", 409);
        send(HttpMethod.POST, "/api/products/" + id + "/variants", seller, body("variant", "Rojo"), 409);
    }

    @Test
    void onlyTheOwnerSellerChangesTheStatus() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog mine = seedSellerCatalog(admin, new BigDecimal("1500.00"));
        SellerCatalog other = seedSellerCatalog(admin, new BigDecimal("1500.00"));
        Account buyer = registerBuyer();

        for (String action : new String[] { "suspend", "discontinue" }) {
            send(HttpMethod.POST, "/api/products/" + mine.productId() + "/" + action, other.seller().token(), "{}", 403);
            send(HttpMethod.POST, "/api/products/" + mine.productId() + "/" + action, buyer.token(), "{}", 403);
            send(HttpMethod.POST, "/api/products/" + mine.productId() + "/" + action, admin, "{}", 403);
        }
        send(HttpMethod.POST, "/api/products/" + mine.productId() + "/variants", other.seller().token(),
                body("variant", "Rojo"), 403);
    }

    @Test
    void variantsAreAddedAndRemoved_withoutDuplicatesOrBlanks() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog cat = seedSellerCatalog(admin, new BigDecimal("1500.00"));
        String path = "/api/products/" + cat.productId() + "/variants";
        String seller = cat.seller().token();

        send(HttpMethod.POST, path, seller, body("variant", "Rojo"), 200);
        String second = send(HttpMethod.POST, path, seller, body("variant", "Talla M"), 200);
        assertThat((java.util.List<String>) JsonPath.read(second, "$.specifications.variants"))
                .containsExactly("Rojo", "Talla M");
        assertThat(product(cat.productId(), seller).at("/specifications/variants").size()).isEqualTo(2);

        send(HttpMethod.POST, path, seller, body("variant", "rojo"), 409);        // repetida (sin distinguir mayúsculas)
        send(HttpMethod.POST, path, seller, body("variant", "   "), 400);         // vacía
        send(HttpMethod.POST, path, seller, body("variant", "x".repeat(51)), 400); // demasiado larga

        String removed = send(HttpMethod.DELETE, path + "/Rojo", seller, null, 200);
        assertThat((java.util.List<String>) JsonPath.read(removed, "$.specifications.variants")).containsExactly("Talla M");
        send(HttpMethod.DELETE, path + "/Inexistente", seller, null, 404);
    }

    @Test
    void theLogisticsOperatorManagesInventory_whileOtherRolesStayOut() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        String operator = staffToken(SystemRole.LOGISTICS_OPERATOR);
        SellerCatalog cat = seedSellerCatalog(admin, new BigDecimal("1500.00"));
        String inventory = "/api/inventory/" + cat.inventoryId();

        int before = JsonPath.read(send(HttpMethod.GET, inventory, operator, null, 200), "$.quantity");
        send(HttpMethod.POST, inventory + "/stock", operator, body("quantity", 4), 200);
        assertThat((int) JsonPath.read(send(HttpMethod.GET, inventory, operator, null, 200), "$.quantity"))
                .isEqualTo(before + 4);
        send(HttpMethod.PUT, inventory + "/quantity", operator, body("quantity", 3), 200);
        send(HttpMethod.POST, inventory + "/damaged", operator, "{}", 200);
        send(HttpMethod.POST, inventory + "/active", operator, "{}", 200);

        // El comprador y el supervisor siguen sin poder modificarlo.
        send(HttpMethod.POST, inventory + "/stock", registerBuyer().token(), body("quantity", 1), 403);
        send(HttpMethod.POST, inventory + "/stock", staffToken(SystemRole.SUPERVISOR), body("quantity", 1), 403);
    }
}
