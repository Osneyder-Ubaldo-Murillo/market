package nexus.market.adapters.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Map;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import nexus.market.adapters.out.audit.LoggingAuditLogRepositoryPort;
import nexus.market.adapters.out.persistence.mongodb.MongoAuditLogRepositoryPort;
import nexus.market.domain.enums.AuditSeverity;
import nexus.market.domain.ports.out.AuditLogRepositoryPort;
import nexus.market.domain.valueobjects.OperationType;

/**
 * La auditoría persiste en MongoDB con el perfil {@code prod} (el que usan el Dockerfile y el compose) o {@code mongodb};
 * sin ellos, en desarrollo y pruebas, se registra en el log. Un fallo de MongoDB nunca rompe la operación de negocio.
 */
class AuditAdapterSelectionTest {

    @Configuration
    static class MongoClientConfig {
        @Bean
        MongoClient mongoClient() {
            return mock(MongoClient.class);
        }
    }

    private ApplicationContextRunner runnerWithProfiles(String... profiles) {
        return new ApplicationContextRunner()
                .withUserConfiguration(MongoClientConfig.class, MongoAuditLogRepositoryPort.class,
                        LoggingAuditLogRepositoryPort.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles));
    }

    @Test
    void prodProfile_usesMongoAudit() {
        runnerWithProfiles("jpa", "prod").run(context -> {
            assertThat(context).hasSingleBean(AuditLogRepositoryPort.class);
            assertThat(context).hasSingleBean(MongoAuditLogRepositoryPort.class);
            assertThat(context).doesNotHaveBean(LoggingAuditLogRepositoryPort.class);
        });
    }

    @Test
    void mongodbProfile_usesMongoAudit() {
        runnerWithProfiles("jpa", "mongodb").run(context -> {
            assertThat(context).hasSingleBean(MongoAuditLogRepositoryPort.class);
            assertThat(context).doesNotHaveBean(LoggingAuditLogRepositoryPort.class);
        });
    }

    @Test
    void developmentProfile_usesLogAudit() {
        runnerWithProfiles("jpa").run(context -> {
            assertThat(context).hasSingleBean(AuditLogRepositoryPort.class);
            assertThat(context).hasSingleBean(LoggingAuditLogRepositoryPort.class);
            assertThat(context).doesNotHaveBean(MongoAuditLogRepositoryPort.class);
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void mongoAdapter_insertsTheAuditDocument() {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(client.getDatabase("nexusmarket_audit")).thenReturn(database);
        when(database.getCollection("audit_log")).thenReturn(collection);

        new MongoAuditLogRepositoryPort(client, "nexusmarket_audit").append(OperationType.ORDER_PAYMENT,
                AuditSeverity.INFO, LocalDateTime.of(2026, 10, 29, 10, 0), "actor-1", "Pago confirmado",
                Map.of("orderId", "o-1"));

        ArgumentCaptor<Document> document = ArgumentCaptor.forClass(Document.class);
        verify(collection).insertOne(document.capture());
        assertThat(document.getValue().getString("operation")).isEqualTo(OperationType.ORDER_PAYMENT.getCode());
        assertThat(document.getValue().getString("severity")).isEqualTo("INFO");
        assertThat(document.getValue().getString("actorId")).isEqualTo("actor-1");
        assertThat(document.getValue().get("metadata", Document.class).getString("orderId")).isEqualTo("o-1");
    }

    @Test
    void mongoFailure_isNotPropagated() {
        MongoClient client = mock(MongoClient.class);
        when(client.getDatabase(any())).thenThrow(new IllegalStateException("MongoDB caído"));

        assertThatCode(() -> new MongoAuditLogRepositoryPort(client, "nexusmarket_audit").append(
                OperationType.ORDER_PAYMENT, AuditSeverity.INFO, LocalDateTime.now(), "actor-1", "Pago", Map.of()))
                .doesNotThrowAnyException();
    }
}
