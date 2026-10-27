package nexus.market.adapters.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import nexus.market.adapters.out.security.BcryptPasswordServicePort;

/**
 * El BCrypt usado NO trunca en silencio: rechaza lo que supere 72 bytes (UTF-8) con
 * IllegalArgumentException. Por eso la contraseña se valida antes, en bytes, y no a mitad de un
 * registro ni con un conteo de caracteres.
 */
class BcryptTruncationTest {

    private final BcryptPasswordServicePort bcrypt = new BcryptPasswordServicePort();

    @Test
    void bcrypt_rejects_instead_of_truncating_beyondExactly72Bytes() {
        String exactly72 = "x".repeat(72);
        assertThat(bcrypt.verify(exactly72, bcrypt.hash(exactly72))).isTrue();
        assertThatThrownBy(() -> bcrypt.hash(exactly72 + "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limitIsMeasuredInBytes_notCharacters() {
        String thirtySevenEnyes = "ñ".repeat(37); // 37 caracteres = 74 bytes
        assertThat(thirtySevenEnyes.length()).isLessThan(72);
        assertThatThrownBy(() -> bcrypt.hash(thirtySevenEnyes)).isInstanceOf(IllegalArgumentException.class);
    }
}
