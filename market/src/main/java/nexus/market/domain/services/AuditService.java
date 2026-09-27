package nexus.market.domain.services;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

import nexus.market.domain.enums.AuditSeverity;
import nexus.market.domain.ports.out.AuditLogRepositoryPort;
import nexus.market.domain.valueobjects.OperationType;

/**
 * Servicio de dominio central de auditoría: todos los servicios delegan aquí
 * el registro de sus operaciones (SDD - Domain Services).
 *
 * <p>El registro de auditoría es <strong>no transaccional</strong> respecto a
 * las escrituras principales: si el registro falla, la operación de negocio
 * no debe revertirse (ver SDD - Output Ports, regla 5).</p>
 */
public class AuditService {

    private final AuditLogRepositoryPort auditLog;

    public AuditService(AuditLogRepositoryPort auditLog) {
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog es obligatorio");
    }

    public void record(OperationType operation, AuditSeverity severity,
                       String actorId, String description) {
        try {
            auditLog.append(operation, severity, LocalDateTime.now(),
                    actorId, description, Map.of());
        } catch (RuntimeException ex) {
            // La auditoría nunca debe romper la operación de negocio.
            // El adaptador de producción debe ser asíncrono (colas/outbox).
        }
    }

    public void record(OperationType operation, AuditSeverity severity,
                       String actorId, String description, Map<String, Object> metadata) {
        try {
            auditLog.append(operation, severity, LocalDateTime.now(),
                    actorId, description, metadata == null ? Map.of() : Map.copyOf(metadata));
        } catch (RuntimeException ex) {
            // Idem: fallo de auditoría no revierte la operación.
        }
    }
}