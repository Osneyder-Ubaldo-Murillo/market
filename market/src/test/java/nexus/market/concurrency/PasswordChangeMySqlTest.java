package nexus.market.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import nexus.market.adapters.in.rest.RestTestSupport;
import nexus.market.application.CredentialStorePort;
import nexus.market.application.TooManyAttemptsException;
import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.models.User;
import nexus.market.domain.ports.in.PasswordChangeUseCases;
import nexus.market.domain.ports.out.PasswordServicePort;
import nexus.market.domain.valueobjects.DocumentId;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.FullName;
import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.UserId;

/**
 * Cambio de contraseña del administrador contra MySQL 8 REAL (InnoDB): el bloqueo {@code FOR UPDATE} de la fila de
 * credenciales serializa los cambios simultáneos y un fallo al guardar revierte. Se omite sin
 * {@code NEXUS_TEST_MYSQL_URL}. Nunca contra una base real o compartida.
 */
@EnabledIfEnvironmentVariable(named = "NEXUS_TEST_MYSQL_URL", matches = ".+")
@TestPropertySource(properties = {
        "spring.datasource.url=${NEXUS_TEST_MYSQL_URL}",
        "spring.datasource.username=${NEXUS_TEST_MYSQL_USER}",
        "spring.datasource.password=${NEXUS_TEST_MYSQL_PASSWORD}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/schema",
        "spring.datasource.hikari.maximum-pool-size=40",
        "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=10"
})
class PasswordChangeMySqlTest extends RestTestSupport {

    @MockitoSpyBean
    private CredentialStorePort credentials;
    @Autowired
    private PasswordServicePort passwords;
    @Autowired
    private PasswordChangeUseCases passwordChange;

    @AfterEach
    void restore() {
        reset(credentials);
    }

    private static String strong() {
        return UUID.randomUUID().toString().replace("-", "") + "Qz#x";
    }

    private record Admin(UserId id, String password) {
    }

    private Admin newAdmin() {
        String password = strong();
        User admin = users.save(User.create(FullName.of("Admin Concurrente"),
                Email.of("adm-" + uniq() + "@tienda.example"), DocumentId.of("ADM-" + uniq()), SystemRole.ADMIN));
        credentials.save(admin.getUserId(), passwords.hash(password));
        return new Admin(admin.getUserId(), password);
    }

    private sealed interface Result permits Ok, Failed {
    }

    private record Ok(String newPassword) implements Result {
    }

    private record Failed(Throwable error) implements Result {
    }

    private List<Result> race(Admin admin, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Result>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String next = strong();
            Callable<Result> call = () -> {
                ready.countDown();
                go.await();
                try {
                    passwordChange.changeAdminPassword(admin.id(), admin.password(), next);
                    return new Ok(next);
                } catch (Throwable error) {
                    return new Failed(error);
                }
            };
            futures.add(pool.submit(call));
        }
        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        List<Result> results = new ArrayList<>();
        for (Future<Result> future : futures) {
            results.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return results;
    }

    @Test
    void simultaneousChangesWithTheSameCurrentPassword_exactlyOneSucceeds_overManyRounds() throws Exception {
        for (int round = 1; round <= 10; round++) {
            Admin admin = newAdmin();
            List<Result> results = race(admin, 5); // 5 = intentos que admite el límite: todos llegan a la base de datos
            List<Ok> ok = results.stream().filter(r -> r instanceof Ok).map(r -> (Ok) r).toList();
            assertThat(ok).as("ronda " + round + ": exactamente un éxito").hasSize(1);
            for (Result result : results) {
                if (result instanceof Failed failed) {
                    assertThat(failed.error()).as("los demás fallan por la contraseña actual, no por la base de datos")
                            .isInstanceOf(BusinessException.class);
                    assertThat(((BusinessException) failed.error()).getCode()).isEqualTo("INVALID_CURRENT_PASSWORD");
                }
            }
            String hash = credentials.findByUserId(admin.id()).orElseThrow();
            assertThat(passwords.verify(ok.get(0).newPassword(), hash)).as("la vigente es la del ganador").isTrue();
            assertThat(passwords.verify(admin.password(), hash)).isFalse();
        }
    }

    @Test
    void aBurstLargerThanTheLimit_neverExceedsFiveVerifications_andStillOneWinnerAtMost() throws Exception {
        Admin admin = newAdmin();
        List<Result> results = race(admin, 12);
        long ok = results.stream().filter(r -> r instanceof Ok).count();
        long tooMany = results.stream().filter(r -> r instanceof Failed f && f.error() instanceof TooManyAttemptsException).count();
        long wrong = results.stream().filter(r -> r instanceof Failed f && f.error() instanceof BusinessException b
                && "INVALID_CURRENT_PASSWORD".equals(b.getCode())).count();
        assertThat(ok).isEqualTo(1);
        assertThat(tooMany).as("las que exceden el límite se rechazan antes de tocar la base de datos").isEqualTo(7);
        assertThat(ok + wrong + tooMany).isEqualTo(12);
    }

    @Test
    void aFailureWhileSavingTheHash_rollsBackInInnoDb_andTheOldPasswordRemainsValid() {
        Admin admin = newAdmin();
        String before = credentials.findByUserId(admin.id()).orElseThrow();
        doThrow(new IllegalStateException("fallo inducido")).when(credentials).replaceHash(any(), any());

        assertThatThrownBy(() -> passwordChange.changeAdminPassword(admin.id(), admin.password(), strong()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(credentials.findByUserId(admin.id()).orElseThrow()).isEqualTo(before);
        assertThat(passwords.verify(admin.password(), before)).isTrue();
        reset(credentials);
        passwordChange.changeAdminPassword(admin.id(), admin.password(), strong()); // y un intento posterior funciona
    }
}
