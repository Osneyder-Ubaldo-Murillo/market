package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.infrastructure.config.SecurityConfiguration;

/**
 * Valores de producción aplicados sobre H2 (sin MySQL/Mongo reales): CORS sin
 * orígenes, Swagger/OpenAPI deshabilitados, health sin detalles y el resto de
 * Actuator protegido. No demuestra que el perfil {@code prod} arranque completo.
 */
@ActiveProfiles("jpa")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nexusmarket_prodlike;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.data.mongodb.uri=",
        "spring.data.mongodb.database=test_audit",
        "nexusmarket.cors.allowed-origins=",
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false",
        "management.endpoints.web.exposure.include=health,info,metrics",
        "management.endpoint.health.show-details=never"
})
class ProductionLikeSecurityTest extends RestTestSupport {

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("corsConfigurationSource")
    private CorsConfigurationSource corsSource;

    @Test
    void cors_withoutConfiguredOrigins_allowsNoCrossOrigin() throws Exception {
        MvcResult result = mockMvc.perform(options("/api/products")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET")).andReturn();
        // Sin orígenes configurados el navegador no recibe cabeceras CORS y bloquea la petición.
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Credentials")).isNull();
        assertThat(((UrlBasedCorsConfigurationSource) corsSource).getCorsConfigurations()).isEmpty();
    }

    @Test
    void cors_rejectsWildcardOrigins_andNeverCombinesThemWithCredentials() {
        SecurityConfiguration config = new SecurityConfiguration();
        assertThatThrownBy(() -> config.corsConfigurationSource("*")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.corsConfigurationSource("https://a.example, https://*.b.example"))
                .isInstanceOf(IllegalStateException.class);

        UrlBasedCorsConfigurationSource source = (UrlBasedCorsConfigurationSource) config
                .corsConfigurationSource(" https://a.example , https://b.example ");
        CorsConfiguration cors = source.getCorsConfigurations().get("/**");
        assertThat(cors.getAllowedOrigins()).containsExactly("https://a.example", "https://b.example");
        assertThat(cors.getAllowCredentials()).isTrue();
        assertThat(cors.getAllowedOriginPatterns()).isNullOrEmpty();

        assertThat(((UrlBasedCorsConfigurationSource) config.corsConfigurationSource("")).getCorsConfigurations())
                .isEmpty();
    }

    @Test
    void swaggerAndOpenApi_areDisabled_forEveryone() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        for (String path : new String[] { "/v3/api-docs", "/swagger-ui.html", "/swagger-ui/index.html" }) {
            assertThat(status(HttpMethod.GET, path, null, null)).as(path).isEqualTo(401);
            assertThat(status(HttpMethod.GET, path, admin, null)).as(path).isEqualTo(403);
        }
    }

    @Test
    void health_isPublicWithoutDetails_andOtherActuatorEndpointsStayProtected() throws Exception {
        MvcResult health = perform(HttpMethod.GET, "/actuator/health", null, null);
        assertThat(health.getResponse().getStatus()).isNotIn(401, 403, 404);
        assertThat(health.getResponse().getContentAsString()).doesNotContain("components", "details");

        String admin = staffToken(SystemRole.ADMIN);
        String buyer = registerBuyer().token();
        String supervisor = staffToken(SystemRole.SUPERVISOR);
        for (String path : new String[] { "/actuator/metrics", "/actuator/info", "/actuator/env" }) {
            assertThat(status(HttpMethod.GET, path, null, null)).as(path).isEqualTo(401);
            assertThat(status(HttpMethod.GET, path, buyer, null)).as(path).isEqualTo(403);
            assertThat(status(HttpMethod.GET, path, supervisor, null)).as(path).isEqualTo(403);
        }
        assertThat(status(HttpMethod.GET, "/actuator/metrics", admin, null)).isEqualTo(200);
        // /actuator/env no está expuesto: ni siquiera el ADMIN lo ve.
        assertThat(status(HttpMethod.GET, "/actuator/env", admin, null)).isEqualTo(404);
    }

    @Test
    void productionProperties_defineSafeDefaults() throws Exception {
        Properties prod = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application-prod.properties")) {
            assertThat(in).isNotNull();
            prod.load(in);
        }
        // Sin prefijo duplicado /api/api y sin perfil declarado dentro del propio perfil.
        assertThat(prod).doesNotContainKey("server.servlet.context-path");
        assertThat(prod).doesNotContainKey("spring.profiles.active");
        assertThat(prod.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
        assertThat(prod.getProperty("nexusmarket.cors.allowed-origins")).isEqualTo("${CORS_ALLOWED_ORIGINS:}");
        assertThat(prod.getProperty("springdoc.api-docs.enabled")).isEqualTo("${SWAGGER_ENABLED:false}");
        assertThat(prod.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("${SWAGGER_ENABLED:false}");
    }
}
