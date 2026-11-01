package nexus.market.adapters.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

import nexus.market.adapters.out.notification.LoggingNotificationPort;
import nexus.market.domain.enums.NotificationChannel;

/**
 * Las notificaciones por log escriben los códigos de verificación y de recuperación en claro: con el perfil
 * {@code prod} quedan bloqueadas salvo autorización expresa ({@code nexusmarket.notifications.logging.allowed=true});
 * fuera de producción funcionan sin configuración.
 */
@ExtendWith(OutputCaptureExtension.class)
class NotificationProductionGuardTest {

    private static MockEnvironment env(boolean prod, String allowed) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(prod ? new String[] { "jpa", "prod" } : new String[] { "jpa" });
        if (allowed != null) {
            env.setProperty(LoggingNotificationPort.LOGGING_ALLOWED_PROPERTY, allowed);
        }
        return env;
    }

    @Test
    void production_withoutExplicitAuthorization_refusesToStart() {
        assertThatThrownBy(() -> new LoggingNotificationPort(env(true, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(LoggingNotificationPort.LOGGING_ALLOWED_PROPERTY)
                .hasMessageContaining("códigos");
    }

    @Test
    void production_withFalseEmptyOrOtherValue_refusesToStart() {
        for (String value : new String[] { "false", "FALSE", "no", "0", "", " ", "yes", "1" }) {
            assertThatThrownBy(() -> new LoggingNotificationPort(env(true, value)))
                    .as("valor: [%s]", value)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void production_withExplicitAuthorization_starts_andWarnsThatCodesGoToTheLog(CapturedOutput output) {
        LoggingNotificationPort port = new LoggingNotificationPort(env(true, "true"));
        assertThat(output.getAll()).contains("Notificaciones por LOG habilitadas en producción")
                .contains(LoggingNotificationPort.LOGGING_ALLOWED_PROPERTY);
        assertThatCode(() -> port.send(NotificationChannel.EMAIL, "ana@example.com", "Código", "123456"))
                .doesNotThrowAnyException();
    }

    @Test
    void outsideProduction_startsWithoutConfiguration_andWithoutTheWarning(CapturedOutput output) {
        assertThatCode(() -> new LoggingNotificationPort(env(false, null))).doesNotThrowAnyException();
        assertThatCode(() -> new LoggingNotificationPort()).doesNotThrowAnyException();
        assertThat(output.getAll()).doesNotContain("habilitadas en producción");
    }

    @Test
    void errorMessage_doesNotLeakConfiguredValues() {
        MockEnvironment env = env(true, "valor-que-no-debe-aparecer");
        assertThatThrownBy(() -> new LoggingNotificationPort(env))
                .hasMessageNotContaining("valor-que-no-debe-aparecer");
    }
}
