package nexus.market.adapters.out.persistence.mysql;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;

import org.hibernate.Session;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import nexus.market.application.InitialAdminException;
import nexus.market.application.InitialAdminStorePort;
import nexus.market.domain.models.User;
import nexus.market.domain.valueobjects.SystemRole;

/**
 * Adaptador JPA del administrador inicial: SOLO inserciones SQL nativas (nunca {@code merge}), por lo que no
 * puede sobrescribir un usuario ni unas credenciales existentes.
 *
 * <p>La garantía de "a lo sumo un administrador creado por el bootstrap" no descansa en esta sentencia sola
 * (bajo READ COMMITTED no es segura): se combina con el candado {@code GET_LOCK} del servicio y con la
 * transacción {@code REPEATABLE_READ} (ver {@code docs/ADMIN_INICIAL_PROPUESTA.md}, sección 2).</p>
 */
@Component
@Profile("jpa")
class JpaInitialAdminStore extends JpaRepositorySupport implements InitialAdminStorePort {

    private static final String ADMIN_ROLE = SystemRole.ADMIN.getCode();

    private volatile Boolean mysqlFamily;

    JpaInitialAdminStore(EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    public boolean existsAnyAdmin() {
        List<Long> count = query(Long.class, "select count(u) from UserEntity u where u.role = :role",
                Map.of("role", ADMIN_ROLE));
        return !count.isEmpty() && count.get(0) > 0;
    }

    @Override
    public Outcome insertIfNoAdmin(User admin, String passwordHash) {
        EntityManager em = requireTransactionalEntityManager();

        // Lectura previa (instantánea): si ya hay administrador no se escribe nada.
        if (countAdmins(em) > 0) {
            return Outcome.ADMIN_ALREADY_EXISTS;
        }
        // Correo o documento de un usuario que NO es administrador: se falla sin modificarlo.
        Number taken = (Number) em.createNativeQuery(
                        "select count(*) from users where email = ?1 or document_id = ?2")
                .setParameter(1, admin.getEmail().value())
                .setParameter(2, admin.getDocumentId().value())
                .getSingleResult();
        if (taken.longValue() > 0) {
            throw conflict();
        }

        // Sentencia atómica y condicional: un único INSERT ... SELECT ... WHERE NOT EXISTS (role = 'ADMIN').
        int inserted;
        try {
            inserted = em.createNativeQuery(atomicInsertSql(em))
                    .setParameter(1, admin.getUserId().value())
                    .setParameter(2, admin.getCreatedAt())
                    .setParameter(3, admin.getDocumentId().value())
                    .setParameter(4, admin.getEmail().value())
                    .setParameter(5, admin.getFullName().value())
                    .setParameter(6, ADMIN_ROLE)
                    .setParameter(7, admin.getStatus().getCode())
                    .setParameter(8, admin.getUpdatedAt())
                    .setParameter(9, ADMIN_ROLE)
                    .executeUpdate();
        } catch (PersistenceException failure) {
            if (isDuplicateKey(failure)) {
                throw conflict();
            }
            throw failure;
        }
        if (inserted == 0) {
            // Otro administrador quedó confirmado entre la lectura previa y la sentencia.
            return Outcome.ADMIN_ALREADY_EXISTS;
        }
        em.createNativeQuery("insert into user_credentials (user_id, created_at, password_hash) values (?1, ?2, ?3)")
                .setParameter(1, admin.getUserId().value())
                .setParameter(2, LocalDateTime.now())
                .setParameter(3, passwordHash)
                .executeUpdate();
        em.flush();
        return Outcome.CREATED;
    }

    private static long countAdmins(EntityManager em) {
        return ((Number) em.createNativeQuery("select count(*) from users where role = ?1")
                .setParameter(1, ADMIN_ROLE).getSingleResult()).longValue();
    }

    private static InitialAdminException conflict() {
        return new InitialAdminException("NEXUSMARKET_BOOTSTRAP_ADMIN_EMAIL o NEXUSMARKET_BOOTSTRAP_ADMIN_DOCUMENT_ID "
                + "pertenecen a un usuario que no es administrador; no se crea el administrador inicial ni se modifica ese usuario.");
    }

    private static boolean isDuplicateKey(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException
                    || t instanceof java.sql.SQLIntegrityConstraintViolationException) {
                return true;
            }
            if (t instanceof java.sql.SQLException sql && "23000".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /** Los tipos de CAST difieren entre MySQL/MariaDB (CHAR, DATETIME) y H2 (VARCHAR, TIMESTAMP). */
    private String atomicInsertSql(EntityManager em) {
        boolean mysql = isMysqlFamily(em);
        String text36 = mysql ? "CHAR(36)" : "VARCHAR(36)";
        String text40 = mysql ? "CHAR(40)" : "VARCHAR(40)";
        String text160 = mysql ? "CHAR(160)" : "VARCHAR(160)";
        String text120 = mysql ? "CHAR(120)" : "VARCHAR(120)";
        String text30 = mysql ? "CHAR(30)" : "VARCHAR(30)";
        String timestamp = mysql ? "DATETIME(6)" : "TIMESTAMP(6)";
        return "insert into users (id, created_at, document_id, email, full_name, role, status, updated_at, "
                + "email_verified_at) "
                + "select cast(?1 as " + text36 + "), cast(?2 as " + timestamp + "), cast(?3 as " + text40 + "), "
                + "cast(?4 as " + text160 + "), cast(?5 as " + text120 + "), cast(?6 as " + text30 + "), "
                + "cast(?7 as " + text30 + "), cast(?8 as " + timestamp + "), cast(?2 as " + timestamp + ") "
                + "from (select 1 as one) src "
                + "where not exists (select 1 from users where role = ?9)";
    }

    private boolean isMysqlFamily(EntityManager em) {
        Boolean cached = mysqlFamily;
        if (cached == null) {
            String product = em.unwrap(Session.class).doReturningWork(
                    connection -> connection.getMetaData().getDatabaseProductName());
            cached = product != null && (product.toLowerCase().contains("mysql") || product.toLowerCase().contains("mariadb"));
            mysqlFamily = cached;
        }
        return cached;
    }
}
