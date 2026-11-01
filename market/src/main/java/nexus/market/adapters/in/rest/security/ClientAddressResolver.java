package nexus.market.adapters.in.rest.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Determina la dirección del cliente que usan los limitadores de intentos y el bloqueo de cuentas.
 *
 * <p><b>Por defecto no se confía en ninguna cabecera:</b> la dirección es la de la conexión TCP
 * ({@code getRemoteAddr()}). Cualquier cliente puede enviar {@code X-Forwarded-For}, así que aceptarla sin más permitiría
 * eludir los límites cambiando de "IP" en cada petición, o suplantar la de otra persona.</p>
 *
 * <p>Detrás de un proxy inverso todas las conexiones llegan desde la dirección del proxy y los clientes compartirían cupo.
 * Para ese caso se declaran los proxies propios en {@value #TRUSTED_PROXIES_PROPERTY} (direcciones IP o CIDR separados por
 * comas, p. ej. {@code 10.0.0.5,172.16.0.0/12}). Solo si la conexión TCP procede de uno de ellos se lee
 * {@code X-Forwarded-For}, <b>de derecha a izquierda</b>: cada proxy añade al final la dirección de quien le habló, así que
 * lo que hay a la derecha lo escribió un proxy de confianza y lo de la izquierda puede ser falsificado por el cliente. Se
 * descartan las entradas que son proxies de confianza y la primera que no lo es es el cliente.</p>
 *
 * <p>Ante cualquier duda se devuelve la dirección TCP (nunca un valor de la cabecera): entrada que no es una IP literal
 * (no se resuelven nombres, no hay DNS), cabecera excesivamente larga o con demasiadas entradas, o una cadena formada solo
 * por proxies de confianza. Solo se interpreta {@code X-Forwarded-For}; la cabecera {@code Forwarded} (RFC 7239) y los
 * puertos dentro de las entradas no se admiten. No se activa {@code server.forward-headers-strategy}, que confiaría en
 * cualquier remitente.</p>
 *
 * <p>La configuración es estricta: un valor que no sea una IP o un CIDR válido, o un prefijo {@code /0} (confiaría en todo
 * Internet), impide arrancar.</p>
 */
@Component
public class ClientAddressResolver {

    /** Proxies inversos propios en los que se confía para leer {@code X-Forwarded-For}. Vacío: ninguno. */
    public static final String TRUSTED_PROXIES_PROPERTY = "nexusmarket.proxy.trusted-proxies";

    static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final int MAX_HEADER_CHARS = 1024;
    private static final int MAX_ENTRIES = 20;

    private static final Logger log = LoggerFactory.getLogger(ClientAddressResolver.class);

    private static final Pattern IPV4 = Pattern
            .compile("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

    private record TrustedNetwork(byte[] network, int prefixBits) {

        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false; // IPv4 frente a IPv6: nunca coinciden (las IPv4 mapeadas ya llegan como IPv4)
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            int remaining = prefixBits % 8;
            if (remaining == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remaining)) & 0xFF;
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }

    private final List<TrustedNetwork> trusted;

    @Autowired
    public ClientAddressResolver(@Value("${" + TRUSTED_PROXIES_PROPERTY + ":}") String trustedProxies) {
        this.trusted = Collections.unmodifiableList(parseTrusted(trustedProxies));
        if (!trusted.isEmpty()) {
            log.info("[PROXY] Se leerá X-Forwarded-For solo cuando la conexión proceda de {} red(es) de proxy declaradas en {}.",
                    trusted.size(), TRUSTED_PROXIES_PROPERTY);
        }
    }

    /** Sin proxies de confianza: siempre la dirección TCP (pruebas y desarrollo). */
    public ClientAddressResolver() {
        this("");
    }

