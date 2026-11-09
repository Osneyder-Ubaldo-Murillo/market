package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import nexus.market.domain.valueobjects.SystemRole;

/**
 * El vendedor ve sus productos en cualquier estado; administración y supervisión ven los de cualquier vendedor; nadie más.
 */
class SellerProductsListingIntegrationTest extends RestTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String path(SellerCatalog cat, String query) {
        return "/api/sellers/" + cat.seller().profileId() + "/products" + query;
    }

    private static String digital(SellerCatalog cat, String name) {
        return "{\"sellerId\":\"" + cat.seller().profileId() + "\",\"name\":\"" + name
                + "\",\"description\":\"Producto de prueba\",\"type\":\"DIGITAL\",\"price\":{\"amount\":900.00,\"currency\":\"COP\"}}";
    }

    private JsonNode list(String path, String token, int expected) throws Exception {
        return JSON.readTree(send(HttpMethod.GET, path, token, null, expected));
    }

    private static boolean contains(JsonNode list, String productId) {
        for (JsonNode item : list) {
            if (productId.equals(item.get("productId").asText())) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theSellerSeesAllItsProducts_inEveryStatus_andCanFilterByStatus() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog cat = seedSellerCatalog(admin, new BigDecimal("1500.00"));
        String seller = cat.seller().token();

        // Un segundo producto, aún sin publicar (INACTIVE): no sale en el catálogo público pero sí en el listado propio.
        String draft = read(send(HttpMethod.POST, "/api/products", seller,
                digital(cat, "Borrador " + uniq()), 201), "$.productId");

        JsonNode all = list(path(cat, ""), seller, 200);
        assertThat(contains(all, cat.productId())).isTrue();
        assertThat(contains(all, draft)).isTrue();

        assertThat(contains(list(path(cat, "?status=ACTIVE"), seller, 200), draft)).isFalse();
        JsonNode inactive = list(path(cat, "?status=inactive"), seller, 200);
        assertThat(contains(inactive, draft)).isTrue();
        assertThat(contains(inactive, cat.productId())).isFalse();

        // Suspendido y descontinuado también aparecen (el catálogo público ya no los muestra).
        send(HttpMethod.POST, "/api/products/" + cat.productId() + "/suspend", seller, "{}", 200);
        assertThat(contains(list(path(cat, "?status=SUSPENDED"), seller, 200), cat.productId())).isTrue();
        send(HttpMethod.POST, "/api/products/" + cat.productId() + "/discontinue", seller, "{}", 200);
        assertThat(contains(list(path(cat, "?status=DISCONTINUED"), seller, 200), cat.productId())).isTrue();
    }

    @Test
    void itIsPaginated_withTheTotalInTheHeader_andRejectsInvalidParameters() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog cat = seedSellerCatalog(admin, new BigDecimal("1500.00"));
        for (int i = 0; i < 2; i++) {
            send(HttpMethod.POST, "/api/products", cat.seller().token(),
                    digital(cat, "Extra " + i + " " + uniq()), 201);
        }

        var response = perform(HttpMethod.GET, path(cat, "?page=0&size=2"), cat.seller().token(), null).getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(response.getContentAsString())).hasSize(2);
        assertThat(response.getHeader(PageParams.TOTAL_COUNT_HEADER)).isEqualTo("3");

        send(HttpMethod.GET, path(cat, "?size=0"), cat.seller().token(), null, 400);
        send(HttpMethod.GET, path(cat, "?size=101"), cat.seller().token(), null, 400);
        send(HttpMethod.GET, path(cat, "?status=NOPE"), cat.seller().token(), null, 400);
    }

    @Test
    void adminAndSupervisorSeeAnySeller_butOtherSellersBuyersAndOperatorsDoNot() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog mine = seedSellerCatalog(admin, new BigDecimal("1500.00"));
        SellerCatalog other = seedSellerCatalog(admin, new BigDecimal("1500.00"));

        assertThat(contains(list(path(mine, ""), admin, 200), mine.productId())).isTrue();
        assertThat(contains(list(path(mine, ""), staffToken(SystemRole.SUPERVISOR), 200), mine.productId())).isTrue();

        // No se mezclan los productos de otro vendedor.
        assertThat(contains(list(path(mine, ""), admin, 200), other.productId())).isFalse();

        send(HttpMethod.GET, path(mine, ""), other.seller().token(), null, 403);
        send(HttpMethod.GET, path(mine, ""), registerBuyer().token(), null, 403);
        send(HttpMethod.GET, path(mine, ""), staffToken(SystemRole.LOGISTICS_OPERATOR), null, 403);
        send(HttpMethod.GET, path(mine, ""), null, null, 401);
    }
}
