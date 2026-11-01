package nexus.market.adapters.in.rest.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Identificación del cliente para los límites de intentos: por defecto solo cuenta la conexión TCP; X-Forwarded-For se
 * lee únicamente si la conexión procede de un proxy declarado y siempre de derecha a izquierda.
 */
class ClientAddressResolverTest {

    private static MockHttpServletRequest request(String remote, String... forwardedLines) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        for (String line : forwardedLines) {
            request.addHeader("X-Forwarded-For", line);
        }
        return request;
    }

    private static ClientAddressResolver resolver(String trusted) {
        return new ClientAddressResolver(trusted);
    }

    // ------------------------------------------------------------------ sin proxies de confianza

    @Test
    void withoutTrustedProxies_theTcpAddressIsUsed_andForwardedForIsIgnored() {
        ClientAddressResolver resolver = new ClientAddressResolver();
        assertThat(resolver.resolve(request("203.0.113.7", "6.6.6.6"))).isEqualTo("203.0.113.7");
        assertThat(resolver("").resolve(request("203.0.113.7", "6.6.6.6"))).isEqualTo("203.0.113.7");
        assertThat(resolver("  ").resolve(request("10.0.0.1", "6.6.6.6"))).isEqualTo("10.0.0.1");
    }

    @Test
    void anUntrustedConnection_cannotChooseItsOwnAddress() {
        ClientAddressResolver resolver = resolver("10.0.0.1");
        // Un atacante directo envía X-Forwarded-For para cambiar de "IP" en cada petición: no sirve de nada.
        assertThat(resolver.resolve(request("203.0.113.7", "1.1.1.1"))).isEqualTo("203.0.113.7");
        assertThat(resolver.resolve(request("203.0.113.7", "10.0.0.1"))).isEqualTo("203.0.113.7");
        // Ni siquiera una dirección parecida a la del proxy: 10.0.0.2 no es 10.0.0.1.
        assertThat(resolver.resolve(request("10.0.0.2", "1.1.1.1"))).isEqualTo("10.0.0.2");
    }

    // ------------------------------------------------------------------ con proxy de confianza

    @Test
    void aTrustedProxy_reportsTheRealClient() {
        ClientAddressResolver resolver = resolver("10.0.0.1");
        assertThat(resolver.resolve(request("10.0.0.1", "203.0.113.9"))).isEqualTo("203.0.113.9");
    }

    @Test
    void withoutTheHeader_orWithABlankOne_theProxyAddressIsUsed() {
        ClientAddressResolver resolver = resolver("10.0.0.1");
        assertThat(resolver.resolve(request("10.0.0.1"))).isEqualTo("10.0.0.1");
        assertThat(resolver.resolve(request("10.0.0.1", ""))).isEqualTo("10.0.0.1");
        assertThat(resolver.resolve(request("10.0.0.1", "   "))).isEqualTo("10.0.0.1");
    }

    @Test
    void anAddressInjectedOnTheLeft_isIgnored_becauseTheChainIsReadFromTheRight() {
        ClientAddressResolver resolver = resolver("10.0.0.1");
        // El cliente envió "6.6.6.6"; el proxy añadió al final la dirección real de quien le habló.
        assertThat(resolver.resolve(request("10.0.0.1", "6.6.6.6, 203.0.113.9"))).isEqualTo("203.0.113.9");
        assertThat(resolver.resolve(request("10.0.0.1", "6.6.6.6,7.7.7.7,203.0.113.9"))).isEqualTo("203.0.113.9");
    }

    @Test
    void severalTrustedProxiesInTheChain_areSkipped() {
        ClientAddressResolver resolver = resolver("10.0.0.0/24");
        assertThat(resolver.resolve(request("10.0.0.1", "203.0.113.9, 10.0.0.2"))).isEqualTo("203.0.113.9");
        assertThat(resolver.resolve(request("10.0.0.1", "6.6.6.6, 203.0.113.9, 10.0.0.77, 10.0.0.2")))
                .isEqualTo("203.0.113.9");
    }

    @Test
    void severalHeaderLines_areTreatedAsOneList() {
        ClientAddressResolver resolver = resolver("10.0.0.1");
        assertThat(resolver.resolve(request("10.0.0.1", "6.6.6.6", "203.0.113.9"))).isEqualTo("203.0.113.9");
    }

    @Test
    void aChainMadeOnlyOfTrustedProxies_fallsBackToTheTcpAddress() {
        ClientAddressResolver resolver = resolver("10.0.0.0/24");
        assertThat(resolver.resolve(request("10.0.0.1", "10.0.0.2, 10.0.0.3"))).isEqualTo("10.0.0.1");
    }

    @Test
    void anythingThatIsNotALiteralIp_makesTheWholeChainUntrustworthy() {
        ClientAddressResolver resolver = resolver("10.0.0.1");
        String[] bad = { "evil.example.com", "localhost", "unknown", "203.0.113.9:8080", "1.2.3.256", "1.2.3", "01.2.3.4",
                "0x7f.0.0.1", "2130706433", "1.2.3.4.5", "203.0.113.9; DROP", "<script>", ",", ", ,", "-1.2.3.4",
                "1.2.3.4/24", "::::", ":::", "1:2:3:4:5:6:7:8:9", "fe80::1%eth0", "g::1" };
        for (String value : bad) {
            assertThat(resolver.resolve(request("10.0.0.1", value))).as("valor: [%s]", value).isEqualTo("10.0.0.1");
            // Aunque venga detrás de una dirección válida: se resuelve de derecha a izquierda y la basura invalida todo.
            assertThat(resolver.resolve(request("10.0.0.1", "203.0.113.9, " + value))).as("valor: [%s]", value)
                    .isEqualTo("10.0.0.1");
        }
    }

    @Test
    void anOversizedHeader_orTooManyEntries_isNotTrusted() {
        ClientAddressResolver resolver = resolver("10.0.0.1");
        String many = String.join(", ", java.util.Collections.nCopies(21, "203.0.113.9"));
        assertThat(resolver.resolve(request("10.0.0.1", many))).isEqualTo("10.0.0.1");
        String longValue = "203.0.113.9" + " ".repeat(2000);
        assertThat(resolver.resolve(request("10.0.0.1", longValue))).isEqualTo("10.0.0.1");
    }

    // ------------------------------------------------------------------ IPv6 y CIDR

    @Test
    void ipv6_isSupported_andNormalized() {
        ClientAddressResolver resolver = resolver("fd00::/8");
        assertThat(resolver.resolve(request("fd00::1", "2001:db8::5"))).isEqualTo("2001:db8:0:0:0:0:0:5");
        assertThat(resolver.resolve(request("fd00::1", "[2001:db8::5]"))).isEqualTo("2001:db8:0:0:0:0:0:5");
        // Dos escrituras de la misma dirección dan la misma clave para los limitadores.
        assertThat(resolver.resolve(request("fd00::1", "2001:0db8:0:0:0:0:0:5")))
                .isEqualTo(resolver.resolve(request("fd00::1", "2001:db8::5")));
        // Un proxy IPv6 no autoriza a un remitente IPv4 con la misma representación numérica.
        assertThat(resolver.resolve(request("10.0.0.1", "2001:db8::5"))).isEqualTo("10.0.0.1");
    }

    @Test
    void anIpv4MappedIpv6Remote_isMatchedAsIpv4() {
        ClientAddressResolver resolver = resolver("10.0.0.0/8");
        assertThat(resolver.resolve(request("::ffff:10.0.0.1", "203.0.113.9"))).isEqualTo("203.0.113.9");
    }

    @Test
    void cidrBoundaries_areExact() {
        ClientAddressResolver half = resolver("10.0.0.0/25");
        assertThat(half.resolve(request("10.0.0.127", "203.0.113.9"))).isEqualTo("203.0.113.9");
        assertThat(half.resolve(request("10.0.0.128", "203.0.113.9"))).isEqualTo("10.0.0.128");
        ClientAddressResolver odd = resolver("172.16.0.0/12");
        assertThat(odd.resolve(request("172.31.255.255", "203.0.113.9"))).isEqualTo("203.0.113.9");
        assertThat(odd.resolve(request("172.32.0.0", "203.0.113.9"))).isEqualTo("172.32.0.0");
        ClientAddressResolver exact = resolver("10.0.0.5/32");
        assertThat(exact.resolve(request("10.0.0.5", "203.0.113.9"))).isEqualTo("203.0.113.9");
        assertThat(exact.resolve(request("10.0.0.6", "203.0.113.9"))).isEqualTo("10.0.0.6");
    }

    @Test
    void aListWithSeveralEntries_andStraySeparators_isAccepted() {
        ClientAddressResolver resolver = resolver(" 10.0.0.5 , ,172.16.0.0/12,fd00::/8 ");
        assertThat(resolver.resolve(request("10.0.0.5", "203.0.113.9"))).isEqualTo("203.0.113.9");
        assertThat(resolver.resolve(request("172.20.1.1", "203.0.113.9"))).isEqualTo("203.0.113.9");
        assertThat(resolver.resolve(request("fd12::1", "203.0.113.9"))).isEqualTo("203.0.113.9");
        assertThat(resolver.resolve(request("192.168.1.1", "203.0.113.9"))).isEqualTo("192.168.1.1");
    }

    // ------------------------------------------------------------------ configuración estricta

    @Test
    void anInvalidConfiguration_preventsStartup() {
        for (String value : new String[] { "example.com", "*", "all", "10.0.0.256", "10.0.0.0/33", "10.0.0.0/abc", "10.0.0.1/",
                "10.0.0.0/-1", "fd00::/129", "10.0.0.1, evil.example.com", "10.0.0.1:8080", "10.0.0.0/8/8" }) {
            assertThatThrownBy(() -> resolver(value)).as("valor: [%s]", value)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(ClientAddressResolver.TRUSTED_PROXIES_PROPERTY);
        }
    }

    @Test
    void trustingTheWholeInternet_isRejected() {
        for (String value : new String[] { "0.0.0.0/0", "10.0.0.0/0", "::/0" }) {
            assertThatThrownBy(() -> resolver(value)).as("valor: [%s]", value)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("/0");
        }
    }

    @Test
    void theErrorMessage_isBounded() {
        String huge = "x".repeat(5000);
        assertThatThrownBy(() -> resolver(huge)).isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage().length()).isLessThan(400));
    }

    @Test
    void literalParsing_neverResolvesNames() {
        assertThat(ClientAddressResolver.parseLiteral("localhost")).isNull();
        assertThat(ClientAddressResolver.parseLiteral("example.com")).isNull();
        assertThat(ClientAddressResolver.parseLiteral(null)).isNull();
        assertThat(ClientAddressResolver.parseLiteral("")).isNull();
        assertThat(ClientAddressResolver.parseLiteral("127.0.0.1")).isNotNull();
        assertThat(ClientAddressResolver.parseLiteral("::1")).isNotNull();
    }

    // ------------------------------------------------------------------ el contenedor no debe confiar por su cuenta

    @Test
    void theFrameworkWideForwardedHeaderStrategy_isNeverEnabled() throws IOException {
        // server.forward-headers-strategy=framework|native reescribiría getRemoteAddr() con cualquier X-Forwarded-For.
        Path resources = Paths.get("src/main/resources");
        for (String file : List.of("application.properties", "application-jpa.properties", "application-prod.properties")) {
            String content = Files.readString(resources.resolve(file));
            assertThat(content).as(file).doesNotContain("forward-headers-strategy");
        }
        assertThatCode(() -> new ClientAddressResolver("10.0.0.1")).doesNotThrowAnyException();
    }
}
