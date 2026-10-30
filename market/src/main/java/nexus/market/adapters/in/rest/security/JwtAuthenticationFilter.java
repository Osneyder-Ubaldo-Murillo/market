package nexus.market.adapters.in.rest.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import nexus.market.adapters.in.rest.DatabaseUnavailability;
import nexus.market.application.CredentialStorePort;
import nexus.market.domain.models.User;
import nexus.market.domain.ports.out.JwtServicePort;
import nexus.market.domain.ports.out.UserRepositoryPort;
import nexus.market.domain.valueobjects.UserId;
import nexus.market.domain.valueobjects.UserStatus;

/**
 * Filtro de autenticación JWT para NexusMarket.
 *
 * <p>Extrae el token Bearer de la cabecera {@code Authorization} y lo valida
 * mediante {@link JwtServicePort} (firma, emisor y expiración). El token solo
 * identifica al usuario: en <b>cada petición</b> se consulta el usuario por su
 * ID, se exige que esté {@code ACTIVE} y se toma el rol vigente de la base de
 * datos (no el claim del token). Así un usuario bloqueado, desactivado o
 * eliminado pierde el acceso de inmediato y un cambio de rol surte efecto sin
 * esperar a que el token expire.</p>
 *
 * <p>Falla cerrado y distingue la causa: si el token no es válido, el usuario no
 * existe o no está activo, la petición continúa <b>sin autenticar</b> y las
 * reglas de {@code SecurityConfiguration} responden 401. Si la base de datos no
 * está disponible y por eso no se puede verificar al usuario (fallo de
 * infraestructura identificable), se responde 503 {@code SERVICE_UNAVAILABLE}
 * con el formato de la API y sin detalles internos. Cualquier otra excepción
 * inesperada no se presenta como 503: se registra y se trata como no
 * autenticado.</p>
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtServicePort jwtService;
    private final ObjectProvider<UserRepositoryPort> users;
    private final ObjectProvider<CredentialStorePort> credentials;

    public JwtAuthenticationFilter(JwtServicePort jwtService, ObjectProvider<UserRepositoryPort> users,
                                   ObjectProvider<CredentialStorePort> credentials) {
        this.jwtService = jwtService;
        this.users = users;
        this.credentials = credentials;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        // Sin cabecera Bearer, o con autenticación previa: no se hace nada.
        if (authHeader == null || !authHeader.startsWith("Bearer ")
                || SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        final Optional<User> active;
        try {
            active = findActiveUser(authHeader.substring(7));
        } catch (InfrastructureUnavailableException ex) {
            log.error("No se pudo verificar al usuario del token: infraestructura no disponible ({})",
                    DatabaseUnavailability.causeChain(ex.getCause()));
            SecurityContextHolder.clearContext();
            RestSecurityHandlers.writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "SERVICE_UNAVAILABLE", "Servicio temporalmente no disponible. Intente de nuevo más tarde.");
            return;
        }

        active.ifPresent(user -> {
            var authToken = new UsernamePasswordAuthenticationToken(
                    user.getUserId().value(), null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().getCode())));
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);
        });

        filterChain.doFilter(request, response);
    }

    /** Usuario ACTIVE dueño del token, o vacío si no se puede confirmar. */
    private Optional<User> findActiveUser(String jwt) {
        try {
            if (!jwtService.isValid(jwt)) {
                return Optional.empty();
            }
            UserRepositoryPort repository = users.getIfAvailable();
            if (repository == null) {
                return Optional.empty();
            }
            Optional<UserId> userId = jwtService.getUserId(jwt);
            if (userId.isEmpty()) {
                return Optional.empty();
            }
            Optional<User> active = repository.findById(userId.get())
                    .filter(user -> user.getStatus() == UserStatus.ACTIVE);
            if (active.isPresent() && !matchesCurrentCredential(jwt, userId.get())) {
                return Optional.empty();
            }
            return active;
        } catch (Exception ex) {
            if (isInfrastructureFailure(ex)) {
                throw new InfrastructureUnavailableException(ex);
            }
            log.error("Error inesperado al verificar el usuario del token; se rechaza el acceso", ex);
            return Optional.empty();
        }
    }

    /**
     * El token debe corresponder a la credencial VIGENTE: lleva la huella {@code pwv} (HMAC del hash de contraseña) y
     * debe coincidir con la del hash actual. Tras un cambio de contraseña los tokens anteriores dejan de coincidir y se
     * tratan como no autenticados (401). Un usuario sin fila de credenciales (personal creado fuera del registro) conserva
     * el comportamiento anterior; si el almacén de credenciales no está disponible se falla cerrado. La comparación es de
     * tiempo constante.
     */
    private boolean matchesCurrentCredential(String jwt, UserId userId) {
        CredentialStorePort store = credentials.getIfAvailable();
        if (store == null) {
            return false;
        }
        Optional<String> hash = store.findByUserId(userId);
        if (hash.isEmpty()) {
            return true;
        }
        Optional<String> claim = jwtService.getCredentialVersion(jwt);
        return claim.isPresent() && MessageDigest.isEqual(
                claim.get().getBytes(StandardCharsets.UTF_8),
                jwtService.credentialVersion(hash.get()).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Fallo de CONECTIVIDAD con la base de datos, con el mismo criterio que {@code GlobalExceptionHandler}
     * ({@link DatabaseUnavailability}). Los errores SQL de programación, de integridad o de consulta no lo son.
     */
    static boolean isInfrastructureFailure(Throwable error) {
        return DatabaseUnavailability.isUnavailable(error);
    }

    private static final class InfrastructureUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InfrastructureUnavailableException(Throwable cause) {
            super(cause);
        }
    }
}
