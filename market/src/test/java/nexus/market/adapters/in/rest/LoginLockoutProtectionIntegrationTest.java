package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.UserId;
import nexus.market.domain.valueobjects.UserStatus;

/**
 * Controles para que un tercero no pueda bloquear cuentas ajenas por su cuenta: un solo origen queda limitado a 3
 * intentos por cuenta y ventana (429) y nunca bloquea la cuenta; hace falta fallar desde varios orígenes. Las respuestas
 * son genéricas y la auditoría (que aquí sale por el log) deja constancia sin datos personales.
 */
@ExtendWith(OutputCaptureExtension.class)
@ActiveProfiles("jpa")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nexusmarket_lockprotect;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.data.mongodb.uri=",
        "spring.data.mongodb.database=test_audit",
        "nexusmarket.login.max-per-email=100000",
        "nexusmarket.login.max-per-client=100000",
        "nexusmarket.login.max-per-client-and-email=3"
})
class LoginLockoutProtectionIntegrationTest extends RestTestSupport {

    private static final String ATTACKER = "198.51.100.7";
    private static final String ATTACKER_2 = "198.51.100.8";
    private static final String HOME = "203.0.113.50";

    private String emailOf(Account account) {
        return users.findById(UserId.of(account.userId())).orElseThrow().getEmail().getValue();
    }

    private MvcResult loginFrom(String origin, String email, String password) throws Exception {
        return performFrom(origin, HttpMethod.POST, "/api/auth/login", null,
                body("email", email, "password", password));
    }

    private UserStatus statusOf(Account account) {
        return users.findById(UserId.of(account.userId())).orElseThrow().getStatus();
    }

    private static long auditLines(CapturedOutput output, String operation, String actorId) {
        return output.getAll().lines()
                .filter(line -> line.contains("[AUDIT] " + operation) && line.contains("actor=" + actorId)).count();
    }

    @Test
    void aSingleOrigin_isThrottledAfterThreeAttempts_andNeverBlocksTheVictim() throws Exception {
        Account victim = registerBuyer();
        String email = emailOf(victim);

        for (int i = 0; i < 3; i++) {
            assertThat(loginFrom(ATTACKER, email, "mala-" + i).getResponse().getStatus()).isEqualTo(401);
        }
        // Desde ese origen ya no puede probar más (ni siquiera con la contraseña correcta)...
        for (int i = 0; i < 30; i++) {
            MvcResult throttled = loginFrom(ATTACKER, email, "mala-x" + i);
            assertThat(throttled.getResponse().getStatus()).isEqualTo(429);
            assertThat(throttled.getResponse().getHeader("Retry-After")).isNotNull();
        }
        // ...y la cuenta sigue activa: la víctima entra con normalidad desde su casa.
        assertThat(statusOf(victim)).isEqualTo(UserStatus.ACTIVE);
        assertThat(loginFrom(HOME, email, PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void failuresFromSeveralOrigins_blockTheAccount_withAGenericResponse_andAudit(CapturedOutput output)
            throws Exception {
        Account victim = registerBuyer();
        String email = emailOf(victim);
        String admin = staffToken(SystemRole.ADMIN);

        assertThat(loginFrom(ATTACKER, email, "mala-1").getResponse().getStatus()).isEqualTo(401);
        assertThat(loginFrom(ATTACKER, email, "mala-2").getResponse().getStatus()).isEqualTo(401);
        MvcResult blocking = loginFrom(ATTACKER_2, email, "mala-3");
        MvcResult unknownEmail = loginFrom(HOME, "nadie-" + uniq() + "@mail.test", "mala-3");

        assertThat(statusOf(victim)).isEqualTo(UserStatus.BLOCKED);
        // El intento que bloquea y un email inexistente responden exactamente igual.
        assertThat(blocking.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknownEmail.getResponse().getStatus()).isEqualTo(401);
        assertThat((String) JsonPath.read(blocking.getResponse().getContentAsString(), "$.code"))
                .isEqualTo(JsonPath.read(unknownEmail.getResponse().getContentAsString(), "$.code"));
        assertThat((String) JsonPath.read(blocking.getResponse().getContentAsString(), "$.message"))
                .isEqualTo(JsonPath.read(unknownEmail.getResponse().getContentAsString(), "$.message"));

        // Auditoría: 3 fallos y 1 bloqueo, con el id como actor.
        assertThat(auditLines(output, "LOGIN_FAILED", victim.userId())).isEqualTo(3);
        assertThat(auditLines(output, "ACCOUNT_LOCKED", victim.userId())).isEqualTo(1);

        // Sigue bloqueada hasta que un ADMIN la reactiva.
        assertThat(loginFrom(HOME, email, PASSWORD).getResponse().getStatus()).isEqualTo(403);
        send(HttpMethod.PATCH, "/api/users/" + victim.userId() + "/activate", admin, "{}", 200);
        assertThat(loginFrom(HOME, email, PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void unknownEmails_areNotAudited_andNeverRevealThemselves(CapturedOutput output) throws Exception {
        String unknown = "fantasma-" + uniq() + "@mail.test";
        MvcResult first = loginFrom(HOME, unknown, "mala");
        assertThat(first.getResponse().getStatus()).isEqualTo(401);
        assertThat((String) JsonPath.read(first.getResponse().getContentAsString(), "$.code"))
                .isEqualTo("INVALID_CREDENTIALS");
        assertThat(output.getAll()).doesNotContain("LOGIN_FAILED");
    }

    @Test
    void auditEntries_neverContainTheEmailOrThePassword(CapturedOutput output) throws Exception {
        Account victim = registerBuyer();
        String email = emailOf(victim);
        loginFrom(ATTACKER, email, "contraseña-secreta-123");

        assertThat(auditLines(output, "LOGIN_FAILED", victim.userId())).isEqualTo(1);
        String auditOnly = String.join("\n", output.getAll().lines().filter(l -> l.contains("[AUDIT]")).toList());
        assertThat(auditOnly).doesNotContain(email).doesNotContain("secreta");
    }
}
