package nexus.market.infrastructure.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.transaction.PlatformTransactionManager;

import nexus.market.application.AdminProvisioningLockPort;
import nexus.market.application.InitialAdminService;
import nexus.market.application.InitialAdminStorePort;
import nexus.market.domain.ports.out.PasswordServicePort;

/**
 * Creación controlada del primer administrador (ver {@code docs/ADMIN_INICIAL_PROPUESTA.md}).
 *
 * <ul>
 *   <li><b>Deshabilitado por defecto</b>: sin {@code NEXUSMARKET_BOOTSTRAP_ADMIN_ENABLED=true} esta
 *       configuración no se carga y no existe ningún componente.</li>
 *   <li><b>Fail-fast</b>: habilitado, la configuración se valida antes de crear ningún bean (y antes de
 *       conectar con la base de datos); si falta o es inválida algún dato, la aplicación no arranca. También
 *       falla si falta el perfil {@code jpa}.</li>
 *   <li>El aprovisionamiento corre al final de la inicialización de los singletons (tras Flyway/JPA y antes de
 *       que el servidor web acepte peticiones).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@Conditional(BootstrapAdminConfiguration.EnabledCondition.class)
public class BootstrapAdminConfiguration {

    static final class EnabledCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return BootstrapAdminSettings.isEnabled(context.getEnvironment());
        }
    }

    /** Se ejecuta antes de instanciar los beans normales. */
    @Bean
    static BeanFactoryPostProcessor bootstrapAdminConfigurationCheck(Environment env) {
        return beanFactory -> {
            if (!env.acceptsProfiles(Profiles.of("jpa"))) {
                throw new BootstrapAdminConfigurationException(BootstrapAdminSettings.ENABLED
                        + "=true requiere el perfil de persistencia \"jpa\" (SPRING_PROFILES_ACTIVE=jpa,...).");
            }
            BootstrapAdminSettings.validateAll(env);
        };
    }

    // El escaneo de componentes registra esta clase anidada por su cuenta: necesita su propia condición.
    @Configuration(proxyBeanMethods = false)
    @Conditional(EnabledCondition.class)
    @Profile("jpa")
    static class Wiring {

        @Bean
        InitialAdminService initialAdminService(InitialAdminStorePort store, AdminProvisioningLockPort lock,
                                                PasswordServicePort passwords,
                                                PlatformTransactionManager transactionManager) {
            return new InitialAdminService(store, lock, passwords, transactionManager);
        }

        @Bean
        InitialAdminBootstrap initialAdminBootstrap(Environment env, InitialAdminService service) {
            return new InitialAdminBootstrap(env, service);
        }
    }

    /** Ejecuta el aprovisionamiento; una excepción impide el arranque. */
    static final class InitialAdminBootstrap implements SmartInitializingSingleton {

        private static final Logger log = LoggerFactory.getLogger(InitialAdminBootstrap.class);

        private final Environment env;
        private final InitialAdminService service;

        InitialAdminBootstrap(Environment env, InitialAdminService service) {
            this.env = env;
            this.service = service;
        }

        @Override
        public void afterSingletonsInstantiated() {
            BootstrapAdminSettings.Identity identity = BootstrapAdminSettings.readIdentity(env);
            // La contraseña se lee solo si hace falta y no se conserva.
            InitialAdminService.Result result = service.provision(identity.fullName(), identity.email(),
                    identity.documentId(), () -> BootstrapAdminSettings.readPassword(env));
            if (result == InitialAdminService.Result.CREATED) {
                log.info("Administrador inicial creado (id={}).", InitialAdminService.INITIAL_ADMIN_ID);
                log.warn("Retire del entorno las variables NEXUSMARKET_BOOTSTRAP_ADMIN_* y desactive "
                        + "NEXUSMARKET_BOOTSTRAP_ADMIN_ENABLED tras este primer arranque.");
            } else {
                log.warn("Ya existe un administrador; no se crea otro ni se modifica ninguno. "
                        + "Desactive NEXUSMARKET_BOOTSTRAP_ADMIN_ENABLED.");
            }
        }
    }
}
