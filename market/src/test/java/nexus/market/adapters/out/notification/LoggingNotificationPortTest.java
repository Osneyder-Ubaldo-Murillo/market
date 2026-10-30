package nexus.market.adapters.out.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

import nexus.market.domain.enums.NotificationChannel;

/** Las notificaciones se simulan en el log sin guardar direcciones completas. */
class LoggingNotificationPortTest {

    @Test
    void recipient_isMasked() {
        assertThat(LoggingNotificationPort.mask("ana.perez@example.com")).isEqualTo("a***@example.com");
        assertThat(LoggingNotificationPort.mask("3001234567")).isEqualTo("30***");
        assertThat(LoggingNotificationPort.mask("abc")).isEqualTo("***");
        assertThat(LoggingNotificationPort.mask(null)).isEqualTo("(sin destinatario)");
        assertThat(LoggingNotificationPort.mask(" ")).isEqualTo("(sin destinatario)");
        assertThat(LoggingNotificationPort.mask("ana.perez@example.com")).doesNotContain("ana.perez");
    }

    @Test
    void send_neverFails() {
        assertThatCode(() -> new LoggingNotificationPort().send(NotificationChannel.EMAIL, "ana@example.com",
                "Asunto", "Contenido")).doesNotThrowAnyException();
        assertThatCode(() -> new LoggingNotificationPort().send(NotificationChannel.SMS, null, "Asunto", "Contenido"))
                .doesNotThrowAnyException();
    }
}
