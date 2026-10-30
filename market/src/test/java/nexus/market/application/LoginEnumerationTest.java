package nexus.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.models.User;
import nexus.market.domain.ports.out.JwtServicePort;
import nexus.market.domain.ports.out.PasswordServicePort;
import nexus.market.domain.ports.out.UserRepositoryPort;
import nexus.market.domain.services.UserManagementService;
import nexus.market.domain.valueobjects.DocumentId;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.FullName;
import nexus.market.domain.valueobjects.SystemRole;

/**
 * Un email inexistente (o un usuario sin credenciales) cuesta lo mismo que una contraseña incorrecta: siempre se
 * ejecuta una verificación BCrypt, para que el tiempo de respuesta no revele qué cuentas existen. El mensaje y el código
 * son idénticos en los tres casos.
 */
class LoginEnumerationTest {

    private final UserRepositoryPort users = mock(UserRepositoryPort.class);
    private final CredentialStorePort credentials = mock(CredentialStorePort.class);
    private final PasswordServicePort passwords = mock(PasswordServicePort.class);
    private final JwtServicePort jwt = mock(JwtServicePort.class);
    private AuthApplicationService service;

    @BeforeEach
    void setUp() {
        service = new AuthApplicationService(mock(UserManagementService.class), users, credentials, passwords, jwt,
                mock(nexus.market.domain.ports.in.EmailVerificationUseCases.class), new LoginLockout(3));
        when(passwords.hash(anyString())).thenReturn("$2a$10$hashficticiohashficticiohashficticiohashficticiohashfict");
    }

    private static User activeBuyer(String email) {
        return User.create(FullName.of("Ana Pérez"), Email.of(email), DocumentId.of("CC-1"), SystemRole.BUYER);
    }

    @Test
    void unknownEmail_stillRunsAPasswordVerification_andFailsWithTheSameError() {
        when(users.findByEmail(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(Email.of("nadie@example.com"), "cualquiera123"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_CREDENTIALS");
        verify(passwords, times(1)).verify(eq("cualquiera123"), anyString());
        verify(credentials, never()).findByUserId(any());
    }

    @Test
    void userWithoutCredentials_behavesLikeUnknownEmail() {
        User user = activeBuyer("sin-credenciales@example.com");
        when(users.findByEmail(any())).thenReturn(Optional.of(user));
        when(credentials.findByUserId(user.getUserId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(user.getEmail(), "cualquiera123"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_CREDENTIALS");
        verify(passwords, times(1)).verify(eq("cualquiera123"), anyString());
    }

    @Test
    void dummyHash_isComputedOnce_andNeverEqualsARealStoredHash() {
        when(users.findByEmail(any())).thenReturn(Optional.empty());

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> service.login(Email.of("nadie@example.com"), "x1234567"))
                    .isInstanceOf(BusinessException.class);
        }
        verify(passwords, times(1)).hash(anyString());
        ArgumentCaptor<String> used = ArgumentCaptor.forClass(String.class);
        verify(passwords, times(3)).verify(anyString(), used.capture());
        assertThat(used.getAllValues()).containsOnly("$2a$10$hashficticiohashficticiohashficticiohashficticiohashfict");
    }

    @Test
    void wrongPassword_andUnknownEmail_produceTheSameErrorMessage() {
        User user = activeBuyer("ana@example.com");
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(users.findByEmail(Email.of("nadie@example.com"))).thenReturn(Optional.empty());
        when(credentials.findByUserId(user.getUserId())).thenReturn(Optional.of("hash-real"));
        when(passwords.verify(anyString(), eq("hash-real"))).thenReturn(false);

        String wrong = messageOf(() -> service.login(user.getEmail(), "incorrecta1"));
        String unknown = messageOf(() -> service.login(Email.of("nadie@example.com"), "incorrecta1"));
        assertThat(wrong).isEqualTo(unknown);
    }

    private static String messageOf(Runnable action) {
        try {
            action.run();
        } catch (BusinessException e) {
            return e.getCode() + ":" + e.getMessage();
        }
        throw new AssertionError("Se esperaba BusinessException");
    }
}
