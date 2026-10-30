package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;

import nexus.market.domain.valueobjects.SystemRole;

/**
 * Búsqueda por nombre en el catálogo público (el texto del usuario es un literal: los comodines de LIKE no filtran de
 * más) y listado paginado de las facturas del comprador, que solo puede ver las suyas.
 */
class CatalogSearchAndInvoiceListingIntegrationTest extends RestTestSupport {

    private String publishProduct(Account seller, String name) throws Exception {
        String productId = read(send(HttpMethod.POST, "/api/products", seller.token(),
                body("sellerId", seller.profileId(), "name", name, "description", "Descripcion de prueba",
                        "type", "DIGITAL", "price", moneyJson(new BigDecimal("5.00"))), 201), "$.productId");
        send(HttpMethod.POST, "/api/products/" + productId + "/publish", seller.token(), "{}", 200);
        return productId;
    }

    /** Nombres devueltos por el catálogo para el texto {@code q} (como parámetro: MockMvc no lo recodifica). */
    private List<String> names(String q) throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/products").param("size", "100").param("q", q)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return JsonPath.read(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8),
                "$[*].name");
    }

    private MvcResult catalog(String size, String q) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/products");
        if (size != null) {
            request.param("size", size);
        }
        if (q != null) {
            request.param("q", q);
        }
        return mockMvc.perform(request).andReturn();
    }

    @Test
    void search_filtersByNameIgnoringCase_andTreatsWildcardsAsLiterals() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        Account seller = registerApprovedSeller(admin);
        String tag = "zq" + uniq();
        publishProduct(seller, "Lámpara " + tag + " Roja");
        publishProduct(seller, "Mesa " + tag.toUpperCase());
        publishProduct(seller, "Silla comun");
        publishProduct(seller, "Cuaderno 100% algodon " + tag);
        publishProduct(seller, "Libro_" + tag);

        assertThat(names(tag)).hasSize(4).allMatch(n -> n.toLowerCase().contains(tag));
        assertThat(names(tag.toUpperCase())).hasSize(4);
        assertThat(names("LÁMPARA " + tag)).hasSize(1);
        // Los comodines de LIKE no se interpretan: "%" y "_" solo coinciden con esos caracteres.
        assertThat(names("100% algodon " + tag)).hasSize(1).first().asString().contains("100%");
        assertThat(names("Libro_" + tag)).hasSize(1);
        assertThat(names("Libro_" + tag.substring(0, 2) + "%")).isEmpty(); // "%" literal, no comodín
        assertThat(names("%")).allMatch(n -> n.contains("%"));
        assertThat(names("_")).allMatch(n -> n.contains("_"));
        // Sin resultados: lista vacía y total 0.
        MvcResult none = catalog(null, "no-existe-" + uniq());
        assertThat(none.getResponse().getStatus()).isEqualTo(200);
        assertThat(none.getResponse().getHeader("X-Total-Count")).isEqualTo("0");
    }

    @Test
    void search_totalCountMatchesTheFilter_andBlankMeansNoFilter() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        Account seller = registerApprovedSeller(admin);
        String tag = "tc" + uniq();
        publishProduct(seller, "Item " + tag + " A");
        publishProduct(seller, "Item " + tag + " B");
        publishProduct(seller, "Item " + tag + " C");

        MvcResult filtered = catalog("2", tag);
        assertThat(filtered.getResponse().getHeader("X-Total-Count")).isEqualTo("3");
        assertThat((List<?>) JsonPath.read(filtered.getResponse().getContentAsString(), "$")).hasSize(2);

        long all = Long.parseLong(catalog(null, null).getResponse().getHeader("X-Total-Count"));
        long blank = Long.parseLong(catalog(null, "   ").getResponse().getHeader("X-Total-Count"));
        assertThat(blank).isEqualTo(all);
        assertThat(catalog(null, "x".repeat(101)).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void search_neverReturnsUnpublishedProducts() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        Account seller = registerApprovedSeller(admin);
        String tag = "np" + uniq();
        String id = read(send(HttpMethod.POST, "/api/products", seller.token(),
                body("sellerId", seller.profileId(), "name", "Oculto " + tag, "description", "Descripcion de prueba",
                        "type", "DIGITAL", "price", moneyJson(new BigDecimal("5.00"))), 201), "$.productId");
        assertThat(id).isNotBlank();
        assertThat(names(tag)).isEmpty(); // creado pero no publicado
    }

    @Test
    void invoices_areListedPerBuyer_paginated_andPrivate() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog catalog = seedSellerCatalog(admin, new BigDecimal("20.00"));
        Account buyer = registerBuyer();
        Account other = registerBuyer();
        for (int i = 0; i < 3; i++) {
            send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                    body("productId", catalog.productId(), "quantity", 1), 200);
            String orderId = read(send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders",
                    buyer.token(), body("shippingAddress", addressJson()), 201), "$.orderId");
            send(HttpMethod.POST, "/api/orders/" + orderId + "/payment", buyer.token(),
                    body("paymentMethod", "CARD"), 200);
            Thread.sleep(5);
        }
        String base = "/api/buyers/" + buyer.profileId() + "/invoices";

        MvcResult first = perform(HttpMethod.GET, base + "?size=2", buyer.token(), null);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(first.getResponse().getHeader("X-Total-Count")).isEqualTo("3");
        List<String> firstIds = JsonPath.read(first.getResponse().getContentAsString(), "$[*].invoiceId");
        List<String> secondIds = JsonPath.read(send(HttpMethod.GET, base + "?size=2&page=1", buyer.token(), null, 200),
                "$[*].invoiceId");
        assertThat(firstIds).hasSize(2);
        assertThat(secondIds).hasSize(1);
        assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);

        assertThat(status(HttpMethod.GET, base, other.token(), null)).isEqualTo(403);
        assertThat(status(HttpMethod.GET, base, catalog.seller().token(), null)).isEqualTo(403);
        assertThat(status(HttpMethod.GET, base, null, null)).isEqualTo(401);
        assertThat(status(HttpMethod.GET, base + "?size=0", buyer.token(), null)).isEqualTo(400);
        String empty = send(HttpMethod.GET, "/api/buyers/" + other.profileId() + "/invoices", other.token(), null, 200);
        assertThat((List<?>) JsonPath.read(empty, "$")).isEmpty();
    }
}
