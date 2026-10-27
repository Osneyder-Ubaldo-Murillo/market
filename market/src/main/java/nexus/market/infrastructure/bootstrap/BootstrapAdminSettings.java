package nexus.market.infrastructure.bootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

import nexus.market.application.AdminPasswordPolicy;
import nexus.market.domain.valueobjects.DocumentId;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.FullName;

/**
 * Lectura y validación de las variables del administrador inicial (ver {@code docs/ADMIN_INICIAL_PROPUESTA.md},
 * sección 1). Sin valores por defecto.
 *
 * <p>La contraseña NO se enlaza como propiedad de Spring ni se conserva: se lee en el momento de usarla
 * (archivo preferido, o variable) y se descarta. Ningún mensaje contiene valores.</p>
 */
public final class BootstrapAdminSettings {

    public static final String ENABLED = "NEXUSMARKET_BOOTSTRAP_ADMIN_ENABLED";
    public static final String EMAIL = "NEXUSMARKET_BOOTSTRAP_ADMIN_EMAIL";
    public static final String FULL_NAME = "NEXUSMARKET_BOOTSTRAP_ADMIN_FULL_NAME";
    public static final String DOCUMENT_ID = "NEXUSMARKET_BOOTSTRAP_ADMIN_DOCUMENT_ID";
    public static final String PASSWORD_FILE = "NEXUSMARKET_BOOTSTRAP_ADMIN_PASSWORD_FILE";
    public static final String PASSWORD = "NEXUSMARKET_BOOTSTRAP_ADMIN_PASSWORD";

    /** Datos no secretos del administrador. */
    public record Identity(Email email, FullName fullName, DocumentId documentId) {
    }

    private BootstrapAdminSettings() {
    }

    /** {@code true} solo si la variable vale exactamente "true" (sin distinguir mayúsculas). */
    public static boolean isEnabled(Environment env) {
        String value = env.getProperty(ENABLED);
        return value != null && value.trim().equalsIgnoreCase("true");
    }

    /**
     * Valida TODA la configuración (identidad y contraseña) y agrupa los problemas en un único mensaje.
     *
     * @throws BootstrapAdminConfigurationException con los nombres de las variables afectadas, sin valores
     */
    public static Identity validateAll(Environment env) {
        List<String> problems = new ArrayList<>();
        Identity identity = readIdentity(env, problems);
        String emailValue = identity != null ? identity.email().value() : null;
        validatePassword(env, emailValue, problems);
        validatePoolConfiguration(env, problems);
        if (!problems.isEmpty()) {
            throw new BootstrapAdminConfigurationException(
                    "Configuración inválida del administrador inicial (" + ENABLED + "=true): "
                            + String.join("; ", problems) + ".");
        }
        return identity;
    }

    /** Propiedad de Spring Boot que fija el tamaño del pool Hikari. */
    public static final String POOL_SIZE_PROPERTY = "spring.datasource.hikari.maximum-pool-size";
    /** Tamaño por defecto de Hikari cuando la propiedad no se define. */
    private static final int HIKARI_DEFAULT_POOL_SIZE = 10;
    /** Conexiones simultáneas que necesita el aprovisionamiento (candado dedicado + transacción). */
    public static final int REQUIRED_POOL_CONNECTIONS = 2;

    /**
     * Validación TEMPRANA de la configuración efectiva del pool, sin crear ningún bean ni abrir conexiones: lee la
     * propiedad con el {@link Binder} de Spring Boot (misma resolución que usará el {@code DataSource}: variables de
     * entorno, argumentos, perfiles, nombres con otra grafía).
     *
     * <ul>
     *   <li>propiedad ausente: Hikari usa 10, se acepta;</li>
     *   <li>valor menor que 2 (incluido 0 o negativo) o no numérico: se rechaza;</li>
     *   <li>{@code spring.datasource.type} distinto de Hikari: no se puede deducir el tamaño aquí; la comprobación
     *       definitiva ({@code PoolCapacity}) la hace el {@code DataSource} real antes de tomar el candado y rechaza
     *       cualquier pool que no sea Hikari.</li>
     * </ul>
     */
    public static void validatePoolConfiguration(Environment env) {
        List<String> problems = new ArrayList<>();
        validatePoolConfiguration(env, problems);
        if (!problems.isEmpty()) {
            throw new BootstrapAdminConfigurationException(String.join("; ", problems) + ".");
        }
    }

