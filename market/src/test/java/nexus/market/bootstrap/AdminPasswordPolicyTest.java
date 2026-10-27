package nexus.market.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import nexus.market.application.AdminPasswordPolicy;

/** Política de la contraseña del administrador inicial: 12 caracteres mínimo, 72 bytes UTF-8 máximo. */
class AdminPasswordPolicyTest {

    private static final String EMAIL = "primer.admin@tienda.example";
    // 10 "ñ" (20 bytes) + 52 caracteres ASCII variados = exactamente 72 bytes y sin fragmentos débiles.
    private static final String SEVENTY_TWO_BYTES = "ñ".repeat(10) + "BcDeFgHiJkLmNoPqRsTuVwXyZ7391846205bcdfghjklmnpqrstv";

    @Test
    void acceptsAStrongPasswordOfExactlyTwelveCharacters() {
        assertThatCode(() -> AdminPasswordPolicy.validate("Tq9#vLx2!mWz", EMAIL)).doesNotThrowAnyException();
    }

    @Test
    void rejectsLessThanTwelveCharacters_buttheRegistrationMinimumOfEightDoesNotApplyHere() {
        assertThatThrownBy(() -> AdminPasswordPolicy.validate("Tq9#vLx2!mW", EMAIL))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("12");
    }

    @Test
    void measuresTheMaximumInUtf8BytesAndNeverTruncates() {
        assertThat(SEVENTY_TWO_BYTES.getBytes(StandardCharsets.UTF_8)).hasSize(72);
        assertThatCode(() -> AdminPasswordPolicy.validate(SEVENTY_TWO_BYTES, EMAIL)).doesNotThrowAnyException();
        String seventyFour = "ñ" + SEVENTY_TWO_BYTES.substring(0, SEVENTY_TWO_BYTES.length() - 1) + "xy";
        assertThat(seventyFour.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(72);
        assertThat(seventyFour.length()).isLessThan(72); // corto en caracteres, largo en bytes
        assertThatThrownBy(() -> AdminPasswordPolicy.validate(seventyFour, EMAIL))
                .hasMessageContaining("72 bytes");
    }

    @Test
    void rejectsExampleAndPredictablePasswords() {
        for (String weak : new String[] { "Password123456!", "NexusMarket#2026x", "ChangeMe-Now-12345",
                "qwertyuiop1234", "Administrador#9999", "letmein-letmein" }) {
            assertThatThrownBy(() -> AdminPasswordPolicy.validate(weak, EMAIL))
                    .as(weak.length() + " caracteres")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(weak);
        }
    }

    @Test
    void rejectsLowVarietyAndTheAdministratorsOwnEmail() {
        assertThatThrownBy(() -> AdminPasswordPolicy.validate("aaaaaaaaaaaaaaaa", EMAIL))
                .hasMessageContaining("variedad");
        assertThatThrownBy(() -> AdminPasswordPolicy.validate("Tq9#carla.gomez-7Xv", "carla.gomez@tienda.example"))
                .hasMessageContaining("correo");
        assertThatThrownBy(() -> AdminPasswordPolicy.validate(null, EMAIL)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdminPasswordPolicy.validate("            ", EMAIL))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
