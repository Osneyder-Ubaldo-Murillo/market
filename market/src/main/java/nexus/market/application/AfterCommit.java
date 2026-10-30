package nexus.market.application;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Ejecuta una acción <b>después de confirmar</b> la transacción en curso (si se revierte, no se ejecuta); sin transacción
 * activa, la ejecuta de inmediato. Pensado para avisos externos que no deben anunciar algo que no llegó a ocurrir.
 */
final class AfterCommit {

    private AfterCommit() {
    }

    static void run(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
