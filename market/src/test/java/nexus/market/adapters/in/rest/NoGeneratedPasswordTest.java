package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * Spring Boot, si no hay ningún {@code UserDetailsService}, crea un usuario en memoria con una contraseña aleatoria y la
 * escribe en el log de arranque ("Using generated security password"). La aplicación autentica solo con JWT, así que no debe
 * existir ese usuario ni ese secreto en los logs de producción.
 */
class NoGeneratedPasswordTest extends RestTestSupport {

    @Autowired
    private UserDetailsService userDetailsService;

    @Test
    void noInMemoryUserWithAGeneratedPasswordExists() {
        assertThat(userDetailsService).isNotInstanceOf(InMemoryUserDetailsManager.class);
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("user"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
