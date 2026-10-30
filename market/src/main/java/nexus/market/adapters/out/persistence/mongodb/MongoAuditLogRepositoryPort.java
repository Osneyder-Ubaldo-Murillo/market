package nexus.market.adapters.out.persistence.mongodb;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;

import com.mongodb.client.MongoClient;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import nexus.market.domain.enums.AuditSeverity;
import nexus.market.domain.ports.out.AuditLogRepositoryPort;
import nexus.market.domain.valueobjects.OperationType;

/**
 * Adaptador de salida {@link AuditLogRepositoryPort} sobre MongoDB.
 *
 * <p>Se activa con el perfil {@code mongodb} o con {@code prod} (el despliegue Docker usa {@code jpa,prod}, así que la
 * auditoría llega a MongoDB sin un perfil adicional; ver {@code application-jpa.properties}:
 * {@code spring.data.mongodb.uri}). El registro es <strong>no transaccional</strong>
 * respecto a las escrituras principales: un fallo de auditoría se registra en el
 * log de la aplicación pero nunca propaga la excepción (regla 5 de la SDD).</p>
 */
@Component
@Profile({ "mongodb", "prod" })
public class MongoAuditLogRepositoryPort implements AuditLogRepositoryPort {

    private static final Logger log = LoggerFactory.getLogger(MongoAuditLogRepositoryPort.class);

    private static final String COLLECTION = "audit_log";

    private final MongoClient mongoClient;
    private final String database;

    public MongoAuditLogRepositoryPort(MongoClient mongoClient,
                                       @Value("${spring.data.mongodb.database:nexusmarket_audit}") String database) {
        this.mongoClient = mongoClient;
        this.database = database;
    }

    @Override
    public void append(OperationType operation, AuditSeverity severity, LocalDateTime occurredAt,
                       String actorId, String description, Map<String, Object> metadata) {
        try {
            Document document = new Document()
                    .append("operation", operation.getCode())
                    .append("operationName", operation.getName())
                    .append("severity", severity.name())
                    .append("occurredAt", toDate(occurredAt))
                    .append("actorId", actorId == null ? "" : actorId)
                    .append("description", description == null ? "" : description)
                    .append("metadata", metadata == null ? new Document() : new Document(metadata));
            mongoClient.getDatabase(database).getCollection(COLLECTION).insertOne(document);
        } catch (RuntimeException ex) {
            // La auditoría nunca debe romper la operación de negocio.
            // Se conserva el registro en el log para no perderlo si MongoDB no responde.
            log.warn("No se pudo registrar la auditoría en MongoDB ({}); se conserva en el log: {} | {} | actor={} | {}",
                    ex.getClass().getSimpleName(), operation.getCode(), severity, actorId, description);
        }
    }

    private static Date toDate(LocalDateTime dateTime) {
        return dateTime == null ? null : Date.from(dateTime.atZone(ZoneId.systemDefault()).toInstant());
    }
}