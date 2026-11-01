package nexus.market.infrastructure.config;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Acota los tiempos de espera del cliente de MongoDB (auditoría).
 *
 * <p>El driver espera por defecto <b>30 s</b> a encontrar un servidor antes de fallar. Con MongoDB caído, cada operación que
 * escribe auditoría (un login fallido, un cambio de estado...) retenía su hilo ese tiempo y {@code /actuator/health}
 * tardaba lo mismo en responder 503: quien pudiera provocar auditorías (p. ej. con intentos de login) podía agotar los hilos
 * del servidor mientras la auditoría estuviera caída. El fallo de auditoría ya estaba aislado (la petición termina bien);
 * esto solo acota la espera.</p>
 *
 * <p>{@value #TIMEOUT_PROPERTY} (milisegundos, por defecto {@value #DEFAULT_TIMEOUT_MS}) fija la selección de servidor y la
 * conexión; la lectura admite {@value #READ_TIMEOUT_FACTOR} veces ese valor. Se aplica sobre cualquier opción equivalente de la
 * URI de MongoDB, así que se configura aquí y no en la URI.</p>
 */
@Configuration
public class MongoClientTimeoutConfiguration {

    public static final String TIMEOUT_PROPERTY = "nexusmarket.audit.mongo.timeout-ms";
    static final long DEFAULT_TIMEOUT_MS = 2000;
    static final long MIN_TIMEOUT_MS = 100;
    static final long MAX_TIMEOUT_MS = 60_000;
    static final int READ_TIMEOUT_FACTOR = 5;

    @Bean
    public MongoClientSettingsBuilderCustomizer auditMongoTimeouts(
            @Value("${" + TIMEOUT_PROPERTY + ":" + DEFAULT_TIMEOUT_MS + "}") long timeoutMs) {
        if (timeoutMs < MIN_TIMEOUT_MS || timeoutMs > MAX_TIMEOUT_MS) {
            throw new IllegalArgumentException(TIMEOUT_PROPERTY + " debe estar entre " + MIN_TIMEOUT_MS + " y "
                    + MAX_TIMEOUT_MS + " ms.");
        }
        return builder -> builder
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(timeoutMs, TimeUnit.MILLISECONDS))
                .applyToSocketSettings(socket -> socket
                        .connectTimeout((int) timeoutMs, TimeUnit.MILLISECONDS)
                        .readTimeout((int) (timeoutMs * READ_TIMEOUT_FACTOR), TimeUnit.MILLISECONDS));
    }
}
