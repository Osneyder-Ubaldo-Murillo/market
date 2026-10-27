package nexus.market.infrastructure.config;

import java.time.LocalDateTime;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import nexus.market.domain.enums.AuditSeverity;
import nexus.market.domain.ports.out.AuditLogRepositoryPort;
import nexus.market.domain.valueobjects.OperationType;

/**
 * La auditoría (log o MongoDB) no participa en la transacción de MySQL. Para que no quede
 * constancia de operaciones que luego se revierten, cuando hay una transacción de Spring en
 * curso el registro se difiere hasta <b>después del commit</b>; si la transacción se revierte,
 * no se registra. Sin transacción en curso se registra de inmediato.
 *
 * <p>Límite: es "al menos una vez tras el commit, mejor esfuerzo". Si la aplicación cae justo
 * entre el commit y el registro, esa entrada de auditoría se pierde (la operación sí queda
 * confirmada). Una garantía fuerte exigiría una tabla outbox, que requiere cambiar el esquema.</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile("jpa")
public class TransactionAwareAuditConfiguration {

    @Bean
    static BeanPostProcessor transactionAwareAuditPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof AuditLogRepositoryPort delegate && !(bean instanceof AfterCommitAuditLog)) {
                    return new AfterCommitAuditLog(delegate);
                }
                return bean;
            }
        };
    }

    /** Decorador visible para pruebas. */
    public static final class AfterCommitAuditLog implements AuditLogRepositoryPort {

        private static final Logger log = LoggerFactory.getLogger(AfterCommitAuditLog.class);

        private final AuditLogRepositoryPort delegate;

        public AfterCommitAuditLog(AuditLogRepositoryPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public void append(OperationType operation, AuditSeverity severity, LocalDateTime occurredAt,
                           String actorId, String description, Map<String, Object> metadata) {
            if (TransactionSynchronizationManager.isSynchronizationActive()
                    && TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            delegate.append(operation, severity, occurredAt, actorId, description, metadata);
                        } catch (RuntimeException ex) {
                            log.warn("No se pudo registrar la auditoría diferida ({}): {}",
                                    operation.getCode(), ex.getMessage());
                        }
                    }
                });
            } else {
                delegate.append(operation, severity, occurredAt, actorId, description, metadata);
            }
        }
    }
}
