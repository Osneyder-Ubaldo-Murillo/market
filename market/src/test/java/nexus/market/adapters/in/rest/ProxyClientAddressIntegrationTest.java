package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import nexus.market.domain.valueobjects.UserId;
import nexus.market.domain.valueobjects.UserStatus;

/**
 * Efecto real de la identificación del cliente sobre el bloqueo de cuentas y los límites de intentos. El proxy de
 * confianza es 10.0.0.1; cualquier otra conexión es un cliente directo.
 *
 * <p>Sin esta configuración, tras un proxy todos los fallos parecerían venir de un solo origen (el proxy) y la cuenta no
 * se bloquearía nunca; con ella, X-Forwarded-For solo cuenta si lo escribió el proxy.</p>
 */
@ActiveProfiles("jpa")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nexusmarket_proxy;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.data.mongodb.uri=",
        "spring.data.mongodb.database=test_audit",
        "nexusmarket.proxy.trusted-proxies=10.0.0.1",
        "nexusmarket.login.max-per-email=100000",
        "nexusmarket.login.max-per-client=100000",
        "nexusmarket.login.max-per-client-and-email=3"
})
class ProxyClientAddressIntegrationTest extends RestTestSupport {

    private static final String PROXY = "10.0.0.1";
    private static final String DIRECT_ATTACKER = "198.51.100.7";

    private MvcResult login(String remote, String forwardedFor, String email, String password) throws Exception {
        MockHttpServletRequestBuilder builder = post("/api/auth/login")
                .with(request -> {
                    request.setRemoteAddr(remote);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("email", email, "password", password));
        if (forwardedFor != null) {
            builder.header("X-Forwarded-For", forwardedFor);
        }
        return mockMvc.perform(builder).andReturn();
    }

    private String emailOf(Account account) {
        return users.findById(UserId.of(account.userId())).orElseThrow().getEmail().getValue();
    }

    private UserStatus statusOf(Account account) {
        return users.findById(UserId.of(account.userId())).orElseThrow().getStatus();
    }

    @Test
    void failuresFromDifferentRealClientsBehindTheTrustedProxy_blockTheAccount() throws Exception {
        Account victim = registerBuyer();
        String email = emailOf(victim);

        assertThat(login(PROXY, "203.0.113.10", email, "mala-1").getResponse().getStatus()).isEqualTo(401);
        assertThat(login(PROXY, "203.0.113.10", email, "mala-2").getResponse().getStatus()).isEqualTo(401);
        assertThat(login(PROXY, "203.0.113.11", email, "mala-3").getResponse().getStatus()).isEqualTo(401);

        assertThat(statusOf(victim)).isEqualTo(UserStatus.BLOCKED);
    }

    @Test
    void oneRealClientBehindTheProxy_isThrottled_andNeverBlocksTheAccount() throws Exception {
        Account victim = registerBuyer();
        String email = emailOf(victim);

        for (int i = 0; i < 3; i++) {
            assertThat(login(PROXY, "203.0.113.20", email, "mala-" + i).getResponse().getStatus()).isEqualTo(401);
        }
        // El mismo cliente real ya no puede seguir probando, aunque el proxy siga siendo la misma conexión TCP...
        assertThat(login(PROXY, "203.0.113.20", email, "mala-x").getResponse().getStatus()).isEqualTo(429);
        assertThat(statusOf(victim)).isEqualTo(UserStatus.ACTIVE);
        // ...y otro cliente real tras el mismo proxy no sufre su cupo: su primer intento se evalúa con normalidad.
        assertThat(login(PROXY, "203.0.113.21", email, PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void aSpoofedLeftmostEntry_doesNotCreateExtraOrigins() throws Exception {
        Account victim = registerBuyer();
        String email = emailOf(victim);

        // Un único cliente real (203.0.113.30) inventa una IP distinta a la izquierda en cada intento; el proxy añade la real.
        for (int i = 0; i < 3; i++) {
            assertThat(login(PROXY, "9.9.9." + i + ", 203.0.113.30", email, "mala-" + i).getResponse().getStatus())
                    .isEqualTo(401);
        }
        assertThat(login(PROXY, "9.9.9.9, 203.0.113.30", email, "mala-x").getResponse().getStatus()).isEqualTo(429);
        assertThat(statusOf(victim)).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void aDirectClient_cannotMultiplyItsAttemptsWithForwardedFor() throws Exception {
        Account victim = registerBuyer();
        String email = emailOf(victim);

        // Conexión directa (no es el proxy) que cambia X-Forwarded-For en cada intento para parecer varios orígenes.
        for (int i = 0; i < 3; i++) {
            assertThat(login(DIRECT_ATTACKER, "203.0.113." + (100 + i), email, "mala-" + i).getResponse().getStatus())
                    .isEqualTo(401);
        }
        MvcResult throttled = login(DIRECT_ATTACKER, "203.0.113.150", email, "mala-x");
        assertThat(throttled.getResponse().getStatus()).isEqualTo(429);
        assertThat(throttled.getResponse().getHeader("Retry-After")).isNotNull();
        // Y no fingió "varios orígenes": la cuenta sigue activa.
        assertThat(statusOf(victim)).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void aDirectClientImpersonatingTheProxy_isStillADirectClient() throws Exception {
        Account victim = registerBuyer();
        String email = emailOf(victim);

        // Escribir "10.0.0.1" en la cabecera no convierte la conexión en la del proxy.
        for (int i = 0; i < 3; i++) {
            assertThat(login(DIRECT_ATTACKER, "10.0.0.1, 203.0.113." + (200 + i), email, "mala-" + i).getResponse()
                    .getStatus()).isEqualTo(401);
        }
        assertThat(login(DIRECT_ATTACKER, "10.0.0.1, 203.0.113.250", email, "mala-x").getResponse().getStatus())
                .isEqualTo(429);
        assertThat(statusOf(victim)).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void responsesStayGeneric_whateverTheAddressSource() throws Exception {
        String unknown = "fantasma-" + uniq() + "@mail.test";
        MvcResult viaProxy = login(PROXY, "203.0.113.40", unknown, "mala");
        MvcResult direct = login(DIRECT_ATTACKER, null, unknown, "mala");
        assertThat(viaProxy.getResponse().getStatus()).isEqualTo(401);
        assertThat(direct.getResponse().getStatus()).isEqualTo(401);
        assertThat(viaProxy.getResponse().getContentAsString().replaceAll("\"timestamp\":\"[^\"]*\"", ""))
                .isEqualTo(direct.getResponse().getContentAsString().replaceAll("\"timestamp\":\"[^\"]*\"", ""));
    }
}
