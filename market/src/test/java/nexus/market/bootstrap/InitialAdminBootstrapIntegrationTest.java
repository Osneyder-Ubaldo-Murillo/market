package nexus.market.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import nexus.market.MarketApplication;
import nexus.market.application.InitialAdminService;
import nexus.market.domain.models.User;
import nexus.market.domain.ports.in.AuthUseCases;
import nexus.market.domain.ports.out.PasswordServicePort;
import nexus.market.domain.ports.out.UserRepositoryPort;
import nexus.market.domain.valueobjects.DocumentId;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.FullName;
import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.infrastructure.bootstrap.BootstrapAdminSettings;

/**
 * Aprovisionamiento del administrador inicial con la aplicación completa (perfil {@code jpa}, H2 en memoria, una
 * base por prueba). Se comprueba el ESTADO FINAL de las tablas, no solo que el arranque termine.
 */
@ExtendWith(OutputCaptureExtension.class)
class InitialAdminBootstrapIntegrationTest {

    private static final String EMAIL = "primer.admin@tienda.example";
    private static final String PASSWORD = "Rk7#pQzLm2!vXw9T";
    private static final String OTHER_PASSWORD = "Zw4$nBv8!kTq3Lp";

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static String db() {
        return "bootstrap_" + UUID.randomUUID().toString().replace("-", "");
    }