    private static void validatePoolConfiguration(Environment env, List<String> problems) {
        if (!(env instanceof ConfigurableEnvironment configurable)) {
            problems.add("No se puede determinar el tamaño del pool de conexiones (entorno no configurable); "
                    + "el administrador inicial necesita al menos " + REQUIRED_POOL_CONNECTIONS + " conexiones");
            return;
        }
        String type = env.getProperty("spring.datasource.type");
        if (!isBlank(type) && !type.toLowerCase().contains("hikari")) {
            return; // la comprobación definitiva la hace el DataSource real (PoolCapacity)
        }
        Integer configured;
        try {
            configured = Binder.get(configurable).bind(POOL_SIZE_PROPERTY, Integer.class).orElse(null);
        } catch (BindException invalid) {
            problems.add(POOL_SIZE_PROPERTY + " no es un número entero válido");
            return;
        }
        int effective = configured == null ? HIKARI_DEFAULT_POOL_SIZE : configured;
        if (effective < REQUIRED_POOL_CONNECTIONS) {
            problems.add("El administrador inicial necesita al menos " + REQUIRED_POOL_CONNECTIONS
                    + " conexiones simultáneas del pool (una dedicada al candado GET_LOCK y otra para la transacción), "
                    + "pero " + POOL_SIZE_PROPERTY + " vale " + effective + ". Configure un valor >= "
                    + REQUIRED_POOL_CONNECTIONS + " o desactive " + ENABLED);
        }
    }

    /** Solo la identidad (para el momento de uso). */
    public static Identity readIdentity(Environment env) {
        List<String> problems = new ArrayList<>();
        Identity identity = readIdentity(env, problems);
        if (!problems.isEmpty() || identity == null) {
            throw new BootstrapAdminConfigurationException(
                    "Configuración inválida del administrador inicial: " + String.join("; ", problems) + ".");
        }
        return identity;
    }

    private static Identity readIdentity(Environment env, List<String> problems) {
        Email email = null;
        FullName fullName = null;
        DocumentId documentId = null;

        String rawEmail = env.getProperty(EMAIL);
        if (isBlank(rawEmail)) {
            problems.add(EMAIL + " es obligatoria");
        } else {
            try {
                email = Email.of(rawEmail);
            } catch (IllegalArgumentException invalid) {
                problems.add(EMAIL + " no es un correo válido (formato o más de 160 caracteres)");
            }
        }
        String rawName = env.getProperty(FULL_NAME);
        if (isBlank(rawName)) {
            problems.add(FULL_NAME + " es obligatoria");
        } else {
            try {
                fullName = FullName.of(rawName);
            } catch (IllegalArgumentException invalid) {
                problems.add(FULL_NAME + " no es válida (2 a 100 caracteres)");
            }
        }
        String rawDocument = env.getProperty(DOCUMENT_ID);
        if (isBlank(rawDocument)) {
            problems.add(DOCUMENT_ID + " es obligatoria");
        } else {
            try {
                documentId = DocumentId.of(rawDocument);
            } catch (IllegalArgumentException invalid) {
                problems.add(DOCUMENT_ID + " no es válida (4 a 20 caracteres alfanuméricos o guiones)");
            }
        }
        return email != null && fullName != null && documentId != null ? new Identity(email, fullName, documentId) : null;
    }

    private static void validatePassword(Environment env, String email, List<String> problems) {
        String source = isBlank(env.getProperty(PASSWORD_FILE)) ? PASSWORD : PASSWORD_FILE;
        String password;
        try {
            password = readPassword(env);
        } catch (BootstrapAdminConfigurationException missing) {
            problems.add(missing.getMessage());
            return;
        }
        try {
            AdminPasswordPolicy.validate(password, email);
        } catch (IllegalArgumentException invalid) {
            problems.add(source + ": " + invalid.getMessage());
        }
    }

    /**
     * Lee la contraseña. Precedencia: si {@code NEXUSMARKET_BOOTSTRAP_ADMIN_PASSWORD_FILE} está definida se usa
     * SOLO el archivo (aunque exista también la variable directa); si no, la variable directa. Del archivo se
     * recortan los saltos de línea finales; no se toca nada más.
     */
    public static String readPassword(Environment env) {
        String file = env.getProperty(PASSWORD_FILE);
        if (!isBlank(file)) {
            try {
                String content = Files.readString(Path.of(file.trim()), StandardCharsets.UTF_8);
                int end = content.length();
                while (end > 0 && (content.charAt(end - 1) == '\n' || content.charAt(end - 1) == '\r')) {
                    end--;
                }
                return content.substring(0, end);
            } catch (IOException | RuntimeException unreadable) {
                throw new BootstrapAdminConfigurationException(
                        PASSWORD_FILE + " apunta a un archivo que no se puede leer");
            }
        }
        String direct = env.getProperty(PASSWORD);
        if (isBlank(direct)) {
            throw new BootstrapAdminConfigurationException(
                    PASSWORD_FILE + " (preferida) o " + PASSWORD + " es obligatoria");
        }
        return direct;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
