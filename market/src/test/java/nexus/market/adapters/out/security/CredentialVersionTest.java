package nexus.market.adapters.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.UserId;

/** Huella de credencial (claim pwv): HMAC-SHA256 del hash con el secreto JWT, truncada. */
class CredentialVersionTest {

    private static final String SECRET = "unit-test-secret-0123456789-abcdefghijklmnopqrstuvwxyz";
    private static final String HASH = "$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234";

    private final JwtJwtServicePort jwt = new JwtJwtServicePort(SECRET, "nexusmarket", 60_000L);

    @Test
    void isDeterministic_urlSafe_andDoesNotRevealTheHash() {
        String version = jwt.credentialVersion(HASH);
        assertThat(jwt.credentialVersion(HASH)).isEqualTo(version);
        assertThat(version).hasSize(22).matches("[A-Za-z0-9_-]{22}");
        assertThat(HASH).doesNotContain(version);
        assertThat(version).doesNotContain("$2a");
    }

    @Test
    void changesWithTheHash_andWithTheSecret() {
        assertThat(jwt.credentialVersion(HASH + "x")).isNotEqualTo(jwt.credentialVersion(HASH));
        JwtJwtServicePort other = new JwtJwtServicePort(SECRET + "-otro-secreto-distinto", "nexusmarket", 60_000L);
        assertThat(other.credentialVersion(HASH)).isNotEqualTo(jwt.credentialVersion(HASH));
    }

    @Test
    void theTokenCarriesTheClaim_andOnlyAValidTokenRevealsIt() {
        UserId id = UserId.generate();
        String token = jwt.issue(id, SystemRole.ADMIN, jwt.credentialVersion(HASH));
        assertThat(jwt.getCredentialVersion(token)).contains(jwt.credentialVersion(HASH));
        assertThat(jwt.isValid(token)).isTrue();
        // Sin huella, con el token manipulado, que no es un JWT o firmado con otro secreto: sin claim.
        assertThat(jwt.getCredentialVersion(jwt.issue(id, SystemRole.ADMIN))).isEqualTo(Optional.empty());
        assertThat(jwt.getCredentialVersion(token + "x")).isEqualTo(Optional.empty());
        assertThat(jwt.getCredentialVersion("no-es-un-jwt")).isEqualTo(Optional.empty());
        JwtJwtServicePort other = new JwtJwtServicePort(SECRET + "-otro-secreto-distinto", "nexusmarket", 60_000L);
        assertThat(other.getCredentialVersion(token)).isEqualTo(Optional.empty());
    }
}