    private ConfigurableApplicationContext start(String db, List<String> extraArgs, Class<?>... extraSources) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "--spring.datasource.username=sa",
                "--spring.datasource.password=",
                "--spring.jpa.hibernate.ddl-auto=update",
                "--spring.data.mongodb.uri=",
                "--spring.data.mongodb.database=test_audit",
                "--spring.main.banner-mode=off",
                "--server.port=0"));
        args.addAll(extraArgs);
        Class<?>[] sources = new Class<?>[1 + extraSources.length];
        sources[0] = MarketApplication.class;
        System.arraycopy(extraSources, 0, sources, 1, extraSources.length);
        return new SpringApplicationBuilder(sources)
                .profiles("jpa")
                .web(WebApplicationType.SERVLET)
                .run(args.toArray(new String[0]));
    }

    private Path passwordFile(String content) throws Exception {
        Path file = tmp.resolve("pw-" + UUID.randomUUID());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static List<String> enabled(Path passwordFile) {
        return List.of(
                "--" + BootstrapAdminSettings.ENABLED + "=true",
                "--" + BootstrapAdminSettings.EMAIL + "=" + EMAIL,
                "--" + BootstrapAdminSettings.FULL_NAME + "=Administradora Inicial",
                "--" + BootstrapAdminSettings.DOCUMENT_ID + "=ADM-2026-01",
                "--" + BootstrapAdminSettings.PASSWORD_FILE + "=" + passwordFile);
    }

    private static long count(ConfigurableApplicationContext ctx, String sql, Object... params) {
        try (EntityManager em = ctx.getBean(EntityManagerFactory.class).createEntityManager()) {
            var query = em.createNativeQuery(sql);
            for (int i = 0; i < params.length; i++) {
                query.setParameter(i + 1, params[i]);
            }
            return ((Number) query.getSingleResult()).longValue();
        }
    }

    /** Todas las filas de users y de user_credentials, para comparar "antes y después". */
    private static String snapshot(ConfigurableApplicationContext ctx) {
        try (EntityManager em = ctx.getBean(EntityManagerFactory.class).createEntityManager()) {
            StringBuilder text = new StringBuilder();
            for (Object row : em.createNativeQuery(
                    "select id, full_name, email, document_id, role, status, created_at, updated_at from users order by id")
                    .getResultList()) {
                text.append(java.util.Arrays.toString((Object[]) row)).append('\n');
            }
            for (Object row : em.createNativeQuery(
                    "select user_id, created_at, password_hash from user_credentials order by user_id").getResultList()) {
                text.append(java.util.Arrays.toString((Object[]) row)).append('\n');
            }
            return text.toString();
        }
    }

    private static String credentialHash(ConfigurableApplicationContext ctx, String userId) {
        try (EntityManager em = ctx.getBean(EntityManagerFactory.class).createEntityManager()) {
            return (String) em.createNativeQuery("select password_hash from user_credentials where user_id = ?1")
                    .setParameter(1, userId).getSingleResult();
        }
    }

    private static User adminUser(String name, String email, String doc) {
        return User.create(FullName.of(name), Email.of(email), DocumentId.of(doc), SystemRole.ADMIN);
    }

    private static String rootMessages(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            all.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" | ");
        }
        return all.toString();
    }

    // ------------------------------------------------------------------
    // 1. Deshabilitado por defecto
    // ------------------------------------------------------------------

    @Test
    void disabledByDefault_createsNoAdministrator() {
        try (ConfigurableApplicationContext ctx = start(db(), List.of())) {
            assertThat(count(ctx, "select count(*) from users where role = 'ADMIN'")).isZero();
            assertThat(count(ctx, "select count(*) from users")).isZero();
            assertThat(count(ctx, "select count(*) from user_credentials")).isZero();
            assertThat(ctx.getBeansOfType(InitialAdminService.class)).isEmpty();
        }
    }

    @Test
    void enabledFlagFalseOrMisspelled_createsNoAdministrator() throws Exception {
        Path file = passwordFile(PASSWORD);
        for (String flag : new String[] { "false", "yes", "ture" }) {
            List<String> args = new ArrayList<>(enabled(file));
            args.set(0, "--" + BootstrapAdminSettings.ENABLED + "=" + flag);
            try (ConfigurableApplicationContext ctx = start(db(), args)) {
                assertThat(count(ctx, "select count(*) from users where role = 'ADMIN'")).as(flag).isZero();
            }
        }
    }

    // ------------------------------------------------------------------
    // 4. Creación correcta + confidencialidad
    // ------------------------------------------------------------------

    @Test
    void enabledWithValidConfiguration_createsTheAdministrator_andNeverLeaksSecrets(CapturedOutput output) throws Exception {
        Path file = passwordFile(PASSWORD + "\n"); // el salto de línea final no forma parte de la contraseña
        String hash;
        try (ConfigurableApplicationContext ctx = start(db(), enabled(file))) {
            String id = InitialAdminService.INITIAL_ADMIN_ID.value();
            assertThat(count(ctx, "select count(*) from users")).isEqualTo(1);
            assertThat(count(ctx, "select count(*) from users where role = 'ADMIN' and status = 'ACTIVE' and id = ?1", id))
                    .isEqualTo(1);
            assertThat(count(ctx, "select count(*) from users where email = ?1 and document_id = ?2",
                    EMAIL, "ADM-2026-01")).isEqualTo(1);
            assertThat(count(ctx, "select count(*) from user_credentials where user_id = ?1", id)).isEqualTo(1);

            hash = credentialHash(ctx, id);
            assertThat(hash).isNotEqualTo(PASSWORD).doesNotContain(PASSWORD).startsWith("$2");
            assertThat(ctx.getBean(PasswordServicePort.class).verify(PASSWORD, hash)).isTrue();

            // Inicio de sesión real con el administrador creado.
            AuthUseCases.AuthResult login = ctx.getBean(AuthUseCases.class).login(Email.of(EMAIL), PASSWORD);
            assertThat(login.role()).isEqualTo(SystemRole.ADMIN);
            assertThat(login.token()).isNotBlank();

            // El componente de arranque no conserva la contraseña (ni ningún texto).
            Object bootstrap = ctx.getBeansOfType(Class.forName(
                    "nexus.market.infrastructure.bootstrap.BootstrapAdminConfiguration$InitialAdminBootstrap"))
                    .values().iterator().next();
            for (Field field : bootstrap.getClass().getDeclaredFields()) {
                assertThat(field.getType()).as(field.getName()).isNotEqualTo(String.class);
            }
        }
        assertThat(output.getAll()).contains("Administrador inicial creado")
                .doesNotContain(PASSWORD).doesNotContain(hash).doesNotContain(OTHER_PASSWORD);
    }

    @Test
    void theCreatedAdministratorCanBeBootstrappedFromADirectVariableToo() throws Exception {
        List<String> args = new ArrayList<>(enabled(passwordFile(PASSWORD)));
        args.removeIf(arg -> arg.startsWith("--" + BootstrapAdminSettings.PASSWORD_FILE));
        args.add("--" + BootstrapAdminSettings.PASSWORD + "=" + PASSWORD);
        try (ConfigurableApplicationContext ctx = start(db(), args)) {
            assertThat(count(ctx, "select count(*) from users where role = 'ADMIN'")).isEqualTo(1);
            assertThat(ctx.getBean(PasswordServicePort.class)
                    .verify(PASSWORD, credentialHash(ctx, InitialAdminService.INITIAL_ADMIN_ID.value()))).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // 5. Segunda ejecución: no cambia nada
    // ------------------------------------------------------------------

    @Test
    void secondRun_doesNotChangeThePasswordOrAnyDataOfTheExistingAdministrator() throws Exception {
        String db = db();
        String before;
        try (ConfigurableApplicationContext first = start(db, enabled(passwordFile(PASSWORD)))) {
            before = snapshot(first);
            assertThat(count(first, "select count(*) from users where role = 'ADMIN'")).isEqualTo(1);
        }
        // Mismo arranque con OTRA contraseña, OTRO correo y OTRO documento en las variables.
        List<String> different = List.of(
                "--" + BootstrapAdminSettings.ENABLED + "=true",
                "--" + BootstrapAdminSettings.EMAIL + "=otro.correo@tienda.example",
                "--" + BootstrapAdminSettings.FULL_NAME + "=Otra Persona",
                "--" + BootstrapAdminSettings.DOCUMENT_ID + "=OTRO-9999",
                "--" + BootstrapAdminSettings.PASSWORD_FILE + "=" + passwordFile(OTHER_PASSWORD));
        try (ConfigurableApplicationContext second = start(db, different)) {
            assertThat(snapshot(second)).as("ninguna fila cambió").isEqualTo(before);
            assertThat(count(second, "select count(*) from users where role = 'ADMIN'")).isEqualTo(1);
            String hash = credentialHash(second, InitialAdminService.INITIAL_ADMIN_ID.value());
            assertThat(second.getBean(PasswordServicePort.class).verify(PASSWORD, hash)).isTrue();
            assertThat(second.getBean(PasswordServicePort.class).verify(OTHER_PASSWORD, hash)).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // 6/7. Administradores preexistentes (cualquier estado, ids distintos)
    // ------------------------------------------------------------------

    @Test
    void twoExistingAdministratorsWithDifferentIds_areLeftUntouched_andNoThirdIsCreated() throws Exception {
        String db = db();
        String before;
        try (ConfigurableApplicationContext seed = start(db, List.of())) {
            UserRepositoryPort users = seed.getBean(UserRepositoryPort.class);
            users.save(adminUser("Admin Uno", "uno@otra.example", "UNO-0001"));
            users.save(adminUser("Admin Dos", "dos@otra.example", "DOS-0002"));
            before = snapshot(seed);
        }
        try (ConfigurableApplicationContext ctx = start(db, enabled(passwordFile(PASSWORD)))) {
            assertThat(count(ctx, "select count(*) from users where role = 'ADMIN'")).isEqualTo(2);
            assertThat(count(ctx, "select count(*) from users where id = ?1",
                    InitialAdminService.INITIAL_ADMIN_ID.value())).isZero();
            assertThat(count(ctx, "select count(*) from user_credentials")).isZero();
            assertThat(snapshot(ctx)).isEqualTo(before);
        }
    }

    @Test
    void aBlockedOrInactiveAdministrator_counts_andIsNotReactivatedOrReplaced() throws Exception {
        String db = db();
        String before;
        try (ConfigurableApplicationContext seed = start(db, List.of())) {
            UserRepositoryPort users = seed.getBean(UserRepositoryPort.class);
            User blocked = adminUser("Admin Bloqueado", "bloqueado@otra.example", "BLQ-0001");
            blocked.block();
            users.save(blocked);
            User inactive = adminUser("Admin Inactivo", "inactivo@otra.example", "INA-0001");
            inactive.deactivate();
            users.save(inactive);
            before = snapshot(seed);
        }
        try (ConfigurableApplicationContext ctx = start(db, enabled(passwordFile(PASSWORD)))) {
            assertThat(count(ctx, "select count(*) from users where role = 'ADMIN'")).isEqualTo(2);
            assertThat(count(ctx, "select count(*) from users where role = 'ADMIN' and status = 'ACTIVE'")).isZero();
            assertThat(snapshot(ctx)).isEqualTo(before);
        }
    }

    @Test
    void emailOrDocumentOfANonAdministrator_failsStartup_withoutModifyingThatUser() throws Exception {
        for (String[] clash : new String[][] { { EMAIL, "OTRO-0001" }, { "otro@tienda.example", "ADM-2026-01" } }) {
            String db = db();
            String before;
            try (ConfigurableApplicationContext seed = start(db, List.of())) {
                seed.getBean(UserRepositoryPort.class).save(User.create(FullName.of("Un Comprador"),
                        Email.of(clash[0]), DocumentId.of(clash[1]), SystemRole.BUYER));
                before = snapshot(seed);
            }
            Path file = passwordFile(PASSWORD);
            assertThatThrownBy(() -> start(db, enabled(file)))
                    .satisfies(failure -> assertThat(rootMessages(failure))
                            .contains("no es administrador")
                            .doesNotContain(PASSWORD));
            try (ConfigurableApplicationContext after = start(db, List.of())) {
                assertThat(snapshot(after)).as("el usuario no se tocó y no se creó ningún administrador").isEqualTo(before);
                assertThat(count(after, "select count(*) from users where role = 'ADMIN'")).isZero();
            }
        }
    }

    // ------------------------------------------------------------------
    // 11. Rollback: un fallo al guardar credenciales no deja usuario
    // ------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    static class OversizedHashPasswords {
        @Bean
        @Primary
        PasswordServicePort oversizedHashes() {
            return new PasswordServicePort() {
                @Override
                public String hash(String rawPassword) {
                    return "x".repeat(300); // no cabe en user_credentials.password_hash (varchar(100))
                }

                @Override
                public boolean verify(String rawPassword, String hashedPassword) {
                    return false;
                }
            };
        }
    }

    @Test
    void failureWhileSavingCredentials_rollsBackTheUser() throws Exception {
        String db = db();
        Path file = passwordFile(PASSWORD);
        assertThatThrownBy(() -> start(db, enabled(file), OversizedHashPasswords.class))
                .satisfies(failure -> assertThat(rootMessages(failure)).doesNotContain(PASSWORD));
        try (ConfigurableApplicationContext after = start(db, List.of())) {
            assertThat(count(after, "select count(*) from users")).as("el usuario insertado se revirtió").isZero();
            assertThat(count(after, "select count(*) from user_credentials")).isZero();
        }
    }

    // ------------------------------------------------------------------
    // Fail-fast con la aplicación completa
    // ------------------------------------------------------------------

    @Test
    void invalidConfiguration_preventsStartup_andCreatesNothing(CapturedOutput output) throws Exception {
        String db = db();
        List<String> weak = new ArrayList<>(enabled(passwordFile("Password-12345-x")));
        assertThatThrownBy(() -> start(db, weak))
                .satisfies(failure -> assertThat(rootMessages(failure))
                        .contains(BootstrapAdminSettings.PASSWORD_FILE).doesNotContain("Password-12345-x"));
        List<String> missing = List.of("--" + BootstrapAdminSettings.ENABLED + "=true");
        assertThatThrownBy(() -> start(db, missing))
                .satisfies(failure -> assertThat(rootMessages(failure)).contains(BootstrapAdminSettings.EMAIL));
        assertThat(output.getAll()).doesNotContain("Password-12345-x");
        try (ConfigurableApplicationContext after = start(db, List.of())) {
            assertThat(count(after, "select count(*) from users")).isZero();
        }
    }

    // ------------------------------------------------------------------
    // Tamaño del pool: el aprovisionamiento necesita 2 conexiones simultáneas
    // ------------------------------------------------------------------

    @Test
    void enabledWithAPoolOfOne_failsFast_withoutWaitingForAConnectionTimeout() throws Exception {
        String db = db();
        Path file = passwordFile(PASSWORD);
        List<String> args = new ArrayList<>(enabled(file));
        args.add("--spring.datasource.hikari.maximum-pool-size=1");
        long started = System.nanoTime();
        assertThatThrownBy(() -> start(db, args))
                .satisfies(failure -> assertThat(rootMessages(failure))
                        .contains("al menos 2 conexiones simultáneas").doesNotContain(PASSWORD));
        long seconds = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
        assertThat(seconds).as("falla pronto (no espera los 30 s de connectionTimeout de Hikari)").isLessThan(25);
        try (ConfigurableApplicationContext after = start(db, List.of())) {
            assertThat(count(after, "select count(*) from users")).isZero();
        }
    }

    @Test
    void disabledWithAPoolOfOne_startsAndWorksNormally_withoutAnyBootstrapValidation() {
        try (ConfigurableApplicationContext ctx = start(db(), List.of("--spring.datasource.hikari.maximum-pool-size=1"))) {
            assertThat(count(ctx, "select count(*) from users")).isZero();
            assertThat(ctx.getBeansOfType(InitialAdminService.class)).isEmpty();
        }
    }

    @Test
    void enabledWithAPoolOfExactlyTwo_hasEnoughCapacity_andCreatesTheAdministrator() throws Exception {
        List<String> args = new ArrayList<>(enabled(passwordFile(PASSWORD)));
        args.add("--spring.datasource.hikari.maximum-pool-size=2");
        try (ConfigurableApplicationContext ctx = start(db(), args)) {
            assertThat(count(ctx, "select count(*) from users where role = 'ADMIN'")).isEqualTo(1);
            assertThat(count(ctx, "select count(*) from user_credentials")).isEqualTo(1);
        }
    }
}
