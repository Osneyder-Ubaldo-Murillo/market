package nexus.market.application;

import java.util.function.Supplier;

/**
 * Exclusión mutua entre instancias para el aprovisionamiento de administradores.
 *
 * <p>La implementación MySQL usa {@code GET_LOCK} en una conexión dedicada, tomado ANTES de abrir la
 * transacción y liberado siempre al terminar (también ante errores). Todo procedimiento que cree
 * administradores debe usar el mismo nombre de candado.</p>
 */
public interface AdminProvisioningLockPort {

    /** Nombre del candado (compartido por el bootstrap y cualquier otro procedimiento autorizado). */
    String LOCK_NAME = "nexusmarket:admin-bootstrap";

    /**
     * Ejecuta {@code action} con el candado tomado y lo libera siempre.
     *
     * @throws InitialAdminException si no se obtiene el candado en el tiempo de espera (no se ejecuta la acción)
     */
    <T> T withLock(Supplier<T> action);
}
