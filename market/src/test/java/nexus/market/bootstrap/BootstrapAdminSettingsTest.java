package nexus.market.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import nexus.market.infrastructure.bootstrap.BootstrapAdminConfigurationException;
import nexus.market.infrastructure.bootstrap.BootstrapAdminSettings;

/**
 * Lectura y validación de las variables del administrador inicial: todo obligatorio, la contraseña desde un
 * archivo (preferido) o una variable, y mensajes que nombran variables pero nunca valores.
 */
class BootstrapAdminSettingsTest {

    private static final String PASSWORD = "Rk7#pQzLm2!vXw9T"; // valor distintivo para buscarlo en los mensajes

    @TempDir
    Path tmp;

    private static MockEnvironment complete() {
        return new MockEnvironment()
                .withProperty(BootstrapAdminSettings.ENABLED, "true")
                .withProperty(BootstrapAdminSettings.EMAIL, "primer.admin@tienda.example")
                .withProperty(BootstrapAdminSettings.FULL_NAME, "Administradora Inicial")
                .withProperty(BootstrapAdminSettings.DOCUMENT_ID, "ADM-2026-01");
    }

    private Path secretFile(String content) throws IOException {
        Path file = tmp.resolve("admin-password");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void isDisabledByDefault_andOnlyTheExactValueTrueEnablesIt() {
        assertThat(BootstrapAdminSettings.isEnabled(new MockEnvironment())).isFalse();
        for (String other : new String[] { "", "false", "yes", "1", "ture", "on" }) {
            assertThat(BootstrapAdminSettings.isEnabled(
                    new MockEnvironment().withProperty(BootstrapAdminSettings.ENABLED, other))).as(other).isFalse();
        }
        assertThat(BootstrapAdminSettings.isEnabled(
                new MockEnvironment().withProperty(BootstrapAdminSettings.ENABLED, "TRUE"))).isTrue();
    }

    @Test
    void missingEverything_reportsEveryVariableByName() {
        MockEnvironment env = new MockEnvironment().withProperty(BootstrapAdminSettings.ENABLED, "true");
        assertThatThrownBy(() -> BootstrapAdminSettings.validateAll(env))
                .isInstanceOf(BootstrapAdminConfigurationException.class)
                .hasMessageContaining(BootstrapAdminSettings.EMAIL)
                .hasMessageContaining(BootstrapAdminSettings.FULL_NAME)
                .hasMessageContaining(BootstrapAdminSettings.DOCUMENT_ID)
                .hasMessageContaining(BootstrapAdminSettings.PASSWORD_FILE)
                .hasMessageContaining(BootstrapAdminSettings.PASSWORD);
    }

    @Test
    void invalidValues_areRejected_withoutEchoingThem() {
        MockEnvironment env = complete()
                .withProperty(BootstrapAdminSettings.EMAIL, "esto-no-es-un-correo")
                .withProperty(BootstrapAdminSettings.DOCUMENT_ID, "x")
                .withProperty(BootstrapAdminSettings.FULL_NAME, "a")
                .withProperty(BootstrapAdminSettings.PASSWORD, "corta");
        assertThatThrownBy(() -> BootstrapAdminSettings.validateAll(env))
                .isInstanceOf(BootstrapAdminConfigurationException.class)
                .hasMessageContaining(BootstrapAdminSettings.EMAIL)
                .hasMessageContaining(BootstrapAdminSettings.DOCUMENT_ID)
                .hasMessageContaining(BootstrapAdminSettings.FULL_NAME)
                .hasMessageContaining("12 caracteres")
                .hasMessageNotContaining("esto-no-es-un-correo")
                .hasMessageNotContaining("corta");
    }

    @Test
    void passwordPolicyViolations_failStartup_withoutRevealingThePassword() {
        for (String bad : new String[] { "Password-12345-x", "ñ".repeat(40) + "abc", "a".repeat(20) }) {
            MockEnvironment env = complete().withProperty(BootstrapAdminSettings.PASSWORD, bad);
            assertThatThrownBy(() -> BootstrapAdminSettings.validateAll(env))
                    .isInstanceOf(BootstrapAdminConfigurationException.class)
                    .hasMessageContaining(BootstrapAdminSettings.PASSWORD)
                    .hasMessageNotContaining(bad);
        }
    }

    @Test
    void readsThePasswordFromAFile_trimmingOnlyTheTrailingLineBreaks() throws IOException {
        for (String ending : new String[] { "", "\n", "\r\n", "\n\n" }) {
            MockEnvironment env = complete().withProperty(BootstrapAdminSettings.PASSWORD_FILE,
                    secretFile(PASSWORD + ending).toString());
            assertThat(BootstrapAdminSettings.readPassword(env)).isEqualTo(PASSWORD);
            assertThat(BootstrapAdminSettings.validateAll(env)).isNotNull();
        }
        // Los espacios forman parte de la contraseña: no se recortan.
        MockEnvironment spaces = complete().withProperty(BootstrapAdminSettings.PASSWORD_FILE,
                secretFile("  " + PASSWORD + "  \n").toString());
        assertThat(BootstrapAdminSettings.readPassword(spaces)).isEqualTo("  " + PASSWORD + "  ");
    }

    @Test
    void theFileHasPrecedenceOverTheDirectVariable_evenWhenTheFileIsInvalid() throws IOException {
        MockEnvironment env = complete()
                .withProperty(BootstrapAdminSettings.PASSWORD, PASSWORD)
                .withProperty(BootstrapAdminSettings.PASSWORD_FILE, secretFile("otra-Clave-9X!z-larga").toString());
        assertThat(BootstrapAdminSettings.readPassword(env)).isEqualTo("otra-Clave-9X!z-larga");

        MockEnvironment missingFile = complete()
                .withProperty(BootstrapAdminSettings.PASSWORD, PASSWORD)
                .withProperty(BootstrapAdminSettings.PASSWORD_FILE, tmp.resolve("no-existe").toString());
        assertThatThrownBy(() -> BootstrapAdminSettings.validateAll(missingFile)) // no cae a la variable directa
                .isInstanceOf(BootstrapAdminConfigurationException.class)
                .hasMessageContaining(BootstrapAdminSettings.PASSWORD_FILE)
                .hasMessageNotContaining(PASSWORD)
                .hasMessageNotContaining("no-existe");

        MockEnvironment emptyFile = complete()
                .withProperty(BootstrapAdminSettings.PASSWORD_FILE, secretFile("\n").toString());
        assertThatThrownBy(() -> BootstrapAdminSettings.validateAll(emptyFile))
                .isInstanceOf(BootstrapAdminConfigurationException.class);
    }

    @Test
    void theDirectVariableIsUsedOnlyWhenThereIsNoFile() {
        MockEnvironment env = complete().withProperty(BootstrapAdminSettings.PASSWORD, PASSWORD);
        assertThat(BootstrapAdminSettings.readPassword(env)).isEqualTo(PASSWORD);
        assertThat(BootstrapAdminSettings.validateAll(env).email().value()).isEqualTo("primer.admin@tienda.example");
    }
}
