package nexus.market.infrastructure.config;

import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Exige credenciales de base de datos explícitas en producción. El perfil {@code prod} no
 * define valores por defecto; este control además rechaza, aunque otro perfil o una
 * variable los inyecte (p. ej. {@code prod,jpa} con las credenciales de desarrollo), una
 * contraseña vacía o una de las contraseñas de ejemplo conocidas. Los mensajes nombran la
 * propiedad, nunca su valor.
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionDatabaseCredentialsGuard {

    private static final Set<String> KNOWN_WEAK_PASSWORDS = Set.of(
            "root", "nexus", "password", "admin", "mysql", "changeme", "change-me", "123456", "12345678", "secret");

    /**
     * Se ejecuta antes de crear ningún bean (DataSource, Flyway, JPA): la aplicación falla con
     * un mensaje claro y sin intentar conectarse a la base de datos.
     */
    @Bean
    static BeanFactoryPostProcessor productionDatabaseCredentialsCheck(Environment env) {
        return beanFactory -> validate(env);
    }

    static void validate(Environment env) {
        validate(env.getProperty("spring.datasource.url"),
                env.getProperty("spring.datasource.username"),
                env.getProperty("spring.datasource.password"));
    }

    static void validate(String url, String username, String password) {
        require(url, "SPRING_DATASOURCE_URL");
        require(username, "SPRING_DATASOURCE_USERNAME");
        require(password, "SPRING_DATASOURCE_PASSWORD");
        String normalized = password.trim().toLowerCase(Locale.ROOT);
        if (KNOWN_WEAK_PASSWORDS.contains(normalized) || normalized.equals(username.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                    "SPRING_DATASOURCE_PASSWORD es una contraseña de ejemplo o igual al usuario; "
                            + "defina una contraseña propia para producción.");
        }
    }

    private static void require(String value, String variable) {
        // "${VAR}" sin resolver: Spring no falla por un marcador ausente en un .properties.
        if (value == null || value.isBlank() || value.contains("${")) {
            throw new IllegalStateException(
                    "En producción es obligatorio definir " + variable + " (sin valor por defecto).");
        }
    }
}
