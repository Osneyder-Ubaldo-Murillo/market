package nexus.market.adapters.in.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.jayway.jsonpath.JsonPath;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import nexus.market.domain.models.User;
import nexus.market.domain.ports.out.JwtServicePort;
import nexus.market.domain.ports.out.UserRepositoryPort;
import nexus.market.domain.valueobjects.DocumentId;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.FullName;
import nexus.market.domain.valueobjects.SystemRole;

/**
 * Base de las pruebas REST autenticadas (H2 + perfil {@code jpa}, cadena de
 * seguridad real). Los usuarios ADMIN, LOGISTICS_OPERATOR y SUPERVISOR se
 * crean a través de los puertos de repositorio porque la API no ofrece (aún)
 * un camino para crearlos; compradores y vendedores se registran por la API.
 */
@ActiveProfiles("jpa")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nexusmarket_rest;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.data.mongodb.uri=",
        "spring.data.mongodb.database=test_audit",
        // Las pruebas comparten la IP 127.0.0.1 y hacen muchos logins: el límite por cliente no debe interferir.
        // El límite de login se prueba en LoginRateLimitIntegrationTest con su propia configuración.
        "nexusmarket.login.max-per-client=100000",
        "nexusmarket.login.max-per-email=100000",
        "nexusmarket.login.max-per-client-and-email=100000",
        "nexusmarket.registration.max-per-client=100000",
        "nexusmarket.registration.max-per-email=100000",
        "nexusmarket.recovery.max-per-client=100000",
        "nexusmarket.recovery.max-per-email=100000",
        "nexusmarket.verification.max-per-client=100000",
        "nexusmarket.verification.max-per-email=100000"
})
public abstract class RestTestSupport {

    public static final String PASSWORD = "Secreta123!";

    /** Perfil de comprador o vendedor registrado: id del perfil, token y datos de acceso. */
    public record Account(String profileId, String userId, String token) {
    }

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected UserRepositoryPort users;
    @Autowired
    protected JwtServicePort jwt;

    // ------------------------------------------------------------------
    // Usuarios
    // ------------------------------------------------------------------

    protected static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Crea un usuario con el rol dado (sin pasar por la API) y devuelve su token. */
    protected String staffToken(SystemRole role) {
        String suffix = uniq();
        User user = users.save(User.create(FullName.of(role.getCode() + " " + suffix),
                Email.of(role.getCode().toLowerCase() + "-" + suffix + "@mail.test"),
                DocumentId.of("ST-" + suffix), role));
        return jwt.issue(user.getUserId(), role);
    }

    protected Account registerBuyer() throws Exception {
        String suffix = uniq();
        String email = "buyer-" + suffix + "@mail.test";
        String registered = send(HttpMethod.POST, "/api/auth/register/buyer", null,
                body("fullName", "Comprador " + suffix, "email", email, "documentId", "CC-" + suffix,
                        "password", PASSWORD, "address", addressJson()), 201);
        verifyEmail(JsonPath.read(registered, "$.userId"));
        return login(JsonPath.read(registered, "$.buyerId"), email);
    }

    /** Verifica el correo del usuario como lo haría el enlace recibido: con un código válido en el endpoint público. */
    protected void verifyEmail(String userId) throws Exception {
        String code = jwt.issueEmailVerificationToken(nexus.market.domain.valueobjects.UserId.of(userId),
                java.time.Duration.ofHours(1));
        send(HttpMethod.POST, "/api/auth/email/verify", null, body("token", code), 200);
    }

