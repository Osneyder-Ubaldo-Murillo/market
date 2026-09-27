package nexus.market.adapters.out.persistence.mysql;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;

/**
 * Soporte común de los adaptadores de persistencia JPA (MySQL).
 *
 * <p>Cada implementación de puerto abre un {@link EntityManager} por operación
 * (patrón <em>session-per-request</em>) y encapsula la transacción para las
 * escrituras. Las entidades JPA del adaptador son los únicos objetos que se
 * persisten; el mapeo dominio {@literal <->} persistencia vive en el adaptador.</p>
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

    /** Persiste/actualiza una entidad dentro de una transacción (merge). */
    protected <T> T persist(T entity) {
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
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            return Optional.ofNullable(em.find(type, id));
        } finally {
            em.close();
        }
    }

    /** Ejecuta una JPQL tipada con parámetros con nombre ({@code :nombre}). */
    protected <T> List<T> query(Class<T> type, String jpql, Map<String, Object> params) {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            TypedQuery<T> query = em.createQuery(jpql, type);
            if (params != null) {
                params.forEach(query::setParameter);
            }
            return query.getResultList();
        } finally {
            em.close();
        }
    }

    /** Ejecuta una JPQL y devuelve el primer resultado, si existe. */
    protected <T> Optional<T> querySingle(Class<T> type, String jpql, Map<String, Object> params) {
        List<T> result = query(type, jpql, params);
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }
}