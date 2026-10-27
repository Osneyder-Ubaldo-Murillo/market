package nexus.market.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import nexus.market.application.InitialAdminService;
import nexus.market.infrastructure.bootstrap.BootstrapAdminConfiguration;
import nexus.market.infrastructure.bootstrap.BootstrapAdminSettings;

/**
 * Activación del mecanismo: deshabilitado por defecto, fail-fast antes de crear ningún bean (y por tanto antes de
 * conectar con la base de datos), exige el perfil {@code jpa} y nunca revela valores.
 */
class BootstrapAdminStartupTest {

    private static final String PASSWORD = "Rk7#pQzLm2!vXw9T";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BootstrapAdminConfiguration.class);

    private static String rootMessages(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            all.append(t.getMessage()).append(" | ");
        }
        return all.toString();
    }

    @Test
    void withoutTheEnabledVariable_nothingIsLoaded() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(InitialAdminService.class);
        });
        runner.withPropertyValues(BootstrapAdminSettings.ENABLED + "=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(InitialAdminService.class);
        });
    }

    @Test
    void anyValueOtherThanTrue_keepsItDisabled_evenWithTheRestOfTheVariablesDefined() {
        runner.withPropertyValues(
                BootstrapAdminSettings.ENABLED + "=yes",
                BootstrapAdminSettings.EMAIL + "=primer.admin@tienda.example",
                BootstrapAdminSettings.PASSWORD + "=" + PASSWORD)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(InitialAdminService.class));
    }

    @Test
    void enabledWithoutThePersistenceProfile_failsToStart() {
        runner.withPropertyValues(BootstrapAdminSettings.ENABLED + "=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessages(context.getStartupFailure())).contains("jpa");
        });
    }

    @Test
    void enabledWithMissingData_failsToStart_namingTheVariables() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("jpa"))
                .withPropertyValues(BootstrapAdminSettings.ENABLED + "=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    String message = rootMessages(context.getStartupFailure());
                    assertThat(message).contains(BootstrapAdminSettings.EMAIL, BootstrapAdminSettings.FULL_NAME,
                            BootstrapAdminSettings.DOCUMENT_ID, BootstrapAdminSettings.PASSWORD_FILE);
                });
    }

    @Test
    void enabledWithAnInvalidPassword_failsToStart_withoutLeakingItIntoTheFailure() {
        String weak = "Password-12345-x";
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("jpa"))
                .withPropertyValues(
                        BootstrapAdminSettings.ENABLED + "=true",
                        BootstrapAdminSettings.EMAIL + "=primer.admin@tienda.example",
                        BootstrapAdminSettings.FULL_NAME + "=Administradora Inicial",
                        BootstrapAdminSettings.DOCUMENT_ID + "=ADM-2026-01",
                        BootstrapAdminSettings.PASSWORD + "=" + weak)
                .run(context -> {
                    assertThat(context).hasFailed();
                    String message = rootMessages(context.getStartupFailure());
                    assertThat(message).contains(BootstrapAdminSettings.PASSWORD);
                    assertThat(message).doesNotContain(weak).doesNotContain("primer.admin@tienda.example");
                });
    }
}
