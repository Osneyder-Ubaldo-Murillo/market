package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import nexus.market.domain.enums.NotificationChannel;
import nexus.market.domain.models.User;
import nexus.market.domain.ports.out.JwtServicePort;
import nexus.market.domain.ports.out.NotificationPort;
import nexus.market.domain.valueobjects.DocumentId;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.FullName;
import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.UserId;
import nexus.market.domain.valueobjects.UserStatus;

/**
 * Tras 3 inicios de sesión fallidos la cuenta queda BLOCKED y solo un ADMIN puede reactivarla; el ADMIN nunca se bloquea
 * por este camino. Una cuenta de registro público no puede iniciar sesión hasta verificar el correo con el código
 * recibido.
 */
class LoginLockoutAndEmailVerificationIntegrationTest extends RestTestSupport {

    @MockitoSpyBean
    private NotificationPort notifications;

    @Autowired
    private JwtServicePort jwtService;

    private String emailOf(Account account) {
        return users.findById(UserId.of(account.userId())).orElseThrow().getEmail().getValue();
    }

    private MvcResultHolder login(String email, String password) throws Exception {
        var result = perform(HttpMethod.POST, "/api/auth/login", null, body("email", email, "password", password));
        return new MvcResultHolder(result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    private record MvcResultHolder(int status, String body) {
        String code() {
            return JsonPath.read(body, "$.code");
        }
    }

    // ------------------------------------------------------------------
    // Bloqueo tras 3 intentos
    // ------------------------------------------------------------------

    @Test
    void threeWrongPasswords_blockTheAccount_andOnlyAnAdminReactivatesIt() throws Exception {
        Account buyer = registerBuyer();
        String email = emailOf(buyer);
        String admin = staffToken(SystemRole.ADMIN);

        assertThat(login(email, "mala-1").status()).isEqualTo(401);
        assertThat(login(email, "mala-2").status()).isEqualTo(401);
        MvcResultHolder third = login(email, "mala-3");
        // La respuesta que bloquea es idéntica a cualquier credencial incorrecta (no delata el bloqueo).
        assertThat(third.status()).isEqualTo(401);
        assertThat(third.code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(users.findById(UserId.of(buyer.userId())).orElseThrow().getStatus()).isEqualTo(UserStatus.BLOCKED);

        // Con la contraseña correcta tampoco entra: cuenta bloqueada, hay que contactar al administrador.
        MvcResultHolder blocked = login(email, PASSWORD);
        assertThat(blocked.status()).isEqualTo(403);
        assertThat(blocked.code()).isEqualTo("USER_NOT_ACTIVE");
        // El token anterior al bloqueo tampoco vale.
        assertThat(status(HttpMethod.GET, "/api/users/" + buyer.userId(), buyer.token(), null)).isEqualTo(401);
        // El usuario no puede desbloquearse solo: recuperar contraseña no aplica a cuentas bloqueadas.
        clearInvocations(notifications);
        send(HttpMethod.POST, "/api/auth/password/forgot", null, body("email", email), 202);
        verify(notifications, never()).send(any(), eq(email), eq("Recuperación de contraseña"), anyString());
        // Ni un comprador ni un vendedor pueden reactivarla.
        Account other = registerBuyer();
        assertThat(status(HttpMethod.PATCH, "/api/users/" + buyer.userId() + "/activate", other.token(), "{}"))
                .isEqualTo(403);

        // El ADMIN la reactiva y entonces entra con su contraseña.
        send(HttpMethod.PATCH, "/api/users/" + buyer.userId() + "/activate", admin, "{}", 200);
        assertThat(login(email, PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void failuresAreConsecutive_aSuccessfulLoginResetsTheCount() throws Exception {
        Account buyer = registerBuyer();
        String email = emailOf(buyer);
        for (int round = 0; round < 3; round++) {
            assertThat(login(email, "mala-a").status()).isEqualTo(401);
            assertThat(login(email, "mala-b").status()).isEqualTo(401);
            assertThat(login(email, PASSWORD).status()).as("ronda %d", round).isEqualTo(200);
        }
        assertThat(users.findById(UserId.of(buyer.userId())).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void unknownEmails_neverBlockAnything_andDoNotRevealIt() throws Exception {
        String unknown = "fantasma-" + uniq() + "@mail.test";
        for (int i = 0; i < 5; i++) {
            MvcResultHolder result = login(unknown, "mala");
            assertThat(result.status()).isEqualTo(401);
            assertThat(result.code()).isEqualTo("INVALID_CREDENTIALS");
        }
    }

    @Test
    void admin_isNeverBlockedByFailedLogins() throws Exception {
        String adminEmail = "admin-" + uniq() + "@mail.test";
        User admin = users.save(User.create(FullName.of("Admin " + uniq()), Email.of(adminEmail),
                DocumentId.of("AD-" + uniq()), SystemRole.ADMIN));
        credentialStore.save(admin.getUserId(), passwordService.hash(PASSWORD));

        for (int i = 0; i < 6; i++) {
            assertThat(login(adminEmail, "mala-" + i).status()).isEqualTo(401);
        }
        assertThat(users.findById(admin.getUserId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(login(adminEmail, PASSWORD).status()).isEqualTo(200);
    }

    @Autowired
    private nexus.market.application.CredentialStorePort credentialStore;

    @Autowired
    private nexus.market.domain.ports.out.PasswordServicePort passwordService;

    // ------------------------------------------------------------------
    // Verificación del correo
    // ------------------------------------------------------------------

    private String registerPending(String email) throws Exception {
        String suffix = uniq();
        clearInvocations(notifications);
        return send(HttpMethod.POST, "/api/auth/register/buyer", null,
                body("fullName", "Persona " + suffix, "email", email, "documentId", "CC-" + suffix,
                        "password", PASSWORD, "address", addressJson()), 201);
    }

    private String codeSentTo(String email) {
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(notifications, org.mockito.Mockito.atLeastOnce()).send(eq(NotificationChannel.EMAIL), eq(email),
                eq("Verifique su correo"), content.capture());
        String text = content.getValue();
        return text.substring(text.lastIndexOf(": ") + 2);
    }

    @Test
    void registration_sendsACode_andLoginIsRefusedUntilTheEmailIsVerified() throws Exception {
        String email = "pendiente-" + uniq() + "@mail.test";
        String registered = registerPending(email);
        String userId = JsonPath.read(registered, "$.userId");
        String code = codeSentTo(email);

        MvcResultHolder refused = login(email, PASSWORD);
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.code()).isEqualTo("EMAIL_NOT_VERIFIED");
        // Una contraseña incorrecta no revela que la cuenta está pendiente de verificar.
        assertThat(login(email, "mala").code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(users.findById(UserId.of(userId)).orElseThrow().isEmailVerified()).isFalse();

        send(HttpMethod.POST, "/api/auth/email/verify", null, body("token", code), 200);

        assertThat(users.findById(UserId.of(userId)).orElseThrow().isEmailVerified()).isTrue();
        assertThat(login(email, PASSWORD).status()).isEqualTo(200);
        // Verificar de nuevo es inofensivo.
        send(HttpMethod.POST, "/api/auth/email/verify", null, body("token", code), 200);
    }

    @Test
    void invalidCodes_allFailTheSameWay_andTheyAreNotAccessTokens() throws Exception {
        String email = "codigos-" + uniq() + "@mail.test";
        String userId = JsonPath.read(registerPending(email), "$.userId");
        String valid = codeSentTo(email);
        String expired = jwtService.issueEmailVerificationToken(UserId.of(userId), Duration.ofSeconds(-60));
        String resetCode = jwtService.issuePasswordResetToken(UserId.of(userId), "x", Duration.ofMinutes(5));
        String[] bad = { "basura", expired, resetCode, valid.substring(0, valid.length() - 3) + "AAA" };
        for (String token : bad) {
            String body = send(HttpMethod.POST, "/api/auth/email/verify", null, body("token", token), 400);
            assertThat((String) JsonPath.read(body, "$.code")).as(token).isEqualTo("INVALID_VERIFICATION_TOKEN");
        }
        // El código de verificación no sirve como token de acceso ni como código de recuperación.
        assertThat(status(HttpMethod.GET, "/api/users/" + userId, valid, null)).isEqualTo(401);
        assertThat(jwtService.parsePasswordResetToken(valid)).isEmpty();
        assertThat(jwtService.parseEmailVerificationToken(resetCode)).isEmpty();
        assertThat(users.findById(UserId.of(userId)).orElseThrow().isEmailVerified()).isFalse();
    }

    @Test
    void resend_isGeneric_andOnlyAnswersForPendingAccounts() throws Exception {
        String pending = "reenvio-" + uniq() + "@mail.test";
        registerPending(pending);
        Account verified = registerBuyer();
        clearInvocations(notifications);

        String a = send(HttpMethod.POST, "/api/auth/email/resend", null, body("email", pending), 202);
        String b = send(HttpMethod.POST, "/api/auth/email/resend", null, body("email", emailOf(verified)), 202);
        String c = send(HttpMethod.POST, "/api/auth/email/resend", null,
                body("email", "no-existe-" + uniq() + "@mail.test"), 202);

        assertThat(a).isEqualTo(b).isEqualTo(c);
        verify(notifications).send(eq(NotificationChannel.EMAIL), eq(pending), eq("Verifique su correo"), anyString());
        verify(notifications, org.mockito.Mockito.times(1)).send(any(), anyString(), anyString(), anyString());
    }

    @Test
    void sellersRegisteredByAdmin_alsoNeedToVerify() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        String suffix = uniq();
        String email = "vendedor-" + suffix + "@mail.test";
        String registered = send(HttpMethod.POST, "/api/auth/register/seller", admin,
                body("fullName", "Vendedor " + suffix, "email", email, "documentId", "NIT-" + suffix,
                        "password", PASSWORD, "businessName", "Comercial " + suffix,
                        "taxId", String.valueOf(java.util.concurrent.ThreadLocalRandom.current()
                                .nextInt(10_000_000, 99_999_999))), 201);
        send(HttpMethod.POST, "/api/sellers/" + JsonPath.read(registered, "$.sellerId") + "/approve", admin, "{}", 200);
        assertThat(login(email, PASSWORD).code()).isEqualTo("EMAIL_NOT_VERIFIED");
        verifyEmail(JsonPath.read(registered, "$.userId"));
        assertThat(login(email, PASSWORD).status()).isEqualTo(200);
    }
}