    /** Dirección del cliente para los límites de intentos: la TCP, o la de {@code X-Forwarded-For} si hay proxy de confianza. */
    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (trusted.isEmpty()) {
            return remote;
        }
        InetAddress remoteIp = parseLiteral(remote);
        if (remoteIp == null || !isTrusted(remoteIp)) {
            return remote; // no es un proxy nuestro: sus cabeceras no valen nada
        }
        String chain = forwardedChain(request);
        if (chain == null) {
            return remote;
        }
        String[] entries = chain.split(",", -1);
        if (entries.length > MAX_ENTRIES) {
            return remote;
        }
        for (int i = entries.length - 1; i >= 0; i--) {
            InetAddress hop = parseLiteral(entries[i]);
            if (hop == null) {
                return remote; // basura o nombre en la cadena: no se adivina nada
            }
            if (!isTrusted(hop)) {
                return hop.getHostAddress();
            }
        }
        return remote; // todo eran proxies de confianza: no hay cliente que identificar
    }

    private static String forwardedChain(HttpServletRequest request) {
        java.util.Enumeration<String> lines = request.getHeaders(FORWARDED_FOR);
        if (lines == null || !lines.hasMoreElements()) {
            return null;
        }
        StringBuilder joined = new StringBuilder();
        while (lines.hasMoreElements()) {
            if (joined.length() > 0) {
                joined.append(','); // varias líneas de cabecera equivalen a una lista separada por comas
            }
            joined.append(lines.nextElement());
            if (joined.length() > MAX_HEADER_CHARS) {
                return null;
            }
        }
        return joined.toString().isBlank() ? null : joined.toString();
    }

    private boolean isTrusted(InetAddress address) {
        for (TrustedNetwork network : trusted) {
            if (network.contains(address)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ configuración

    static List<TrustedNetwork> parseTrusted(String configured) {
        List<TrustedNetwork> networks = new ArrayList<>();
        if (configured == null || configured.isBlank()) {
            return networks;
        }
        for (String raw : configured.split(",", -1)) {
            String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            String addressPart = token;
            Integer prefix = null;
            int slash = token.indexOf('/');
            if (slash >= 0) {
                addressPart = token.substring(0, slash);
                String prefixText = token.substring(slash + 1);
                if (!prefixText.matches("\\d{1,3}")) {
                    throw invalid(token, "el prefijo del CIDR no es un número");
                }
                prefix = Integer.parseInt(prefixText);
            }
            InetAddress address = parseLiteral(addressPart);
            if (address == null) {
                throw invalid(token, "debe ser una dirección IP literal o un CIDR (no se admiten nombres de host)");
            }
            int bits = address.getAddress().length * 8;
            int effective = prefix == null ? bits : prefix;
            if (effective == 0) {
                throw invalid(token, "un prefijo /0 haría confiar en todo Internet");
            }
            if (effective > bits) {
                throw invalid(token, "el prefijo supera los " + bits + " bits de la dirección");
            }
            networks.add(new TrustedNetwork(address.getAddress(), effective));
        }
        return networks;
    }

    private static IllegalArgumentException invalid(String token, String reason) {
        String shown = token.length() > 64 ? token.substring(0, 64) + "..." : token;
        return new IllegalArgumentException(TRUSTED_PROXIES_PROPERTY + " contiene un valor no válido ('" + shown + "'): " + reason
                + ". Use direcciones IP o CIDR separados por comas, p. ej. 10.0.0.5,172.16.0.0/12.");
    }

    // ------------------------------------------------------------------ IP literal, sin DNS

    /**
     * Interpreta una dirección IPv4 o IPv6 literal y devuelve {@code null} si no lo es. No resuelve nombres: ni IPv4 ni IPv6
     * llegan a {@code InetAddress.getByName} sin haber pasado una validación sintáctica estricta (que además excluye
     * cualquier nombre de host, zona o puerto).
     */
    static InetAddress parseLiteral(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        if (s.length() >= 2 && s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.isEmpty() || s.length() > 45) {
            return null;
        }
        try {
            if (IPV4.matcher(s).matches()) {
                String[] parts = s.split("\\.");
                byte[] bytes = new byte[4];
                for (int i = 0; i < 4; i++) {
                    bytes[i] = (byte) Integer.parseInt(parts[i]);
                }
                return InetAddress.getByAddress(bytes);
            }
            if (s.indexOf(':') >= 0 && IPV6_CHARS.matcher(s).matches()) {
                new URI("http://[" + s + "]/"); // valida la sintaxis IPv6 (sin resolver nada)
                return InetAddress.getByName(s); // ya es un literal válido: no hay búsqueda DNS
            }
        } catch (URISyntaxException | UnknownHostException | RuntimeException e) {
            return null;
        }
        return null;
    }
}
