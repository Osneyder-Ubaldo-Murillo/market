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
 * Límite de intentos de login a través de la API real (H2 + perfil {@code jpa}): al agotar el cupo por email se
 * responde 429 con {@code Retry-After} y formato {@code ErrorResponse}, aunque la contraseña sea correcta; otros
 * emails no se ven afectados; credenciales inexistentes e incorrectas son indistinguibles (401).
 */
@ActiveProfiles("jpa")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nexusmarket_loginlimit;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.data.mongodb.uri=",
        "spring.data.mongodb.database=test_audit",
        "nexusmarket.login.max-per-email=3",
        "nexusmarket.login.max-per-client=1000"
})
class LoginRateLimitIntegrationTest extends RestTestSupport {

    private MvcResult login(String email, String password) throws Exception {
        return perform(HttpMethod.POST, "/api/auth/login", null, body("email", email, "password", password));
    }

    @Test
    void afterTheQuota_loginIsRejectedWith429_evenWithTheCorrectPassword() throws Exception {
        Account buyer = registerBuyer(); // el registro no consume el cupo de login
        String email = emailOf(buyer);

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(login(email, "incorrecta-" + attempt).getResponse().getStatus()).as("intento %d", attempt)
                    .isEqualTo(401);
        }
        MvcResult blocked = login(email, PASSWORD);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(blocked.getResponse().getHeader("Retry-After"))).isBetween(1L, 901L);
        String json = blocked.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(json, "$.code")).isEqualTo("TOO_MANY_ATTEMPTS");
        assertThat(json).doesNotContain(email);
    }

    @Test
    void anotherEmail_isNotAffectedByTheBlockedOne() throws Exception {
        Account blockedBuyer = registerBuyer();
        Account otherBuyer = registerBuyer();
        for (int attempt = 0; attempt < 3; attempt++) {
            login(emailOf(blockedBuyer), "incorrecta");
        }
        assertThat(login(emailOf(blockedBuyer), PASSWORD).getResponse().getStatus()).isEqualTo(429);
        assertThat(login(emailOf(otherBuyer), PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void successfulLogin_resetsTheCounter() throws Exception {
        Account buyer = registerBuyer();
        String email = emailOf(buyer);
        for (int round = 0; round < 3; round++) {
            assertThat(login(email, "incorrecta").getResponse().getStatus()).isEqualTo(401);
            assertThat(login(email, "incorrecta").getResponse().getStatus()).isEqualTo(401);
            assertThat(login(email, PASSWORD).getResponse().getStatus()).as("ronda %d", round).isEqualTo(200);
        }
    }

    @Test
    void unknownEmailAndWrongPassword_areIndistinguishable() throws Exception {
        Account buyer = registerBuyer();
        MvcResult unknown = login("no-existe-" + uniq() + "@example.com", PASSWORD);
        MvcResult wrong = login(emailOf(buyer), "incorrecta");
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401).isEqualTo(wrong.getResponse().getStatus());
        assertThat((String) JsonPath.read(unknown.getResponse().getContentAsString(), "$.message"))
                .isEqualTo(JsonPath.read(wrong.getResponse().getContentAsString(), "$.message"));
        assertThat((String) JsonPath.read(unknown.getResponse().getContentAsString(), "$.code"))
                .isEqualTo("INVALID_CREDENTIALS");
    }

    /** Email del comprador registrado (el soporte lo genera con el id de usuario). */
    private String emailOf(Account account) {
        return users.findById(nexus.market.domain.valueobjects.UserId.of(account.userId())).orElseThrow()
                .getEmail().getValue();
    }
}
