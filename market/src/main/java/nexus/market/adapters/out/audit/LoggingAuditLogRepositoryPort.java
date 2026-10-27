package nexus.market.adapters.out.audit;

import java.time.LocalDateTime;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import nexus.market.domain.enums.AuditSeverity;
import nexus.market.domain.ports.out.AuditLogRepositoryPort;
import nexus.market.domain.valueobjects.OperationType;

/**
 * Adaptador de salida {@link AuditLogRepositoryPort} de respaldo: registra la
 * auditoría en el log de la aplicación.
 *
 * <p>Se activa cuando el perfil {@code mongodb} NO está presente (por ejemplo
 * con el solo perfil {@code jpa} o en pruebas), de modo que {@code AuditService}
 * siempre tenga una implementación disponible. Con el perfil {@code mongodb},
 * la auditoría persiste en MongoDB (ver
 * {@code adapters.out.persistence.mongodb.MongoAuditLogRepositoryPort}).</p>
 */
@Component
@Profile("!mongodb")
public class LoggingAuditLogRepositoryPort implements AuditLogRepositoryPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingAuditLogRepositoryPort.class);

    @Override
    public void append(OperationType operation, AuditSeverity severity, LocalDateTime occurredAt,
                       String actorId, String description, Map<String, Object> metadata) {
        log.info("[AUDIT] {} | {} | actor={} | {} | metadata={}",
                operation.getCode(), severity, actorId, description, metadata);
    }
}
