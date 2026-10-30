package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

import nexus.market.domain.enums.NotificationChannel;
import nexus.market.domain.models.User;
import nexus.market.domain.ports.out.NotificationPort;
import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.UserId;

/**
 * Recuperación de contraseña: la solicitud no revela si el email existe, el código sirve una sola vez, vence, invalida los
 * tokens de acceso anteriores, no es utilizable como token de acceso y no existe para el ADMIN ni para cuentas inactivas.
 */
class PasswordRecoveryIntegrationTest extends RestTestSupport {

    private static final String NEW_PASSWORD = "NuevaClave456!";

    @MockitoSpyBean
    private NotificationPort notifications;

    @Autowired
    private nexus.market.domain.ports.out.JwtServicePort jwtService;

    private String emailOf(Account account) {
        return users.findById(UserId.of(account.userId())).orElseThrow().getEmail().getValue();
    }

    /** Solicita el código y lo extrae del aviso (el adaptador de log lo recibiría igual). */
    private String requestToken(String email) throws Exception {
        clearInvocations(notifications);
        send(HttpMethod.POST, "/api/auth/password/forgot", null, body("email", email), 202);
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(notifications).send(eq(NotificationChannel.EMAIL), eq(email), eq("Recuperación de contraseña"),
                content.capture());
        String text = content.getValue();
        return text.substring(text.lastIndexOf(": ") + 2);
    }

    private int login(String email, String password) throws Exception {
        return status(HttpMethod.POST, "/api/auth/login", null, body("email", email, "password", password));
    }

    @Test
    void forgot_answersTheSameForKnownAndUnknownEmails_andSendsOnlyForTheKnownOne() throws Exception {
        Account buyer = registerBuyer();
        clearInvocations(notifications);

        MvcResult known = perform(HttpMethod.POST, "/api/auth/password/forgot", null, body("email", emailOf(buyer)));
        MvcResult unknown = perform(HttpMethod.POST, "/api/auth/password/forgot", null,
                body("email", "no-existe-" + uniq() + "@mail.test"));

        assertThat(known.getResponse().getStatus()).isEqualTo(202).isEqualTo(unknown.getResponse().getStatus());
        assertThat(known.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());
        verify(notifications).send(any(), eq(emailOf(buyer)), anyString(), anyString());
        verify(notifications, org.mockito.Mockito.times(1)).send(any(), anyString(), anyString(), anyString());
    }

    @Test
    void fullFlow_resetsThePassword_invalidatesOldTokens_andTheCodeWorksOnlyOnce() throws Exception {
        Account buyer = registerBuyer();
        String email = emailOf(buyer);
        String token = requestToken(email);

        send(HttpMethod.POST, "/api/auth/password/reset", null, body("token", token, "newPassword", NEW_PASSWORD), 200);

        assertThat(login(email, PASSWORD)).isEqualTo(401);
        assertThat(login(email, NEW_PASSWORD)).isEqualTo(200);
        // El token de acceso anterior al cambio ya no vale.
        assertThat(status(HttpMethod.GET, "/api/users/" + buyer.userId(), buyer.token(), null)).isEqualTo(401);
        // El mismo código no se puede usar de nuevo.
        String reused = send(HttpMethod.POST, "/api/auth/password/reset", null,
                body("token", token, "newPassword", "OtraClave789!"), 400);
        assertThat((String) JsonPath.read(reused, "$.code")).isEqualTo("INVALID_RESET_TOKEN");
        assertThat(login(email, NEW_PASSWORD)).isEqualTo(200);
    }

