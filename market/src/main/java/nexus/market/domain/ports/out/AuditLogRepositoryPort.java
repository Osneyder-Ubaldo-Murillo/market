package nexus.market.domain.ports.out;

import java.time.LocalDateTime;
import java.util.Map;

import nexus.market.domain.enums.AuditSeverity;
import nexus.market.domain.valueobjects.OperationType;

/**
 * Puerto de salida para el registro de auditoría (destino natural: MongoDB).
 * Es el único puerto cuyo fallo NO debe revertir la operación de negocio
 * (se recomienda un adaptador asíncrono).
 */
public interface AuditLogRepositoryPort {

    void append(OperationType operation, AuditSeverity severity, LocalDateTime occurredAt,
                String actorId, String description, Map<String, Object> metadata);
}