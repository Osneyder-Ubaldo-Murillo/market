package nexus.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import nexus.market.domain.models.User;
import nexus.market.domain.ports.out.JwtServicePort;
import nexus.market.domain.ports.out.NotificationPort;
import nexus.market.domain.ports.out.UserRepositoryPort;
import nexus.market.domain.services.AuditService;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.UserId;

/**
 * La verificación del correo se exige por defecto. Se puede desactivar para una demostración
 * ({@code nexusmarket.auth.email-verification.required=false}); con el perfil {@code prod} eso exige además
 * {@code nexusmarket.auth.email-verification.skip-allowed=true}.
 */
class EmailVerificationSwitchTest {

    private final UserRepositoryPort users = mock(UserRepositoryPort.class);
    private final JwtServicePort jwt = mock(JwtServicePort.class);
    private final NotificationPort notifications = mock(NotificationPort.class);

    private static MockEnvironment env(boolean prod, String required, String skipAllowed) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(prod ? new String[] { "jpa", "prod" } : new String[] { "jpa" });
        if (required != null) {
            env.setProperty(EmailVerificationApplicationService.REQUIRED_PROPERTY, required);
        }
        if (skipAllowed != null) {
            env.setProperty(EmailVerificationApplicationService.SKIP_ALLOWED_PROPERTY, skipAllowed);
        }
        return env;
    }

    private EmailVerificationApplicationService service(MockEnvironment env) {
        return new EmailVerificationApplicationService(users, jwt, notifications, mock(AuditService.class), env);
    }

    @Test
    void byDefault_theCodeIsIssuedAndTheAccountIsNotMarkedVerified() {
        UserId id = UserId.generate();
        when(jwt.issueEmailVerificationToken(any(), any())).thenReturn("codigo");

        service(env(false, null, null)).sendVerificationCode(id, Email.of("a@mail.test"));

        verify(jwt).issueEmailVerificationToken(any(), any());
        verify(users, never()).save(any());
    }

    @Test
    void whenNotRequired_theAccountIsVerifiedAndNoCodeIsIssued() {
        UserId id = UserId.generate();
        User user = mock(User.class);
        when(users.findById(id)).thenReturn(Optional.of(user));

        service(env(false, "false", null)).sendVerificationCode(id, Email.of("a@mail.test"));

        verify(user).verifyEmail();
        verify(users).save(user);
        verify(jwt, never()).issueEmailVerificationToken(any(), any());
    }

    @Test
    void production_withoutExplicitAuthorization_refusesToSkipVerification() {
        for (String skip : new String[] { null, "false", "", "no" }) {
            assertThatThrownBy(() -> service(env(true, "false", skip)))
                    .as("skip-allowed: [%s]", skip)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(EmailVerificationApplicationService.SKIP_ALLOWED_PROPERTY);
        }
    }

    @Test
    void production_startsWhenVerificationIsRequired_orSkippingIsAuthorized() {
        assertThatCode(() -> service(env(true, null, null))).doesNotThrowAnyException();
        assertThatCode(() -> service(env(true, "true", null))).doesNotThrowAnyException();
        assertThatCode(() -> service(env(true, "false", "true"))).doesNotThrowAnyException();
    }

    @Test
    void anyValueOtherThanFalse_keepsVerificationRequired() {
        when(jwt.issueEmailVerificationToken(any(), any())).thenReturn("codigo");
        service(env(false, "no", null)).sendVerificationCode(UserId.generate(), Email.of("a@mail.test"));
        verify(jwt).issueEmailVerificationToken(any(), any());
    }
}
