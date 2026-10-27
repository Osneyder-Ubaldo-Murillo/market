package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import nexus.market.application.CredentialStorePort;
import nexus.market.domain.enums.AuditSeverity;
import nexus.market.domain.ports.out.AuditLogRepositoryPort;
import nexus.market.domain.valueobjects.OperationType;
import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.infrastructure.config.TransactionAwareAuditConfiguration.AfterCommitAuditLog;

/**
 * Rollback REAL contra la base (H2 con el mismo JpaTransactionManager de la aplicación): un fallo
 * inducido en un paso posterior revierte todas las escrituras de la operación. Se comprueba el
 * estado final de las tablas, no solo el código HTTP.
 */
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:nexusmarket_rollback;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
class TransactionalRollbackIntegrationTest extends RestTestSupport {

    @Autowired
    private EntityManagerFactory emf;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockitoSpyBean
    private CredentialStorePort credentials;

    @AfterEach
    void resetSpy() {
        reset(credentials);
    }

    private long count(String table) {
        try (EntityManager em = emf.createEntityManager()) {
            return ((Number) em.createNativeQuery("select count(*) from " + table).getSingleResult()).longValue();
        }
    }

    @Test
    void buyerRegistration_failingAtCredentialsStep_rollsBackUserAndBuyer() throws Exception {
        doThrow(new IllegalStateException("fallo inducido al guardar credenciales"))
                .when(credentials).save(any(), anyString());
        long users = count("users");
        long buyers = count("buyers");
        long creds = count("user_credentials");

        String response = send(HttpMethod.POST, "/api/auth/register/buyer", null,
                body("fullName", "Comprador Rollback", "email", "rb-" + uniq() + "@mail.test",
                        "documentId", "CC-" + uniq(), "password", PASSWORD, "address", addressJson()), 500);

        assertThat(response).doesNotContain("inducido").doesNotContain("IllegalState"); // sin detalles internos
        assertThat(count("users")).as("usuario revertido").isEqualTo(users);
        assertThat(count("buyers")).as("perfil de comprador revertido").isEqualTo(buyers);
        assertThat(count("user_credentials")).isEqualTo(creds);
    }

    @Test
    void sellerRegistration_failingAtCredentialsStep_rollsBackUserAndSeller() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        doThrow(new IllegalStateException("fallo inducido")).when(credentials).save(any(), anyString());
        long users = count("users");
        long sellers = count("sellers");

        send(HttpMethod.POST, "/api/auth/register/seller", admin,
                body("fullName", "Vendedor Rollback", "email", "rs-" + uniq() + "@mail.test",
                        "documentId", "NIT-" + uniq(), "password", PASSWORD,
                        "businessName", "Comercial Rollback", "taxId", "87654321"), 500);

        assertThat(count("users")).isEqualTo(users);
        assertThat(count("sellers")).isEqualTo(sellers);
    }

    @Test
    void afterTheInducedFailureIsGone_registrationCommitsEverything() throws Exception {
        long creds = count("user_credentials");
        registerBuyer();
        assertThat(count("user_credentials")).isEqualTo(creds + 1);
    }

    @Test
    void orderCreation_failingOnLaterStockReservation_leavesNoOrderRow() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog catalog = seedSellerCatalog(admin, new BigDecimal("1500.00")); // 10 unidades
        Account buyer = registerBuyer();
        send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                body("productId", catalog.productId(), "quantity", 11), 200);
        long orders = count("orders");
        long items = count("order_items");

        int status = status(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders", buyer.token(),
                body("shippingAddress", addressJson()));

        assertThat(status).as("sin stock suficiente la reserva falla").isGreaterThanOrEqualTo(400);
        assertThat(count("orders")).as("el pedido (primer paso) se revierte").isEqualTo(orders);
        assertThat(count("order_items")).isEqualTo(items);
    }

    // ------------------------------------------------------------------
    // Auditoría: no se registra lo que se revierte
    // ------------------------------------------------------------------

    @Test
    void audit_isDeferredUntilCommit_andDroppedOnRollback() {
        AuditLogRepositoryPort delegate = mock(AuditLogRepositoryPort.class);
        AfterCommitAuditLog audit = new AfterCommitAuditLog(delegate);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            audit.append(OperationType.USER_REGISTRATION, AuditSeverity.INFO, LocalDateTime.now(), "a", "d", Map.of());
            verify(delegate, never()).append(any(), any(), any(), any(), any(), any()); // aún no
        });
        verify(delegate).append(any(), any(), any(), any(), any(), any()); // tras el commit

        reset(delegate);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            audit.append(OperationType.USER_REGISTRATION, AuditSeverity.INFO, LocalDateTime.now(), "a", "d", Map.of());
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        verify(delegate, never()).append(any(), any(), any(), any(), any(), any());

        audit.append(OperationType.USER_REGISTRATION, AuditSeverity.INFO, LocalDateTime.now(), "a", "d", Map.of());
        verify(delegate).append(any(), any(), any(), any(), any(), any()); // sin transacción: inmediato
    }
}