    @Test
    void invalidTokens_allFailWithTheSameError() throws Exception {
        Account buyer = registerBuyer();
        String valid = requestToken(emailOf(buyer));
        String tampered = valid.substring(0, valid.length() - 3) + (valid.endsWith("AAA") ? "BBB" : "AAA");
        String[] bad = { "no-es-un-token", tampered, valid.substring(0, valid.indexOf('.')), "a.b.c",
                // un token de ACCESO (firmado y vigente) no es un código de recuperación
                buyer.token() };
        for (String token : bad) {
            String body = send(HttpMethod.POST, "/api/auth/password/reset", null,
                    body("token", token, "newPassword", NEW_PASSWORD), 400);
            assertThat((String) JsonPath.read(body, "$.code")).as(token).isEqualTo("INVALID_RESET_TOKEN");
        }
        assertThat(login(emailOf(buyer), PASSWORD)).isEqualTo(200); // nada cambió
    }

    @Test
    void expiredCode_isRejected() throws Exception {
        Account buyer = registerBuyer();
        User user = users.findById(UserId.of(buyer.userId())).orElseThrow();
        String expired = jwtService.issuePasswordResetToken(user.getUserId(),
                jwtService.credentialVersion("hash-cualquiera"), java.time.Duration.ofSeconds(-60));
        String body = send(HttpMethod.POST, "/api/auth/password/reset", null,
                body("token", expired, "newPassword", NEW_PASSWORD), 400);
        assertThat((String) JsonPath.read(body, "$.code")).isEqualTo("INVALID_RESET_TOKEN");
    }

    @Test
    void resetCode_isNotUsableAsAnAccessToken() throws Exception {
        Account buyer = registerBuyer();
        String token = requestToken(emailOf(buyer));
        assertThat(status(HttpMethod.GET, "/api/users/" + buyer.userId(), token, null)).isEqualTo(401);
        assertThat(jwtService.isValid(token)).isFalse();
        assertThat(jwtService.getUserId(token)).isEmpty();
    }

    @Test
    void weakOrOversizedNewPassword_is400_andKeepsTheCodeUsable() throws Exception {
        Account buyer = registerBuyer();
        String token = requestToken(emailOf(buyer));
        send(HttpMethod.POST, "/api/auth/password/reset", null, body("token", token, "newPassword", "corta"), 400);
        send(HttpMethod.POST, "/api/auth/password/reset", null,
                body("token", token, "newPassword", "x".repeat(73)), 400);
        send(HttpMethod.POST, "/api/auth/password/reset", null, body("token", token, "newPassword", NEW_PASSWORD), 200);
    }

    @Test
    void admin_andInactiveAccounts_cannotRecoverThisWay() throws Exception {
        String adminEmail = "admin-" + uniq() + "@mail.test";
        users.save(User.create(nexus.market.domain.valueobjects.FullName.of("Admin " + uniq()),
                nexus.market.domain.valueobjects.Email.of(adminEmail),
                nexus.market.domain.valueobjects.DocumentId.of("AD-" + uniq()), SystemRole.ADMIN));
        clearInvocations(notifications);
        send(HttpMethod.POST, "/api/auth/password/forgot", null, body("email", adminEmail), 202);
        verify(notifications, never()).send(any(), anyString(), anyString(), anyString());

        Account buyer = registerBuyer();
        String token = requestToken(emailOf(buyer));
        String admin = staffToken(SystemRole.ADMIN);
        send(HttpMethod.PATCH, "/api/users/" + buyer.userId() + "/block", admin, "{}", 200);
        String body = send(HttpMethod.POST, "/api/auth/password/reset", null,
                body("token", token, "newPassword", NEW_PASSWORD), 400);
        assertThat((String) JsonPath.read(body, "$.code")).isEqualTo("INVALID_RESET_TOKEN");
        clearInvocations(notifications);
        send(HttpMethod.POST, "/api/auth/password/forgot", null, body("email", emailOf(buyer)), 202);
        verify(notifications, never()).send(any(), anyString(), anyString(), anyString());
    }

    @Test
    void validation_malformedRequestsAre400() throws Exception {
        send(HttpMethod.POST, "/api/auth/password/forgot", null, "{}", 400);
        send(HttpMethod.POST, "/api/auth/password/forgot", null, body("email", "no-es-email"), 400);
        send(HttpMethod.POST, "/api/auth/password/reset", null, body("token", "x"), 400);
    }
}
