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
 * Identificadores legibles: cuentas por correo o documento, código comercial de producto (sellerSku) y respuestas con
 * nombres de vendedor y comprador. El UUID sigue siendo la referencia técnica.
 */
class ReadableIdentifiersIntegrationTest extends RestTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode get(String path, String token, int expected) throws Exception {
        return JSON.readTree(send(HttpMethod.GET, path, token, null, expected));
    }

    /** Alta de un operador logístico con correo y documento conocidos; devuelve su userId. */
    private String createStaff(String admin, String email, String document) throws Exception {
        String created = send(HttpMethod.POST, "/api/auth/register/staff", admin,
                body("fullName", "Juan Pérez", "email", email, "documentId", document, "password", PASSWORD,
                        "role", "LOGISTICS_OPERATOR"), 201);
        return read(created, "$.userId");
    }

    private static String productJson(String sellerId, String name, String sku) {
        return "{\"sellerId\":\"" + sellerId + "\",\"name\":\"" + name + "\",\"description\":\"Producto de prueba\","
                + "\"type\":\"DIGITAL\",\"price\":{\"amount\":900.00,\"currency\":\"COP\"}"
                + (sku == null ? "" : ",\"sellerSku\":\"" + sku + "\"") + "}";
    }

    // ---------------------------------------------------------------- cuentas

    @Test
    void anAdminFindsAnAccountByEmailOrByDocument_withoutCopyingTheUuid() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        String suffix = uniq();
        String email = "juan." + suffix + "@ejemplo.com";
        String document = "10" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
        String userId = createStaff(admin, email, document);

        for (String reference : new String[] { email, email.toUpperCase(), document, userId }) {
            JsonNode user = get("/api/users/" + reference, admin, 200);
            assertThat(user.get("userId").asText()).isEqualTo(userId);
            assertThat(user.get("fullName").asText()).isEqualTo("Juan Pérez");
            assertThat(user.get("email").asText()).isEqualTo(email);
            assertThat(user.get("documentId").asText()).isEqualTo(document);
            assertThat(user.get("role").asText()).isEqualTo("LOGISTICS_OPERATOR");
            assertThat(user.toString()).doesNotContainIgnoringCase("password").doesNotContain(PASSWORD);
        }
        assertThat(get("/api/users/" + email, staffToken(SystemRole.SUPERVISOR), 200).get("userId").asText())
                .isEqualTo(userId);
    }

    @Test
    void aSellerAccountFoundByEmailShowsItsSellerIdAndBusinessName() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        String suffix = uniq();
        String email = "vendedor." + suffix + "@ejemplo.com";
        String registered = send(HttpMethod.POST, "/api/auth/register/seller", admin,
                body("fullName", "Vendedor " + suffix, "email", email, "documentId", "NIT-" + suffix, "password", PASSWORD,
                        "businessName", "Tecnología Pérez " + suffix,
                        "taxId", String.valueOf(ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999))), 201);

        JsonNode user = get("/api/users/" + email, admin, 200);
        assertThat(user.get("sellerId").asText()).isEqualTo(read(registered, "$.sellerId"));
        assertThat(user.get("businessName").asText()).isEqualTo("Tecnología Pérez " + suffix);
        assertThat(user.has("buyerId")).isFalse();
    }

    @Test
    void anAdminBlocksAndUnlocksByEmailAndByDocument() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        String email = "bloqueo." + uniq() + "@ejemplo.com";
        String document = "20" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
        createStaff(admin, email, document);

        send(HttpMethod.PATCH, "/api/users/" + email + "/block", admin, null, 200);
        assertThat(get("/api/users/" + email, admin, 200).get("status").asText()).isEqualTo("BLOCKED");

        send(HttpMethod.PATCH, "/api/users/" + document + "/activate", admin, null, 200);
        assertThat(get("/api/users/" + document, admin, 200).get("status").asText()).isEqualTo("ACTIVE");

        send(HttpMethod.PATCH, "/api/users/" + document + "/deactivate", admin, null, 200);
        assertThat(get("/api/users/" + email, admin, 200).get("status").asText()).isNotEqualTo("ACTIVE");
    }

    @Test
    void nonAdminsCannotManageOrLookUpOtherAccounts_andCannotEnumerateThem() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        String email = "ajeno." + uniq() + "@ejemplo.com";
        String document = "30" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
        createStaff(admin, email, document);
        Account buyer = registerBuyer();

        // Ajena existente e inexistente reciben la misma negativa: no se puede averiguar qué correos están registrados.
        send(HttpMethod.GET, "/api/users/" + email, buyer.token(), null, 403);
        send(HttpMethod.GET, "/api/users/" + document, buyer.token(), null, 403);
        send(HttpMethod.GET, "/api/users/nadie." + uniq() + "@ejemplo.com", buyer.token(), null, 403);
        send(HttpMethod.GET, "/api/users/99999999999", buyer.token(), null, 403);
        send(HttpMethod.PATCH, "/api/users/" + email + "/block", buyer.token(), null, 403);
        send(HttpMethod.PATCH, "/api/users/" + email + "/activate", staffToken(SystemRole.SUPERVISOR), null, 403);
        send(HttpMethod.GET, "/api/users/" + email, null, null, 401);

        // La propia cuenta sí, por UUID.
        assertThat(get("/api/users/" + buyer.userId(), buyer.token(), 200).get("buyerId").asText())
                .isEqualTo(buyer.profileId());

        // Administración sí distingue: inexistente es 404, y un formato de correo inválido es 400.
        send(HttpMethod.GET, "/api/users/nadie." + uniq() + "@ejemplo.com", admin, null, 404);
        send(HttpMethod.GET, "/api/users/a@b", admin, null, 400);
    }

    // ---------------------------------------------------------------- código comercial

    @Test
    void sellerSku_isUniquePerSeller_validated_andShownEverywhere() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog a = seedSellerCatalog(admin, new BigDecimal("1500.00"));
        SellerCatalog b = seedSellerCatalog(admin, new BigDecimal("1500.00"));
        String sku = "TEC-" + uniq().toUpperCase();

        String created = send(HttpMethod.POST, "/api/products", a.seller().token(),
                productJson(a.seller().profileId(), "Portátil HP", sku.toLowerCase()), 201);
        assertThat(read(created, "$.sellerSku")).isEqualTo(sku);               // se normaliza a mayúsculas
        assertThat(read(created, "$.sellerName")).startsWith("Comercial");

        // Duplicado en el mismo vendedor: 409. El mismo código en otro vendedor: permitido.
        send(HttpMethod.POST, "/api/products", a.seller().token(),
                productJson(a.seller().profileId(), "Otro", sku), 409);
        String other = send(HttpMethod.POST, "/api/products", b.seller().token(),
                productJson(b.seller().profileId(), "Portátil HP", sku), 201);
        assertThat(read(other, "$.sellerSku")).isEqualTo(sku);
        assertThat(read(other, "$.productId")).isNotEqualTo(read(created, "$.productId"));

        // Formato inválido.
        for (String bad : new String[] { "TEC 001", "-TEC", "TEC-", "TEC/001", "A".repeat(41) }) {
            send(HttpMethod.POST, "/api/products", a.seller().token(),
                    productJson(a.seller().profileId(), "Malo", bad), 400);
        }

        // Sin código: se genera uno automático y único.
        String auto = send(HttpMethod.POST, "/api/products", a.seller().token(),
                productJson(a.seller().profileId(), "Automático", null), 201);
        assertThat(read(auto, "$.sellerSku")).startsWith("SKU-");
    }

    @Test
    void aProductIsFoundByItsSellerSku_withoutMixingSellers_andOtherSellersCannotReachIt() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog a = seedSellerCatalog(admin, new BigDecimal("1500.00"));
        SellerCatalog b = seedSellerCatalog(admin, new BigDecimal("1500.00"));
        String sku = "MOUSE-" + uniq().toUpperCase();
        String idA = read(send(HttpMethod.POST, "/api/products", a.seller().token(),
                productJson(a.seller().profileId(), "Mouse A", sku), 201), "$.productId");
        String idB = read(send(HttpMethod.POST, "/api/products", b.seller().token(),
                productJson(b.seller().profileId(), "Mouse B", sku), 201), "$.productId");

        String pathA = "/api/sellers/" + a.seller().profileId() + "/products/by-sku/" + sku;
        assertThat(get(pathA, a.seller().token(), 200).get("productId").asText()).isEqualTo(idA);
        assertThat(get(pathA.toLowerCase().replace("/api/sellers/", "/api/sellers/"), a.seller().token(), 200)
                .get("productId").asText()).isEqualTo(idA);
        assertThat(get("/api/sellers/" + b.seller().profileId() + "/products/by-sku/" + sku, admin, 200)
                .get("productId").asText()).isEqualTo(idB);
        assertThat(get(pathA, staffToken(SystemRole.SUPERVISOR), 200).get("productId").asText()).isEqualTo(idA);

        // Otros vendedores, compradores y operadores no acceden a los productos de otro vendedor.
        send(HttpMethod.GET, pathA, b.seller().token(), null, 403);
        send(HttpMethod.GET, pathA, registerBuyer().token(), null, 403);
        send(HttpMethod.GET, pathA, staffToken(SystemRole.LOGISTICS_OPERATOR), null, 403);
        send(HttpMethod.GET, "/api/sellers/" + a.seller().profileId() + "/products/by-sku/NO-EXISTE", a.seller().token(), null, 404);

        // Catálogo público: el código solo se resuelve junto con el vendedor (sin él sería ambiguo).
        send(HttpMethod.POST, "/api/products/" + idA + "/publish", a.seller().token(), "{}", 200);
        send(HttpMethod.GET, "/api/products?sellerSku=" + sku, null, null, 400);
        JsonNode found = get("/api/products?sellerSku=" + sku + "&sellerId=" + a.seller().profileId(), null, 200);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).get("productId").asText()).isEqualTo(idA);
        assertThat(found.get(0).get("sellerName").asText()).startsWith("Comercial");
        // El de B no está publicado: no aparece.
        assertThat(get("/api/products?sellerSku=" + sku + "&sellerId=" + b.seller().profileId(), null, 200)).isEmpty();
    }

    @Test
    void theSkuCanBeChanged_withoutBreakingTheProductRelations() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog cat = seedSellerCatalog(admin, new BigDecimal("1500.00"));
        String seller = cat.seller().token();
        String sku1 = "UNO-" + uniq().toUpperCase();
        String sku2 = "DOS-" + uniq().toUpperCase();
        String first = read(send(HttpMethod.POST, "/api/products", seller,
                productJson(cat.seller().profileId(), "Primero", sku1), 201), "$.productId");
        send(HttpMethod.POST, "/api/products", seller, productJson(cat.seller().profileId(), "Segundo", sku2), 201);

        // Cambiar al código de otro producto propio: 409. Cambiar a uno libre: se actualiza el mismo producto.
        send(HttpMethod.PUT, "/api/products/" + first, seller,
                body("name", "Primero", "description", "Descripción nueva", "sellerSku", sku2), 409);
        String updated = send(HttpMethod.PUT, "/api/products/" + first, seller,
                body("name", "Primero", "description", "Descripción nueva", "sellerSku", sku2 + "-B"), 200);
        assertThat(read(updated, "$.productId")).isEqualTo(first);
        assertThat(read(updated, "$.sellerSku")).isEqualTo(sku2 + "-B");

        // El código anterior quedó libre y ya no apunta al producto.
        send(HttpMethod.GET, "/api/sellers/" + cat.seller().profileId() + "/products/by-sku/" + sku1, seller, null, 404);
        // Sin enviar el código, el producto lo conserva.
        String kept = send(HttpMethod.PUT, "/api/products/" + first, seller,
                body("name", "Primero", "description", "Otra descripción"), 200);
        assertThat(read(kept, "$.sellerSku")).isEqualTo(sku2 + "-B");
    }

    // ---------------------------------------------------------------- carrito y pedidos legibles

    @Test
    void oneCartHoldsThreeProductsFromThreeSellers_withReadableNamesAndCodes() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog[] sellers = {
            seedSellerCatalog(admin, new BigDecimal("1000.00")),
            seedSellerCatalog(admin, new BigDecimal("2000.00")),
            seedSellerCatalog(admin, new BigDecimal("3000.00")) };
        Account buyer = registerBuyer();

        // El comprador ve los tres en el catálogo público, con vendedor y código.
        JsonNode catalog = get("/api/products?size=100", buyer.token(), 200);
        for (SellerCatalog s : sellers) {
            JsonNode entry = null;
            for (JsonNode item : catalog) {
                if (s.productId().equals(item.get("productId").asText())) {
                    entry = item;
                }
            }
            assertThat(entry).as("producto %s publicado en el catálogo", s.productId()).isNotNull();
            assertThat(entry.get("sellerSku").asText()).isNotBlank();
            assertThat(entry.get("sellerName").asText()).startsWith("Comercial");
        }

        for (SellerCatalog s : sellers) {
            send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                    body("productId", s.productId(), "quantity", 1), 200);
        }

        JsonNode cart = get("/api/buyers/" + buyer.profileId() + "/cart", buyer.token(), 200);
        assertThat(cart.get("items")).hasSize(3);
        assertThat(cart.get("buyerName").asText()).isNotBlank();
        assertThat(cart.get("buyerEmail").asText()).contains("@");
        assertThat(cart.at("/total/amount").decimalValue()).isEqualByComparingTo("6000.00");
        java.util.Set<String> sellerIds = new java.util.HashSet<>();
        for (JsonNode line : cart.get("items")) {
            assertThat(line.get("quantity").asInt()).isEqualTo(1);
            assertThat(line.get("sellerSku").asText()).isNotBlank();
            assertThat(line.get("sellerName").asText()).startsWith("Comercial");
            sellerIds.add(line.get("sellerId").asText());
        }
        assertThat(sellerIds).as("tres vendedores distintos en un solo carrito").hasSize(3);

        // Pedido: el comprador ve su correo; el vendedor solo sus líneas (con su código) y sin datos del comprador.
        String orderId = read(send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders", buyer.token(),
                body("shippingAddress", addressJson()), 201), "$.orderId");
        send(HttpMethod.POST, "/api/orders/" + orderId + "/payment", buyer.token(), body("paymentMethod", "CARD"), 200);

        JsonNode asBuyer = get("/api/orders/" + orderId, buyer.token(), 200);
        assertThat(asBuyer.get("items")).hasSize(3);
        assertThat(asBuyer.get("buyerName").asText()).isNotBlank();
        assertThat(asBuyer.get("buyerEmail").asText()).contains("@");
        assertThat(asBuyer.at("/items/0/sellerSku").asText()).isNotBlank();

        JsonNode asAdmin = get("/api/orders/" + orderId, admin, 200);
        assertThat(asAdmin.get("buyerEmail").asText()).contains("@");

        String operatorToken = staffToken(SystemRole.LOGISTICS_OPERATOR);
        assignAndPick(admin, operatorToken, orderId); // el operador solo ve los pedidos que le asignaron
        JsonNode asOperator = get("/api/orders/" + orderId, operatorToken, 200);
        assertThat(asOperator.get("buyerName").asText()).isNotBlank();
        assertThat(asOperator.has("buyerEmail")).as("el operador no necesita el correo del comprador").isFalse();

        JsonNode asSeller = get("/api/orders/" + orderId, sellers[1].seller().token(), 200);
        assertThat(asSeller.get("items")).hasSize(1);
        assertThat(asSeller.at("/items/0/productId").asText()).isEqualTo(sellers[1].productId());
        assertThat(asSeller.at("/items/0/sellerSku").asText()).isNotBlank();
        assertThat(asSeller.toString()).doesNotContain("buyerEmail").doesNotContain("buyerName");

        JsonNode list = get("/api/buyers/" + buyer.profileId() + "/orders", buyer.token(), 200);
        assertThat(list.get(0).get("buyerName").asText()).isNotBlank();
        assertThat(JsonPath.<String>read(list.toString(), "$[0].items[0].sellerName")).startsWith("Comercial");
    }
}
