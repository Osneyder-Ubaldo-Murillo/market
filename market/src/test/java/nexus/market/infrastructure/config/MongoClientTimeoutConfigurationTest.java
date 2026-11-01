package nexus.market.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;

/**
 * Con MongoDB caído, el driver esperaba 30 s por operación (cada auditoría retenía su hilo y el health tardaba lo mismo en
 * dar 503). Los tiempos de espera quedan acotados y configurables, y prevalecen sobre los de la URI.
 */
class MongoClientTimeoutConfigurationTest {

    private static MongoClientSettings settingsFor(long timeoutMs, String uri) {
        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri));
        new MongoClientTimeoutConfiguration().auditMongoTimeouts(timeoutMs).customize(builder);
        return builder.build();
    }

    @Test
    void defaultTimeout_boundsServerSelectionAndConnection_farBelowTheDrivers30Seconds() {
        MongoClientSettings settings = settingsFor(MongoClientTimeoutConfiguration.DEFAULT_TIMEOUT_MS,
                "mongodb://localhost:27017/auditoria");
        assertThat(settings.getClusterSettings().getServerSelectionTimeout(TimeUnit.MILLISECONDS)).isEqualTo(2000);
        assertThat(settings.getSocketSettings().getConnectTimeout(TimeUnit.MILLISECONDS)).isEqualTo(2000);
        assertThat(settings.getSocketSettings().getReadTimeout(TimeUnit.MILLISECONDS)).isEqualTo(10_000);
    }

    @Test
    void theConfiguredValue_overridesWhateverTheUriSays() {
        MongoClientSettings settings = settingsFor(1500,
                "mongodb://localhost:27017/auditoria?serverSelectionTimeoutMS=30000&connectTimeoutMS=30000");
        assertThat(settings.getClusterSettings().getServerSelectionTimeout(TimeUnit.MILLISECONDS)).isEqualTo(1500);
        assertThat(settings.getSocketSettings().getConnectTimeout(TimeUnit.MILLISECONDS)).isEqualTo(1500);
    }

    @Test
    void valuesOutsideTheSaneRange_preventStartup() {
        for (long invalid : new long[] { 0, -1, 99, 60_001, Long.MAX_VALUE }) {
            assertThatThrownBy(() -> new MongoClientTimeoutConfiguration().auditMongoTimeouts(invalid))
                    .as("valor: %d", invalid)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(MongoClientTimeoutConfiguration.TIMEOUT_PROPERTY);
        }
    }
}
