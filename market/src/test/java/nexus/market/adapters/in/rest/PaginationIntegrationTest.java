package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;

import nexus.market.domain.valueobjects.SystemRole;

/**
 * Los listados son paginados y acotados: el catálogo público ya no devuelve toda la tabla en una petición, y el comprador
 * puede consultar sus propios pedidos (solo los suyos, del más reciente al más antiguo).
 */
class PaginationIntegrationTest extends RestTestSupport {

    @Test
    void pageParams_rejectOutOfRangeValues_insteadOfClampingThem() {
        assertThat(PageParams.of(0, 20).offset()).isZero();
        assertThat(PageParams.of(3, 25).offset()).isEqualTo(75);
        assertThat(PageParams.of(0, PageParams.MAX_SIZE).size()).isEqualTo(100);
        for (int[] invalid : new int[][] { { -1, 20 }, { 0, 0 }, { 0, -5 }, { 0, 101 }, { Integer.MAX_VALUE, 100 } }) {
            assertThatThrownBy(() -> PageParams.of(invalid[0], invalid[1]))
                    .as("page=%d size=%d", invalid[0], invalid[1])
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void publicCatalog_isPaginated_withTotalHeader_andStableOrder() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        for (int i = 0; i < 3; i++) {
            seedSellerCatalog(admin, new BigDecimal("10.00"));
        }
        MvcResult all = perform(HttpMethod.GET, "/api/products?size=100", null, null);
        long total = Long.parseLong(all.getResponse().getHeader("X-Total-Count"));
        assertThat(total).isGreaterThanOrEqualTo(3);

        List<String> ids = new ArrayList<>();
        int pages = (int) Math.ceil(total / 2.0);
        for (int page = 0; page < pages; page++) {
            MvcResult result = perform(HttpMethod.GET, "/api/products?size=2&page=" + page, null, null);
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(result.getResponse().getHeader("X-Total-Count")).isEqualTo(String.valueOf(total));
            List<String> pageIds = JsonPath.read(result.getResponse().getContentAsString(), "$[*].productId");
            assertThat(pageIds).hasSizeLessThanOrEqualTo(2);
            ids.addAll(pageIds);
        }
        // Recorrer las páginas devuelve cada producto exactamente una vez.
        assertThat(ids).hasSize((int) total).doesNotHaveDuplicates();
        // Pedir una página más allá del final devuelve una lista vacía, no un error.
        String empty = send(HttpMethod.GET, "/api/products?size=2&page=" + (pages + 5), null, null, 200);
        assertThat((List<?>) JsonPath.read(empty, "$")).isEmpty();
    }

    @Test
    void publicCatalog_defaultPageIsBounded_andInvalidParametersAre400() throws Exception {
        String defaultPage = send(HttpMethod.GET, "/api/products", null, null, 200);
        assertThat((List<?>) JsonPath.read(defaultPage, "$")).hasSizeLessThanOrEqualTo(20);
        for (String query : new String[] { "?size=0", "?size=101", "?page=-1", "?size=abc", "?page=1.5" }) {
            String body = send(HttpMethod.GET, "/api/products" + query, null, null, 400);
            assertThat((String) JsonPath.read(body, "$.code")).isIn("VALIDATION_ERROR", "INVALID_PARAMETER");
        }
    }

    @Test
    void buyerOrders_listOnlyTheirOwn_newestFirst_andPaginated() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog catalog = seedSellerCatalog(admin, new BigDecimal("10.00"));
        Account buyer = registerBuyer();
        Account other = registerBuyer();
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                    body("productId", catalog.productId(), "quantity", 1), 200);
            created.add(read(send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders", buyer.token(),
                    body("shippingAddress", addressJson()), 201), "$.orderId"));
            send(HttpMethod.POST, "/api/orders/" + created.get(i) + "/cancel", buyer.token(), "{}", 200);
            Thread.sleep(5); // createdAt distinto para comprobar el orden
        }

        String base = "/api/buyers/" + buyer.profileId() + "/orders";
        MvcResult first = perform(HttpMethod.GET, base + "?size=2", buyer.token(), null);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(first.getResponse().getHeader("X-Total-Count")).isEqualTo("3");
        List<String> firstIds = JsonPath.read(first.getResponse().getContentAsString(), "$[*].orderId");
        assertThat(firstIds).hasSize(2);
        String second = send(HttpMethod.GET, base + "?size=2&page=1", buyer.token(), null, 200);
        List<String> secondIds = JsonPath.read(second, "$[*].orderId");
        assertThat(secondIds).hasSize(1);
        List<String> all = new ArrayList<>(firstIds);
        all.addAll(secondIds);
        assertThat(all).containsExactlyInAnyOrderElementsOf(created).doesNotHaveDuplicates();

        // Otro comprador no ve estos pedidos ni puede listarlos con su token; un vendedor tampoco.
        assertThat(status(HttpMethod.GET, base, other.token(), null)).isEqualTo(403);
        assertThat(status(HttpMethod.GET, base, catalog.seller().token(), null)).isEqualTo(403);
        assertThat(status(HttpMethod.GET, base, null, null)).isEqualTo(401);
        assertThat(status(HttpMethod.GET, base + "?size=1000", buyer.token(), null)).isEqualTo(400);
        String ownEmpty = send(HttpMethod.GET, "/api/buyers/" + other.profileId() + "/orders", other.token(), null, 200);
        assertThat((List<?>) JsonPath.read(ownEmpty, "$")).isEmpty();
    }
}
