package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import nexus.market.application.CredentialStorePort;
import nexus.market.application.InitialAdminService;
import nexus.market.application.PasswordAttemptLimiter;
import nexus.market.domain.models.User;
import nexus.market.domain.ports.out.PasswordServicePort;
import nexus.market.domain.valueobjects.DocumentId;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.FullName;
import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.UserId;

/**
 * Rotación de la contraseña del administrador (POST /api/auth/password) con la pila completa: seguridad real, filtro
 * JWT con huella de credencial, transacción con bloqueo de fila, límite de intentos y auditoría. Se comprueba el estado
 * final de la base de datos, no solo el código HTTP.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:nexusmarket_pwchange;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
class PasswordChangeIntegrationTest extends RestTestSupport {

    private static final String PATH = "/api/auth/password";

    @MockitoSpyBean
    private CredentialStorePort credentials;
    @Autowired
    private PasswordServicePort passwords;
    @Autowired
    private EntityManagerFactory emf;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private PasswordAttemptLimiter limiter;

    @AfterEach
    void restore() {
        reset(credentials);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private record Admin(String id, String email, String password, String token) {
    }

    private static String strong() {
        return UUID.randomUUID().toString().replace("-", "") + "Qz#x";
    }

    private Admin newAdmin() throws Exception {
        String password = strong();
        User admin = users.save(User.create(FullName.of("Admin Prueba"), Email.of("adm-" + uniq() + "@tienda.example"),
                DocumentId.of("ADM-" + uniq()), SystemRole.ADMIN));
        credentials.save(admin.getUserId(), passwords.hash(password));
        String logged = send(HttpMethod.POST, "/api/auth/login", null,
                body("email", admin.getEmail().value(), "password", password), 200);
        return new Admin(admin.getUserId().value(), admin.getEmail().value(), password, JsonPath.read(logged, "$.token"));
    }

    private MvcResult change(String token, String current, String next) throws Exception {
        return perform(HttpMethod.POST, PATH, token, body("currentPassword", current, "newPassword", next));
    }

    private static String code(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.code");
    }

    private String hash(String userId) {
        return credentials.findByUserId(UserId.of(userId)).orElseThrow();
    }

    private int loginStatus(Admin admin, String password) throws Exception {
        return status(HttpMethod.POST, "/api/auth/login", null, body("email", admin.email(), "password", password));
    }

    private String usersRow(String userId) {
        try (EntityManager em = emf.createEntityManager()) {
            return java.util.Arrays.toString((Object[]) em.createNativeQuery(
                    "select id, full_name, email, document_id, role, status, created_at, updated_at from users where id = ?1")
                    .setParameter(1, userId).getSingleResult());
        }
    }

    private String credentialCreatedAt(String userId) {
        try (EntityManager em = emf.createEntityManager()) {
            return String.valueOf(em.createNativeQuery("select created_at from user_credentials where user_id = ?1")
                    .setParameter(1, userId).getSingleResult());
        }
    }

    // ------------------------------------------------------------------
    // Contraseña actual y política de la nueva
    // ------------------------------------------------------------------

    @Test
    void wrongCurrentPassword_is400InvalidCurrentPassword_andNothingChanges() throws Exception {
        Admin admin = newAdmin();
        String before = hash(admin.id());

        MvcResult result = change(admin.token(), "no-es-la-contraseña-actual-9Z", strong());

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(code(result)).isEqualTo("INVALID_CURRENT_PASSWORD");
        assertThat(hash(admin.id())).isEqualTo(before);
        assertThat(loginStatus(admin, admin.password())).isEqualTo(200);
    }

    @Test
    void newPasswordPolicy_isEnforced_andNothingChanges() throws Exception {
        Admin admin = newAdmin();
        String before = hash(admin.id());
        String local = admin.email().substring(0, admin.email().indexOf('@'));

        // Corta (validación del cuerpo), de más de 72 bytes, débil, con el correo y con "ñ" que excede los bytes.
        assertThat(code(change(admin.token(), admin.password(), "Corta-1Aa!"))).isEqualTo("VALIDATION_ERROR");
        assertThat(code(change(admin.token(), admin.password(), "x".repeat(60) + "Ab1#" + "y".repeat(20))))
                .isEqualTo("VALIDATION_ERROR");
        assertThat(code(change(admin.token(), admin.password(), "ñ".repeat(37)))).isEqualTo("VALIDATION_ERROR");
        assertThat(code(change(admin.token(), admin.password(), "Password-Reset-2026x"))).isEqualTo("PASSWORD_POLICY_VIOLATION");
        assertThat(code(change(admin.token(), admin.password(), "Zz9!" + local + "-Kq7"))).isEqualTo("PASSWORD_POLICY_VIOLATION");
        assertThat(hash(admin.id())).isEqualTo(before);
        assertThat(loginStatus(admin, admin.password())).isEqualTo(200);
    }

    @Test
    void aNewPasswordIdenticalToTheCurrentOne_isRejected_withoutChangingTheHash() throws Exception {
        Admin admin = newAdmin();
        String before = hash(admin.id());
        MvcResult result = change(admin.token(), admin.password(), admin.password());
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(code(result)).isEqualTo("NEW_PASSWORD_SAME_AS_CURRENT");
        assertThat(hash(admin.id())).isEqualTo(before);
    }

    // ------------------------------------------------------------------
    // Autorización
    // ------------------------------------------------------------------

    @Test
    void onlyAnAuthenticatedAdminMayUseIt_everyoneElseGets401Or403() throws Exception {
        Admin admin = newAdmin();
        String before = hash(admin.id());
        String next = strong();

        assertThat(status(HttpMethod.POST, PATH, null, body("currentPassword", admin.password(), "newPassword", next))).isEqualTo(401);
        assertThat(change("no-es-un-jwt", admin.password(), next).getResponse().getStatus()).isEqualTo(401);
        assertThat(change(registerBuyer().token(), admin.password(), next).getResponse().getStatus()).isEqualTo(403);
        assertThat(change(registerApprovedSeller(admin.token()).token(), admin.password(), next).getResponse().getStatus()).isEqualTo(403);
        assertThat(change(staffToken(SystemRole.SUPERVISOR), admin.password(), next).getResponse().getStatus()).isEqualTo(403);
        assertThat(change(staffToken(SystemRole.LOGISTICS_OPERATOR), admin.password(), next).getResponse().getStatus()).isEqualTo(403);

        assertThat(hash(admin.id())).as("ninguna de esas peticiones tocó la credencial").isEqualTo(before);
        assertThat(loginStatus(admin, admin.password())).isEqualTo(200);
    }

    @Test
    void aBlockedAdministrator_cannotUseItWithAnOldToken() throws Exception {
        Admin admin = newAdmin();
        User user = users.findById(UserId.of(admin.id())).orElseThrow();
        user.block();
        users.save(user);
        assertThat(change(admin.token(), admin.password(), strong()).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void aForeignUserIdInTheBody_isIgnored_onlyTheAuthenticatedAdministratorChanges() throws Exception {
        Admin attacker = newAdmin();
        Admin victim = newAdmin();
        String victimBefore = hash(victim.id());
        String attackerBefore = hash(attacker.id());
        String next = strong();

        MvcResult result = perform(HttpMethod.POST, PATH, attacker.token(),
                body("userId", victim.id(), "targetUserId", victim.id(), "currentPassword", attacker.password(),
                        "newPassword", next));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(hash(victim.id())).as("la credencial ajena no cambió").isEqualTo(victimBefore);
        assertThat(hash(attacker.id())).isNotEqualTo(attackerBefore);
        assertThat(loginStatus(victim, victim.password())).isEqualTo(200);
        assertThat(loginStatus(attacker, next)).isEqualTo(200);
        // Con la contraseña de la víctima como "actual" tampoco se puede tocar a la víctima: sigue siendo el propio usuario.
        String attackerNewToken = JsonPath.read(result.getResponse().getContentAsString(), "$.token");
        assertThat(code(perform(HttpMethod.POST, PATH, attackerNewToken,
                body("userId", victim.id(), "currentPassword", victim.password(), "newPassword", strong()))))
                .isEqualTo("INVALID_CURRENT_PASSWORD");
        assertThat(hash(victim.id())).isEqualTo(victimBefore);
    }

    // ------------------------------------------------------------------
    // Éxito, huella pwv y sesiones
    // ------------------------------------------------------------------

    @Test
    void successfulChange_rotatesTheHash_invalidatesOldTokens_andIssuesAWorkingNewOne() throws Exception {
        Admin admin = newAdmin();
        String hashBefore = hash(admin.id());
        String rowBefore = usersRow(admin.id());
        String createdAtBefore = credentialCreatedAt(admin.id());
        String next = strong();
        assertThat(status(HttpMethod.GET, "/api/users/" + admin.id(), admin.token(), null)).isEqualTo(200);

        MvcResult result = change(admin.token(), admin.password(), next);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String newToken = JsonPath.read(result.getResponse().getContentAsString(), "$.token");
        assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.role")).isEqualTo("ADMIN");
        // Estado final de la base de datos.
        String hashAfter = hash(admin.id());
        assertThat(hashAfter).isNotEqualTo(hashBefore).isNotEqualTo(next).doesNotContain(next).startsWith("$2");
        assertThat(passwords.verify(next, hashAfter)).isTrue();
        assertThat(usersRow(admin.id())).as("la fila de users no cambió").isEqualTo(rowBefore);
        assertThat(credentialCreatedAt(admin.id())).as("created_at de la credencial intacto").isEqualTo(createdAtBefore);
        // Sesiones.
        assertThat(loginStatus(admin, next)).isEqualTo(200);
        assertThat(loginStatus(admin, admin.password())).isEqualTo(401);
        assertThat(status(HttpMethod.GET, "/api/users/" + admin.id(), admin.token(), null)).as("token anterior").isEqualTo(401);
        assertThat(status(HttpMethod.GET, "/api/users/" + admin.id(), newToken, null)).as("token nuevo").isEqualTo(200);
        assertThat(newToken).isNotEqualTo(admin.token());
    }

    @Test
    void aSecondChangeWithTheSameTokenAfterTheFirst_isRejected_becauseTheTokenWasInvalidated() throws Exception {
        Admin admin = newAdmin();
        assertThat(change(admin.token(), admin.password(), strong()).getResponse().getStatus()).isEqualTo(200);
        assertThat(change(admin.token(), admin.password(), strong()).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void usersWithoutCredentials_keepWorking_andBootstrapIsDisabled() throws Exception {
        String supervisor = staffToken(SystemRole.SUPERVISOR); // sin fila de credenciales
        assertThat(status(HttpMethod.GET, "/api/products", supervisor, null)).isEqualTo(200);
        assertThat(context.getBeansOfType(InitialAdminService.class)).as("el bootstrap sigue deshabilitado").isEmpty();
    }

    // ------------------------------------------------------------------
    // Límite de intentos
    // ------------------------------------------------------------------

    @Test
    void fiveFailedAttempts_thenTheSixthIs429_evenWithTheCorrectPassword_andOthersAreUnaffected() throws Exception {
        Admin admin = newAdmin();
        Admin other = newAdmin();
        String before = hash(admin.id());
        for (int attempt = 1; attempt <= PasswordAttemptLimiter.MAX_ATTEMPTS; attempt++) {
            assertThat(code(change(admin.token(), "incorrecta-" + attempt + "-Zz9", strong()))).isEqualTo("INVALID_CURRENT_PASSWORD");
        }

        MvcResult blocked = change(admin.token(), admin.password(), strong());

        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        assertThat(code(blocked)).isEqualTo("TOO_MANY_ATTEMPTS");
        assertThat(Long.parseLong(blocked.getResponse().getHeader("Retry-After"))).isBetween(1L, 900L);
        assertThat(hash(admin.id())).as("durante el bloqueo no se cambia nada").isEqualTo(before);
        assertThat(change(other.token(), other.password(), strong()).getResponse().getStatus()).as("otro administrador").isEqualTo(200);
    }

    @Test
    void aSuccessfulChange_resetsTheCounter() throws Exception {
        Admin admin = newAdmin();
        for (int attempt = 0; attempt < PasswordAttemptLimiter.MAX_ATTEMPTS - 1; attempt++) {
            change(admin.token(), "incorrecta-" + attempt + "-Zz9", strong());
        }
        String next = strong();
        MvcResult ok = change(admin.token(), admin.password(), next);
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        String token = JsonPath.read(ok.getResponse().getContentAsString(), "$.token");
        for (int attempt = 0; attempt < PasswordAttemptLimiter.MAX_ATTEMPTS; attempt++) { // otra ventana completa de intentos
            assertThat(code(change(token, "incorrecta-" + attempt + "-Zz9", strong()))).isEqualTo("INVALID_CURRENT_PASSWORD");
        }
    }

    @Test
    void aBurstOfConcurrentWrongAttempts_cannotExceedTheLimit() throws Exception {
        Admin admin = newAdmin();
        List<Integer> statuses = runConcurrently(12, () -> change(admin.token(), "incorrecta-" + uniq() + "-Zz9", strong())
                .getResponse().getStatus());
        assertThat(statuses.stream().filter(s -> s == 400).count()).as("solo 5 intentos llegan a verificarse")
                .isEqualTo(PasswordAttemptLimiter.MAX_ATTEMPTS);
        assertThat(statuses.stream().filter(s -> s == 429).count()).isEqualTo(12 - PasswordAttemptLimiter.MAX_ATTEMPTS);
    }

    // ------------------------------------------------------------------
    // Concurrencia, rollback y disponibilidad
    // ------------------------------------------------------------------

    @Test
    void twoSimultaneousChangesWithTheSameCurrentPassword_onlyOneSucceeds() throws Exception {
        Admin admin = newAdmin();
        String first = strong();
        String second = strong();
        List<Integer> statuses = runConcurrently(2, null, List.of(
                () -> change(admin.token(), admin.password(), first).getResponse().getStatus(),
                () -> change(admin.token(), admin.password(), second).getResponse().getStatus()));

        assertThat(statuses.stream().filter(s -> s == 200).count()).as("exactamente un éxito: " + statuses).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 200).count() + statuses.stream().filter(s -> s == 400 || s == 401).count())
                .isEqualTo(2);
        boolean firstWon = loginStatus(admin, first) == 200;
        boolean secondWon = loginStatus(admin, second) == 200;
        assertThat(firstWon ^ secondWon).as("la contraseña vigente es la del ganador, y solo una").isTrue();
        assertThat(loginStatus(admin, admin.password())).isEqualTo(401);
    }

    @Test
    void aPersistenceFailureWhileSavingTheHash_rollsBack_andTheOldPasswordRemainsValid() throws Exception {
        Admin admin = newAdmin();
        String before = hash(admin.id());
        doThrow(new IllegalStateException("fallo inducido al guardar")).when(credentials).replaceHash(any(), any());

        MvcResult result = change(admin.token(), admin.password(), strong());

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("inducido");
        assertThat(hash(admin.id())).isEqualTo(before);
        assertThat(loginStatus(admin, admin.password())).isEqualTo(200);
        assertThat(status(HttpMethod.GET, "/api/users/" + admin.id(), admin.token(), null)).as("el token sigue valiendo").isEqualTo(200);
    }

    @Test
    void whenTheDatabaseIsUnavailable_itIs503_andAfterItRecoversTheSameRequestWorks() throws Exception {
        Admin admin = newAdmin();
        doThrow(new DataAccessResourceFailureException("pool",
                new java.sql.SQLTransientConnectionException("HikariPool-1 - Connection is not available")))
                .when(credentials).findByUserIdForUpdate(any());

        MvcResult down = change(admin.token(), admin.password(), strong());
        assertThat(down.getResponse().getStatus()).isEqualTo(503);
        assertThat(code(down)).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(down.getResponse().getContentAsString()).doesNotContain("Hikari").doesNotContain("jdbc");

        reset(credentials); // la base de datos "vuelve", sin reiniciar la aplicación
        assertThat(change(admin.token(), admin.password(), strong()).getResponse().getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------
    // Auditoría y ausencia de secretos
    // ------------------------------------------------------------------

    @Test
    void auditEventsAreRecorded_withoutPasswordsHashesOrTokens(CapturedOutput output) throws Exception {
        Admin admin = newAdmin();
        String wrong = "incorrecta-" + uniq() + "-Zz9";
        String next = strong();
        String hashBefore = hash(admin.id());

        change(admin.token(), wrong, strong());                      // fallo auditado
        MvcResult ok = change(admin.token(), admin.password(), next); // éxito auditado
        String newToken = JsonPath.read(ok.getResponse().getContentAsString(), "$.token");

        String log = output.getAll();
        assertThat(log).contains("PASSWORD_CHANGE_FAILED").contains("PASSWORD_CHANGE").contains(admin.id());
        for (String secret : List.of(admin.password(), next, wrong, hashBefore, hash(admin.id()), admin.token(), newToken)) {
            assertThat(log).as("el log no contiene secretos").doesNotContain(secret);
        }
        // Tampoco en ninguna respuesta de error.
        MvcResult rejected = change(admin.token(), wrong, next);
        assertThat(rejected.getResponse().getContentAsString()).doesNotContain(wrong).doesNotContain(next)
                .doesNotContain(admin.password());
    }

    // ------------------------------------------------------------------
    // Utilidades de concurrencia
    // ------------------------------------------------------------------

    private List<Integer> runConcurrently(int threads, Callable<Integer> task) throws Exception {
        return runConcurrently(threads, task, null);
    }

    private List<Integer> runConcurrently(int threads, Callable<Integer> same, List<Callable<Integer>> tasks) throws Exception {
        int count = tasks != null ? tasks.size() : threads;
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            Callable<Integer> task = tasks != null ? tasks.get(i) : same;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }
        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        List<Integer> results = new java.util.ArrayList<>();
        for (Future<Integer> future : futures) {
            results.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return results;
    }
}
