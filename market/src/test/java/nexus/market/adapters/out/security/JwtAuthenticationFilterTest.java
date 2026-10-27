package nexus.market.adapters.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransientConnectionException;
import java.util.Optional;

import jakarta.persistence.PersistenceException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.CannotCreateTransactionException;

import nexus.market.application.CredentialStorePort;
import nexus.market.domain.models.User;
import nexus.market.domain.ports.out.UserRepositoryPort;
import nexus.market.domain.valueobjects.DocumentId;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.FullName;
import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.UserId;

/**
 * Distingue el token/usuario inválido (401 vía reglas de seguridad) del fallo de
 * infraestructura identificable al consultar al usuario (503 en formato de la API).
 */
class JwtAuthenticationFilterTest {

    private static final String SECRET = "unit-test-secret-0123456789-abcdefghijklmnopqrstuvwxyz";

    private final JwtJwtServicePort jwt = new JwtJwtServicePort(SECRET, "nexusmarket", 60_000L);
    private final UserRepositoryPort repository = mock(UserRepositoryPort.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<UserRepositoryPort> provider = mock(ObjectProvider.class);
    private final CredentialStorePort credentialStore = mock(CredentialStorePort.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<CredentialStorePort> credentialProvider = mock(ObjectProvider.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwt, provider, credentialProvider);

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        when(provider.getIfAvailable()).thenReturn(repository);
        when(credentialProvider.getIfAvailable()).thenReturn(credentialStore); // por defecto: sin credenciales (Optional vacío)
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static User user(SystemRole role) {
        return User.create(FullName.of("Persona Prueba"), Email.of("p@mail.test"), DocumentId.of("CC12345678"), role);
    }

    private record Outcome(MockHttpServletResponse response, MockFilterChain chain, boolean authenticated) {
    }

    private Outcome run(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/x");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Outcome(response, chain, SecurityContextHolder.getContext().getAuthentication() != null);
    }

    @Test
    void infrastructureFailures_areReportedAs503_inApiFormat_withoutInternalDetails() throws Exception {
        User user = user(SystemRole.BUYER);
        String token = jwt.issue(user.getUserId(), SystemRole.BUYER);
        RuntimeException[] failures = {
                new CannotGetJdbcConnectionException("jdbc:mysql://secret-host:3306 refused",
                        new SQLTransientConnectionException("Connection is not available, request timed out")),
                new DataAccessResourceFailureException("jdbc:mysql://secret-host:3306 refused",
                        new SQLNonTransientConnectionException("connection closed", "08003")),
                new CannotCreateTransactionException("no connection",
                        new org.hibernate.exception.JDBCConnectionException("hibernate failure",
                                new SQLRecoverableException("Communications link failure"))),
                new PersistenceException("hibernate failure", new SQLException("link down", "08S01")),
                new IllegalStateException("wrapper", new SQLRecoverableException("Communications link failure"))
        };
        for (RuntimeException failure : failures) {
            org.mockito.Mockito.doThrow(failure).when(repository).findById(any());
            Outcome outcome = run(token);
            assertThat(outcome.response().getStatus()).as(failure.toString()).isEqualTo(503);
            String body = outcome.response().getContentAsString();
            assertThat(body).contains("\"code\":\"SERVICE_UNAVAILABLE\"").contains("timestamp");
            assertThat(body).doesNotContain("secret-host").doesNotContain("jdbc").doesNotContain("hibernate");
            assertThat(outcome.chain().getRequest()).as("la cadena no debe continuar").isNull();
            assertThat(outcome.authenticated()).isFalse();
        }
    }

    @Test
    void sqlErrorsThatAreNotConnectivity_areNot503_andAreLoggedAsErrorsWithoutDetailsToTheClient() throws Exception {
        User user = user(SystemRole.BUYER);
        String token = jwt.issue(user.getUserId(), SystemRole.BUYER);
        RuntimeException[] failures = {
                new BadSqlGrammarException("select", "select * from secret_table", new SQLSyntaxErrorException("bad sql", "42000")),
                new DataIntegrityViolationException("secret-constraint",
                        new SQLIntegrityConstraintViolationException("duplicate", "23000")),
                new DataAccessResourceFailureException("recurso sin causa de conectividad"),
                new PersistenceException("hibernate failure", new SQLException("generic failure", "HY000")),
                new CannotCreateTransactionException("no connection")
        };
        for (RuntimeException failure : failures) {
            org.mockito.Mockito.doThrow(failure).when(repository).findById(any());
            Outcome outcome = run(token);
            assertThat(outcome.response().getStatus()).as(failure.toString()).isNotEqualTo(503);
            assertThat(outcome.response().getContentAsString()).doesNotContain("secret").doesNotContain("jdbc");
            assertThat(outcome.authenticated()).as("falla cerrado").isFalse();
            assertThat(outcome.chain().getRequest()).as("las reglas de seguridad responden").isNotNull();
        }
    }

    @Test
    void sqlErrorWhileReadingTheCredential_isNot503_andFailsClosed() throws Exception {
        User user = user(SystemRole.ADMIN);
        when(repository.findById(any())).thenReturn(Optional.of(user));
        org.mockito.Mockito.doThrow(new BadSqlGrammarException("select", "select 1",
                new SQLSyntaxErrorException("bad sql", "42000"))).when(credentialStore).findByUserId(any());
        Outcome outcome = run(jwt.issue(user.getUserId(), SystemRole.ADMIN, jwt.credentialVersion(HASH_V1)));
        assertThat(outcome.response().getStatus()).isNotEqualTo(503);
        assertThat(outcome.authenticated()).isFalse();
    }

    @Test
    void unexpectedNonInfrastructureErrors_areNot503() throws Exception {
        User user = user(SystemRole.BUYER);
        when(repository.findById(any())).thenThrow(new IllegalStateException("bug"));
        Outcome outcome = run(jwt.issue(user.getUserId(), SystemRole.BUYER));
        assertThat(outcome.response().getStatus()).isEqualTo(200);
        assertThat(outcome.authenticated()).isFalse();
        assertThat(outcome.chain().getRequest()).isNotNull();
    }

    @Test
    void invalidToken_unknownUser_blockedAndInactive_continueUnauthenticated() throws Exception {
        // Token inválido: ni siquiera se consulta la base de datos.
        Outcome invalid = run("no-es-un-jwt");
        assertThat(invalid.authenticated()).isFalse();
        verify(repository, never()).findById(any());

        // Usuario inexistente.
        when(repository.findById(any())).thenReturn(Optional.empty());
        assertThat(run(jwt.issue(UserId.generate(), SystemRole.ADMIN)).authenticated()).isFalse();

        // Bloqueado e inactivo.
        User blocked = user(SystemRole.BUYER);
        blocked.block();
        when(repository.findById(any())).thenReturn(Optional.of(blocked));
        assertThat(run(jwt.issue(blocked.getUserId(), SystemRole.BUYER)).authenticated()).isFalse();

        User inactive = user(SystemRole.BUYER);
        inactive.deactivate();
        when(repository.findById(any())).thenReturn(Optional.of(inactive));
        assertThat(run(jwt.issue(inactive.getUserId(), SystemRole.BUYER)).authenticated()).isFalse();
    }

    @Test
    void activeUser_isAuthenticatedWithRoleFromDatabase() throws Exception {
        User user = user(SystemRole.BUYER);
        when(repository.findById(any())).thenReturn(Optional.of(user));
        Outcome outcome = run(jwt.issue(user.getUserId(), SystemRole.ADMIN)); // claim manipulado
        assertThat(outcome.authenticated()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString).containsExactly("ROLE_BUYER");
    }

    @Test
    void withoutRepository_failsClosed() throws Exception {
        when(provider.getIfAvailable()).thenReturn(null);
        User user = user(SystemRole.BUYER);
        assertThat(run(jwt.issue(user.getUserId(), SystemRole.BUYER)).authenticated()).isFalse();
    }

    // ------------------------------------------------------------------
    // Huella de credencial (pwv): los tokens anteriores a un cambio de contraseña dejan de valer
    // ------------------------------------------------------------------

    private static final String HASH_V1 = "$2a$10$huellaDeLaContrasenaVersionUno.................";
    private static final String HASH_V2 = "$2a$10$huellaDeLaContrasenaVersionDos.................";

    @Test
    void tokenWithTheCurrentCredentialFingerprint_isAuthenticated() throws Exception {
        User user = user(SystemRole.ADMIN);
        when(repository.findById(any())).thenReturn(Optional.of(user));
        when(credentialStore.findByUserId(any())).thenReturn(Optional.of(HASH_V1));
        String token = jwt.issue(user.getUserId(), SystemRole.ADMIN, jwt.credentialVersion(HASH_V1));
        assertThat(run(token).authenticated()).isTrue();
    }

    @Test
    void tokenIssuedBeforeThePasswordChange_isRejected_andTheNewOneAccepted() throws Exception {
        User user = user(SystemRole.ADMIN);
        when(repository.findById(any())).thenReturn(Optional.of(user));
        String oldToken = jwt.issue(user.getUserId(), SystemRole.ADMIN, jwt.credentialVersion(HASH_V1));
        // El hash cambia (cambio de contraseña): el token anterior ya no corresponde a la credencial vigente.
        when(credentialStore.findByUserId(any())).thenReturn(Optional.of(HASH_V2));
        assertThat(run(oldToken).authenticated()).as("token anterior").isFalse();
        String newToken = jwt.issue(user.getUserId(), SystemRole.ADMIN, jwt.credentialVersion(HASH_V2));
        assertThat(run(newToken).authenticated()).as("token nuevo").isTrue();
    }

    @Test
    void tokenWithoutFingerprint_isRejected_whenTheUserHasCredentials() throws Exception {
        User user = user(SystemRole.ADMIN);
        when(repository.findById(any())).thenReturn(Optional.of(user));
        when(credentialStore.findByUserId(any())).thenReturn(Optional.of(HASH_V1));
        assertThat(run(jwt.issue(user.getUserId(), SystemRole.ADMIN)).authenticated()).isFalse();
    }

    @Test
    void userWithoutCredentialRow_keepsTheCurrentBehavior() throws Exception {
        User user = user(SystemRole.SUPERVISOR); // personal creado fuera del registro: sin fila de credenciales
        when(repository.findById(any())).thenReturn(Optional.of(user));
        when(credentialStore.findByUserId(any())).thenReturn(Optional.empty());
        assertThat(run(jwt.issue(user.getUserId(), SystemRole.SUPERVISOR)).authenticated()).isTrue();
    }

    @Test
    void forgedFingerprintOrAnotherUsersFingerprint_isRejected() throws Exception {
        User user = user(SystemRole.ADMIN);
        when(repository.findById(any())).thenReturn(Optional.of(user));
        when(credentialStore.findByUserId(any())).thenReturn(Optional.of(HASH_V1));
        assertThat(run(jwt.issue(user.getUserId(), SystemRole.ADMIN, "huella-inventada-1234")).authenticated()).isFalse();
        assertThat(run(jwt.issue(user.getUserId(), SystemRole.ADMIN, jwt.credentialVersion(HASH_V2))).authenticated()).isFalse();
    }

    @Test
    void credentialStoreUnavailable_failsClosed() throws Exception {
        User user = user(SystemRole.ADMIN);
        when(repository.findById(any())).thenReturn(Optional.of(user));
        when(credentialProvider.getIfAvailable()).thenReturn(null);
        assertThat(run(jwt.issue(user.getUserId(), SystemRole.ADMIN, jwt.credentialVersion(HASH_V1))).authenticated()).isFalse();
    }

    @Test
    void databaseFailureWhileReadingTheCredential_is503_notAn401() throws Exception {
        User user = user(SystemRole.ADMIN);
        when(repository.findById(any())).thenReturn(Optional.of(user));
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("pool",
                new SQLTransientConnectionException("Connection is not available"))).when(credentialStore).findByUserId(any());
        Outcome outcome = run(jwt.issue(user.getUserId(), SystemRole.ADMIN, jwt.credentialVersion(HASH_V1)));
        assertThat(outcome.response().getStatus()).isEqualTo(503);
        assertThat(outcome.authenticated()).isFalse();
    }
}
