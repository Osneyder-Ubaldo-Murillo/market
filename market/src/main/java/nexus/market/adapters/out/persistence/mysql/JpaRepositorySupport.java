package nexus.market.adapters.out.persistence.mysql;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;

import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Soporte común de los adaptadores de persistencia JPA (MySQL).
 *
 * <p><b>Sin transacción de Spring en curso</b>, cada implementación de puerto abre un
 * {@link EntityManager} por operación (patrón <em>session-per-request</em>) y
 * encapsula una transacción local para cada escritura.</p>
 *
 * <p><b>Con una transacción de Spring en curso</b> (un caso de uso anotado con
 * {@code @Transactional}, gestionada por el {@code JpaTransactionManager} del mismo
 * {@link EntityManagerFactory}), todas las lecturas y escrituras del caso de uso comparten el
 * {@link EntityManager} de esa transacción: se confirman o se revierten juntas, y las
 * lecturas ven las escrituras previas aún no confirmadas. Cada escritura hace
 * {@code flush} para que una violación de restricciones falle en el paso que la causa.</p>
 *
 * <p>Las entidades JPA del adaptador son los únicos objetos que se persisten; el mapeo
 * dominio {@literal <->} persistencia vive en el adaptador.</p>
 *
 * <p>Clase base abstracta: {@link JpaUserRepositoryPort} y compañía heredan de
 * aquí y aportan los métodos de consulta específicos del agregado.</p>
 */
abstract class JpaRepositorySupport {

    protected final EntityManagerFactory entityManagerFactory;

    protected JpaRepositorySupport(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = Objects.requireNonNull(entityManagerFactory,
                "entityManagerFactory es obligatorio");
    }

    /** EntityManager de la transacción de Spring en curso, o {@code null} si no hay ninguna. */
    private EntityManager transactionalEntityManager() {
        // Solo con una transacción REAL en curso: un EntityManager enlazado por open-in-view sin
        // transacción no sirve para escribir (TransactionRequiredException).
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return null;
        }
        return EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
    }

    /** EntityManager de la transacción en curso; falla si no hay ninguna (operaciones que lo exigen). */
    protected EntityManager requireTransactionalEntityManager() {
        EntityManager shared = transactionalEntityManager();
        if (shared == null) {
            throw new IllegalStateException("Esta operación debe ejecutarse dentro de una transacción.");
        }
        return shared;
    }

    /** Persiste/actualiza una entidad dentro de una transacción (merge). */
    protected <T> T persist(T entity) {
        EntityManager shared = transactionalEntityManager();
        if (shared != null) {
            // Transacción del caso de uso: el commit/rollback lo decide quien la abrió.
            T merged = shared.merge(entity);
            shared.flush();
            return merged;
        }
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            em.getTransaction().begin();
            T merged = em.merge(entity);
            em.getTransaction().commit();
            return merged;
        } catch (RuntimeException ex) {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
            throw ex;
        } finally {
            em.close();
        }
    }

    /** Busca una entidad por su clave primaria (String, UUID del dominio). */
    protected <T> Optional<T> find(Class<T> type, String id) {
        EntityManager shared = transactionalEntityManager();
        if (shared != null) {
            return Optional.ofNullable(shared.find(type, id));
        }
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            return Optional.ofNullable(em.find(type, id));
        } finally {
            em.close();
        }
    }

    /**
     * Busca una entidad por clave primaria <b>bloqueando su fila</b> ({@code SELECT ... FOR UPDATE},
     * {@code PESSIMISTIC_WRITE}) hasta el fin de la transacción: otra transacción que intente bloquear o
     * modificar la misma fila espera y después lee el estado ya confirmado. Solo tiene sentido dentro de
     * una transacción de Spring (el bloqueo vive lo que ella); sin transacción cae a la lectura simple.
     *
     * <p>Debe ser la <b>primera</b> lectura de esa fila en la transacción: una lectura previa sin bloqueo
     * dejaría la entidad en caché con un estado posiblemente obsoleto.</p>
     */
    protected <T> Optional<T> findForUpdate(Class<T> type, String id) {
        EntityManager shared = transactionalEntityManager();
        if (shared == null) {
            return find(type, id);
        }
        return Optional.ofNullable(shared.find(type, id, LockModeType.PESSIMISTIC_WRITE));
    }

    /** Ejecuta una JPQL tipada con parámetros con nombre ({@code :nombre}). */
    protected <T> List<T> query(Class<T> type, String jpql, Map<String, Object> params) {
        EntityManager shared = transactionalEntityManager();
        if (shared != null) {
            return run(shared, type, jpql, params);
        }
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            return run(em, type, jpql, params);
        } finally {
            em.close();
        }
    }

    private static <T> List<T> run(EntityManager em, Class<T> type, String jpql, Map<String, Object> params) {
        TypedQuery<T> query = em.createQuery(jpql, type);
        if (params != null) {
            params.forEach(query::setParameter);
        }
        return query.getResultList();
    }

    /** Ejecuta una JPQL y devuelve el primer resultado, si existe. */
    protected <T> Optional<T> querySingle(Class<T> type, String jpql, Map<String, Object> params) {
        List<T> result = query(type, jpql, params);
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }
}
