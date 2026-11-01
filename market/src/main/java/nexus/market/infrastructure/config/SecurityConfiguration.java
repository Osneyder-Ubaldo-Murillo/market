package nexus.market.infrastructure.config;

import java.util.Arrays;
import java.util.List;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import nexus.market.adapters.in.rest.security.RestSecurityHandlers;
import nexus.market.adapters.in.rest.security.JwtAuthenticationFilter;
import nexus.market.application.CredentialStorePort;
import nexus.market.domain.ports.out.JwtServicePort;
import nexus.market.domain.ports.out.UserRepositoryPort;

/**
 * Configuración de seguridad HTTP para NexusMarket.
 *
 * <p>Autenticación stateless con JWT y autorización por rol. <b>Todo endpoint
 * sin regla explícita queda denegado</b> ({@code denyAll}). Estas reglas solo
 * validan el rol; la propiedad del recurso (comprador/vendedor dueño) la
 * comprueba {@code AccessPolicy} dentro de cada controlador.</p>
 *
 * <p>Públicos: registro de comprador, login, consulta del catálogo
 * ({@code GET /api/products}, {@code GET /api/products/{id}}, este último
 * filtrado a productos publicados), {@code /actuator/health} y la
 * documentación de la API. Un usuario anónimo recibe 401 y un usuario
 * autenticado sin permiso recibe 403, ambos con el formato
 * {@code ErrorResponse}.</p>
 *
 * <p>La documentación (Swagger/OpenAPI) solo es pública si
 * {@code springdoc.api-docs.enabled} y {@code springdoc.swagger-ui.enabled}
 * están activos (desarrollo); en producción está desactivada por defecto y sus
 * rutas quedan bajo {@code denyAll}. CORS solo admite los orígenes exactos de
 * {@code nexusmarket.cors.allowed-origins}.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    private static final String BUYER = "BUYER";
    private static final String SELLER = "SELLER";
    private static final String OPERATOR = "LOGISTICS_OPERATOR";
    private static final String ADMIN = "ADMIN";
    private static final String SUPERVISOR = "SUPERVISOR";

    /**
     * Sin usuarios locales de Spring Security: la autenticación es solo el JWT. Si no existe ningún
     * {@link UserDetailsService}, Spring Boot crea un usuario en memoria con una contraseña aleatoria y <b>la escribe en el
     * log de arranque</b> ("Using generated security password"); este servicio vacío evita crear ese usuario y escribir ese
     * secreto en los logs de producción.
     */
    @Bean
    public UserDetailsService noLocalUsers() {
        return username -> {
            throw new UsernameNotFoundException("La aplicación no tiene usuarios locales: se autentica con JWT.");
        };
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtServicePort jwtService,
                                                           ObjectProvider<UserRepositoryPort> users,
                                                           ObjectProvider<CredentialStorePort> credentials) {
        return new JwtAuthenticationFilter(jwtService, users, credentials);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthFilter,
                                                   CorsConfigurationSource corsConfigurationSource,
                                                   @Value("${springdoc.api-docs.enabled:true}") boolean apiDocsEnabled,
                                                   @Value("${springdoc.swagger-ui.enabled:true}") boolean swaggerUiEnabled)
            throws Exception {
        RestSecurityHandlers handlers = new RestSecurityHandlers();
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                // CSRF deshabilitado (API stateless con JWT)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // Despacho de errores internos (no expone recursos)
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()

                        // ---- Públicos ----
                        .requestMatchers(HttpMethod.POST, "/api/auth/register/buyer", "/api/auth/login",
                                "/api/auth/password/forgot", "/api/auth/password/reset",
                                "/api/auth/email/verify", "/api/auth/email/resend").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()

                        // ---- Documentación: pública solo si está habilitada (por defecto, solo en desarrollo) ----
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**")
                        .access((authentication, context) -> new AuthorizationDecision(apiDocsEnabled))
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html")
                        .access((authentication, context) -> new AuthorizationDecision(swaggerUiEnabled))

                        // ---- Usuarios y vendedores (administración) ----
                        .requestMatchers(HttpMethod.POST, "/api/auth/register/seller").hasRole(ADMIN)
                        // Rotación de la contraseña del propio administrador (el destino es siempre el usuario autenticado).
                        .requestMatchers(HttpMethod.POST, "/api/auth/password").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/sellers/*/approve").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.PATCH, "/api/users/*/block", "/api/users/*/activate",
                                "/api/users/*/deactivate").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/users/*")
                        .hasAnyRole(BUYER, SELLER, OPERATOR, ADMIN, SUPERVISOR)

                        // ---- Comprador: direcciones, carrito y pedidos propios ----
                        .requestMatchers(HttpMethod.POST, "/api/buyers/*/addresses").hasRole(BUYER)
                        .requestMatchers(HttpMethod.GET, "/api/buyers/*/cart").hasRole(BUYER)
                        .requestMatchers(HttpMethod.DELETE, "/api/buyers/*/cart").hasRole(BUYER)
                        .requestMatchers(HttpMethod.POST, "/api/buyers/*/cart/items").hasRole(BUYER)
                        .requestMatchers(HttpMethod.PUT, "/api/buyers/*/cart/items/*").hasRole(BUYER)
                        .requestMatchers(HttpMethod.DELETE, "/api/buyers/*/cart/items/*").hasRole(BUYER)
                        .requestMatchers(HttpMethod.POST, "/api/buyers/*/orders").hasRole(BUYER)
                        .requestMatchers(HttpMethod.GET, "/api/buyers/*/orders").hasRole(BUYER)
                        .requestMatchers(HttpMethod.POST, "/api/orders/*/payment").hasRole(BUYER)
                        .requestMatchers(HttpMethod.POST, "/api/orders/*/cancel").hasRole(BUYER)
                        .requestMatchers(HttpMethod.POST, "/api/orders/*/returns").hasRole(BUYER)

                        // ---- Consulta de pedidos y envíos ----
                        .requestMatchers(HttpMethod.GET, "/api/orders/*", "/api/orders/*/shipping", "/api/shippings/*")
                        .hasAnyRole(BUYER, SELLER, OPERATOR, ADMIN, SUPERVISOR)

                        // ---- Logística: solo el operador despacha y actualiza estados ----
                        .requestMatchers(HttpMethod.POST, "/api/orders/*/dispatch", "/api/orders/*/deliver",
                                "/api/orders/*/shippings", "/api/shippings/*/dispatch",
                                "/api/shippings/*/in-transit", "/api/shippings/*/deliver").hasRole(OPERATOR)

                        // ---- Facturación (el vendedor queda excluido por ahora) ----
                        .requestMatchers(HttpMethod.GET, "/api/buyers/*/invoices").hasRole(BUYER)
                        .requestMatchers(HttpMethod.GET, "/api/invoices/*", "/api/orders/*/invoice")
                        .hasAnyRole(BUYER, ADMIN, SUPERVISOR)
                        .requestMatchers(HttpMethod.POST, "/api/orders/*/invoice", "/api/invoices/*/cancel")
                        .hasRole(ADMIN)

                        // ---- Devoluciones y reembolsos ----
                        .requestMatchers(HttpMethod.GET, "/api/returns/*")
                        .hasAnyRole(BUYER, OPERATOR, ADMIN, SUPERVISOR)
                        .requestMatchers(HttpMethod.POST, "/api/returns/*/approve", "/api/returns/*/reject",
                                "/api/refunds/*/process").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/returns/*/process").hasRole(OPERATOR)

                        // ---- Catálogo: el vendedor administra solo sus productos ----
                        .requestMatchers(HttpMethod.POST, "/api/products").hasRole(SELLER)
                        .requestMatchers(HttpMethod.POST, "/api/products/*/publish", "/api/products/*/unpublish")
                        .hasRole(SELLER)
                        .requestMatchers(HttpMethod.PUT, "/api/products/*/price").hasRole(SELLER)
                        .requestMatchers(HttpMethod.PUT, "/api/products/*").hasRole(SELLER)

                        // ---- Bodegas ----
                        .requestMatchers(HttpMethod.POST, "/api/warehouses/marketplace",
                                "/api/sellers/*/warehouses").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/warehouses/*", "/api/sellers/*/warehouses")
                        .hasAnyRole(SELLER, OPERATOR, ADMIN, SUPERVISOR)

                        // ---- Inventario: vendedor (propio), administración y supervisión (lectura).
                        // El operador logístico no tiene acceso mientras no exista relación operador-bodega.
                        // reserve/release no se exponen: se ejecutan dentro del flujo de pedidos.
                        .requestMatchers(HttpMethod.POST, "/api/inventory", "/api/inventory/*/stock",
                                "/api/inventory/*/damaged", "/api/inventory/*/active").hasRole(SELLER)
                        .requestMatchers(HttpMethod.PUT, "/api/inventory/*/quantity").hasRole(SELLER)
                        .requestMatchers(HttpMethod.GET, "/api/inventory/*").hasAnyRole(SELLER, ADMIN, SUPERVISOR)

                        // ---- Monitoreo restringido ----
                        .requestMatchers("/actuator/**").hasRole(ADMIN)

                        // Denegado por defecto
                        .anyRequest().denyAll()
                );
        return http.build();
    }

    /**
     * CORS con orígenes exactos tomados de {@code nexusmarket.cors.allowed-origins}
     * (lista separada por comas). Sin orígenes configurados no se permite ningún
     * origen cruzado. Nunca se admite {@code *}: con credenciales habilitadas
     * permitiría que cualquier sitio usara la sesión del usuario.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${nexusmarket.cors.allowed-origins:}") String allowedOrigins) {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        if (origins.stream().anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalStateException(
                    "nexusmarket.cors.allowed-origins no admite comodines: indique los orígenes exactos.");
        }

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (!origins.isEmpty()) {
            CorsConfiguration configuration = new CorsConfiguration();
            configuration.setAllowedOrigins(origins);
            configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
            configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
            // Cabeceras que el navegador puede leer: total de los listados paginados y espera tras un 429.
            configuration.setExposedHeaders(List.of("X-Total-Count", "Retry-After"));
            configuration.setAllowCredentials(true);
            configuration.setMaxAge(3600L);
            source.registerCorsConfiguration("/**", configuration);
        }
        return source;
    }
}
