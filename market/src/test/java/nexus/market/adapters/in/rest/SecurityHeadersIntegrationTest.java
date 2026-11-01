package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Cabeceras HTTP de seguridad y ausencia de sesión del servidor. La API es stateless (JWT en {@code Authorization}): no emite
 * cookies de sesión, por lo que CSRF no aplica; las respuestas llevan las cabeceras por defecto de Spring Security también
 * cuando son errores.
 *
 * <p>HSTS solo lo emite la aplicación para peticiones que ella ve como HTTPS. Con TLS terminado en un proxy inverso (lo
 * habitual) la aplicación ve HTTP, así que el proxy debe añadir {@code Strict-Transport-Security} (ver ENTORNOS.md).</p>
 */
class SecurityHeadersIntegrationTest extends RestTestSupport {

    private static void assertSecurityHeaders(MvcResult result) {
        var response = result.getResponse();
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(response.getHeader(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(response.getHeader("X-Powered-By")).isNull();
    }

    @Test
    void publicResponses_carryTheSecurityHeaders() throws Exception {
        assertSecurityHeaders(perform(HttpMethod.GET, "/api/products", null, null));
        assertSecurityHeaders(perform(HttpMethod.GET, "/actuator/health", null, null));
    }

    @Test
    void errorResponses_carryTheSecurityHeaders_tooAndNeverASessionCookie() throws Exception {
        MvcResult unauthorized = perform(HttpMethod.GET, "/api/orders/" + java.util.UUID.randomUUID(), null, null);
        assertThat(unauthorized.getResponse().getStatus()).isEqualTo(401);
        assertSecurityHeaders(unauthorized);
        assertThat(unauthorized.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();

        MvcResult forbidden = perform(HttpMethod.GET, "/api/no-existe", staffToken(nexus.market.domain.valueobjects.SystemRole.ADMIN), null);
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        assertSecurityHeaders(forbidden);
    }

    @Test
    void login_isStateless_noCookieIsIssued_andNoServerSessionIsCreated() throws Exception {
        Account account = registerBuyer();
        String email = users.findById(nexus.market.domain.valueobjects.UserId.of(account.userId())).orElseThrow()
                .getEmail().getValue();
        MvcResult login = perform(HttpMethod.POST, "/api/auth/login", null, body("email", email, "password", PASSWORD));

        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        assertThat(login.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
        assertThat(login.getRequest().getSession(false)).isNull();
        assertSecurityHeaders(login);
    }

    @Test
    void strictTransportSecurity_isEmittedForRequestsTheApplicationSeesAsHttps_only() throws Exception {
        MvcResult https = mockMvc.perform(get("/api/products").secure(true)).andReturn();
        assertThat(https.getResponse().getHeader("Strict-Transport-Security")).contains("max-age=");

        MvcResult plain = mockMvc.perform(get("/api/products")).andReturn();
        assertThat(plain.getResponse().getHeader("Strict-Transport-Security")).isNull();
    }
}
