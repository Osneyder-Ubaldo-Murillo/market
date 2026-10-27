package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;

import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.UserId;

/**
 * Perfil de desarrollo: el estado y el rol del usuario se verifican en cada
 * petición (no se confía en el token), CORS solo admite los orígenes locales
 * explícitos y las rutas viven bajo {@code /api} sin prefijo duplicado.
 */
class TokenAndCorsIntegrationTest extends RestTestSupport {

    @org.springframework.beans.factory.annotation.Autowired
    private nexus.market.application.CredentialStorePort credentialStore;

    @Test
    void blockedUser_losesAccessImmediately_andRegainsItWhenReactivated() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        Account buyer = registerBuyer();
        String me = "/api/users/" + buyer.userId();

        send(HttpMethod.GET, me, buyer.token(), null, 200);

        send(HttpMethod.PATCH, me + "/block", admin, "{}", 200);
        String denied = send(HttpMethod.GET, me, buyer.token(), null, 401);
        assertThat((String) JsonPath.read(denied, "$.code")).isEqualTo("UNAUTHORIZED");
        assertThat(status(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                body("productId", UUID.randomUUID().toString(), "quantity", 1))).isEqualTo(401);

        send(HttpMethod.PATCH, me + "/activate", admin, "{}", 200);
        send(HttpMethod.GET, me, buyer.token(), null, 200);

        // Un usuario desactivado tampoco opera con su token vigente.
        send(HttpMethod.PATCH, me + "/deactivate", admin, "{}", 200);
        send(HttpMethod.GET, me, buyer.token(), null, 401);
    }

    @Test
    void tokenOfUnknownUser_isRejected_evenWithAdminClaim() throws Exception {
        String forged = jwt.issue(UserId.generate(), SystemRole.ADMIN);
        String body = send(HttpMethod.GET, "/api/users/" + UUID.randomUUID(), forged, null, 401);
        assertThat((String) JsonPath.read(body, "$.code")).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void roleComesFromDatabase_notFromTokenClaim() throws Exception {
        Account buyer = registerBuyer();
        // Token firmado, vigente y con la huella de credencial correcta del comprador, pero con el claim de rol ADMIN:
        // el rol sale de la base de datos, no del token.
        String hash = credentialStore.findByUserId(UserId.of(buyer.userId())).orElseThrow();
        String escalated = jwt.issue(UserId.of(buyer.userId()), SystemRole.ADMIN, jwt.credentialVersion(hash));
        // Sin la huella de la credencial (token fabricado con otro rol) ni siquiera se autentica: 401.
        assertThat(status(HttpMethod.GET, "/api/users/" + buyer.userId(),
                jwt.issue(UserId.of(buyer.userId()), SystemRole.ADMIN), null)).isEqualTo(401);
        send(HttpMethod.POST, "/api/auth/register/seller", escalated,
                body("fullName", "X Y", "email", "x-" + uniq() + "@mail.test", "documentId", "NIT-" + uniq(),
                        "password", PASSWORD, "businessName", "Comercial", "taxId", "12345678"), 403);
    }

    @Test
    void cors_allowsOnlyConfiguredLocalOrigins_withCredentials() throws Exception {
        MvcResult allowed = mockMvc.perform(options("/api/products")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET")).andReturn();
        assertThat(allowed.getResponse().getStatus()).isEqualTo(200);
        assertThat(allowed.getResponse().getHeader("Access-Control-Allow-Origin"))
                .isEqualTo("http://localhost:3000");
        assertThat(allowed.getResponse().getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");

        MvcResult rejected = mockMvc.perform(options("/api/products")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "GET")).andReturn();
        assertThat(rejected.getResponse().getStatus()).isEqualTo(403);
        assertThat(rejected.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void routes_liveUnderSingleApiPrefix() throws Exception {
        assertThat(status(HttpMethod.GET, "/api/products", null, null)).isEqualTo(200);
        assertThat(status(HttpMethod.GET, "/api/api/products", null, null)).isIn(401, 404);
    }

    @Test
    void swagger_isAvailableInDevelopment() throws Exception {
        // La documentación se genera de verdad (springdoc compatible con Boot 4): 200 y un OpenAPI válido.
        String docs = send(HttpMethod.GET, "/v3/api-docs", null, null, 200);
        assertThat((String) JsonPath.read(docs, "$.openapi")).startsWith("3.");
        assertThat((Object) JsonPath.read(docs, "$.paths['/api/products']")).isNotNull();
    }
}