    /** Registra un vendedor con token ADMIN, lo aprueba y le crea una bodega propia. */
    protected Account registerApprovedSeller(String adminToken) throws Exception {
        String suffix = uniq();
        String email = "seller-" + suffix + "@mail.test";
        String registered = send(HttpMethod.POST, "/api/auth/register/seller", adminToken,
                body("fullName", "Vendedor " + suffix, "email", email, "documentId", "NIT-" + suffix,
                        "password", PASSWORD, "businessName", "Comercial " + suffix,
                        "taxId", String.valueOf(ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999))),
                201);
        String sellerId = JsonPath.read(registered, "$.sellerId");
        send(HttpMethod.POST, "/api/sellers/" + sellerId + "/approve", adminToken, "{}", 200);
        verifyEmail(JsonPath.read(registered, "$.userId"));
        return login(sellerId, email);
    }

    private Account login(String profileId, String email) throws Exception {
        String logged = send(HttpMethod.POST, "/api/auth/login", null,
                body("email", email, "password", PASSWORD), 200);
        return new Account(profileId, JsonPath.read(logged, "$.userId"), JsonPath.read(logged, "$.token"));
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    protected MvcResult perform(HttpMethod method, String path, String token, String json) throws Exception {
        return performFrom(null, method, path, token, json);
    }

    /** Igual que {@link #perform} pero con otra dirección de cliente ({@code null}: la de MockMvc por defecto). */
    protected MvcResult performFrom(String remoteAddress, HttpMethod method, String path, String token, String json)
            throws Exception {
        MockHttpServletRequestBuilder builder = request(method, path);
        if (remoteAddress != null) {
            builder.with(request -> {
                request.setRemoteAddr(remoteAddress);
                return request;
            });
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (json != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(builder).andReturn();
    }

    protected int status(HttpMethod method, String path, String token, String json) throws Exception {
        return perform(method, path, token, json).getResponse().getStatus();
    }

    /** Ejecuta la petición, exige el estado esperado y devuelve el cuerpo. */
    protected String send(HttpMethod method, String path, String token, String json, int expected)
            throws Exception {
        MvcResult result = perform(method, path, token, json);
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                .as("%s %s -> %s", method, path, content)
                .isEqualTo(expected);
        return content;
    }

    protected static String read(String json, String jsonPath) {
        return JsonPath.read(json, jsonPath);
    }

    // ------------------------------------------------------------------
    // Cuerpos JSON
    // ------------------------------------------------------------------

    /** Construye JSON simple; los valores que empiezan con {@code {} o {@code [} o son numéricos van sin comillas. */
    protected static String body(Object... kv) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                json.append(',');
            }
            Object value = kv[i + 1];
            json.append('"').append(kv[i]).append("\":");
            if (value instanceof String s && !s.startsWith("{") && !s.startsWith("[")
                    && !s.matches("-?\\d+(\\.\\d+)?")) {
                json.append('"').append(s).append('"');
            } else {
                json.append(value);
            }
        }
        return json.append('}').toString();
    }

    protected static String addressJson() {
        return "{\"street\":\"Calle 1\",\"number\":\"12-34\",\"complement\":\"Piso 3\","
                + "\"neighborhood\":\"Centro\",\"city\":\"Bogota\",\"state\":\"Cundinamarca\","
                + "\"postalCode\":\"110111\",\"country\":\"CO\"}";
    }

    protected static String moneyJson(BigDecimal amount) {
        return "{\"amount\":" + amount + ",\"currency\":\"COP\"}";
    }

    // ------------------------------------------------------------------
    // Catálogo de ayuda
    // ------------------------------------------------------------------

    /** Producto físico del vendedor, con bodega propia, 10 unidades y publicado. */
    public record SellerCatalog(Account seller, String warehouseId, String productId, String inventoryId) {
    }

    protected SellerCatalog seedSellerCatalog(String adminToken, BigDecimal price) throws Exception {
        Account seller = registerApprovedSeller(adminToken);
        String suffix = uniq();
        String warehouseId = read(send(HttpMethod.POST, "/api/sellers/" + seller.profileId() + "/warehouses",
                adminToken, body("name", "Bodega " + suffix, "address", addressJson()), 201), "$.warehouseId");
        String productId = read(send(HttpMethod.POST, "/api/products", seller.token(),
                body("sellerId", seller.profileId(), "name", "Producto " + suffix, "description", "Descripcion",
                        "type", "PHYSICAL", "price", moneyJson(price)), 201), "$.productId");
        String inventoryId = read(send(HttpMethod.POST, "/api/inventory", seller.token(),
                body("productId", productId, "warehouseId", warehouseId), 201), "$.inventoryId");
        send(HttpMethod.POST, "/api/inventory/" + inventoryId + "/stock", seller.token(),
                body("quantity", 10), 200);
        send(HttpMethod.POST, "/api/products/" + productId + "/publish", seller.token(), "{}", 200);
        return new SellerCatalog(seller, warehouseId, productId, inventoryId);
    }
}
