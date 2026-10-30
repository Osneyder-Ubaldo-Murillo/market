package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Los endpoints públicos que crean cuentas o emiten avisos tienen cupo propio: el registro (sondeo de correos y cuentas
 * masivas) y la solicitud de recuperación de contraseña (inundar de avisos a una persona) responden 429 con
 * {@code Retry-After} al agotarlo.
 */
@ActiveProfiles("jpa")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nexusmarket_publiclimit;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.data.mongodb.uri=",
        "spring.data.mongodb.database=test_audit",
        "nexusmarket.login.max-per-client=100000",
        "nexusmarket.login.max-per-email=100000",
        "nexusmarket.registration.max-per-email=2",
        "nexusmarket.registration.max-per-client=1000",
        "nexusmarket.recovery.max-per-email=2",
        "nexusmarket.recovery.max-per-client=1000"
})
class PublicEndpointsRateLimitIntegrationTest extends RestTestSupport {

    private MvcResult register(String email, String suffix) throws Exception {
        return perform(HttpMethod.POST, "/api/auth/register/buyer", null,
                body("fullName", "Persona " + suffix, "email", email, "documentId", "CC-" + suffix,
                        "password", PASSWORD, "address", addressJson()));
    }

    @Test
    void registration_isLimitedPerEmail() throws Exception {
        String suffix = uniq();
        String email = "limite-" + suffix + "@mail.test";
        assertThat(register(email, suffix).getResponse().getStatus()).isEqualTo(201);
        assertThat(register(email, suffix).getResponse().getStatus()).isEqualTo(409); // ya registrado
        MvcResult blocked = register(email, suffix);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(blocked.getResponse().getHeader("Retry-After"))).isPositive();
        assertThat((String) JsonPath.read(blocked.getResponse().getContentAsString(), "$.code"))
                .isEqualTo("TOO_MANY_ATTEMPTS");
        // Otro email no se ve afectado.
        String other = uniq();
        assertThat(register("otro-" + other + "@mail.test", other).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void passwordRecoveryRequests_areLimitedPerEmail() throws Exception {
        String email = "recupera-" + uniq() + "@mail.test";
        for (int i = 0; i < 2; i++) {
            assertThat(status(HttpMethod.POST, "/api/auth/password/forgot", null, body("email", email)))
                    .isEqualTo(202);
        }
        MvcResult blocked = perform(HttpMethod.POST, "/api/auth/password/forgot", null, body("email", email));
        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        assertThat(blocked.getResponse().getHeader("Retry-After")).isNotNull();
        // La respuesta es la misma tanto si el email existe como si no (el límite no lo delata).
        assertThat((String) JsonPath.read(blocked.getResponse().getContentAsString(), "$.code"))
                .isEqualTo("TOO_MANY_ATTEMPTS");
    }
}
