package nexus.market.infrastructure.config;

import java.util.List;
import java.util.Map;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

import nexus.market.adapters.in.rest.responses.UserResponses.ErrorResponse;

/**
 * Documentación OpenAPI (Swagger) de la API.
 *
 * <p>Declara el esquema de seguridad {@code bearerAuth} (JWT): con él, Swagger UI muestra el botón <b>Authorize</b> y las
 * pruebas de los endpoints protegidos envían el token. La seguridad es global y los endpoints públicos la quitan con
 * {@code @SecurityRequirements}. Cada operación lleva su resumen y los roles que la pueden usar (anotaciones
 * {@code @Operation} de los controladores) y las respuestas de error estándar se añaden aquí una sola vez.</p>
 *
 * <p>La documentación solo se publica con {@code springdoc.api-docs.enabled} y {@code springdoc.swagger-ui.enabled}
 * (activos en desarrollo; en producción, solo con {@code SWAGGER_ENABLED=true}). Ver {@code market/docs/API_SWAGGER.md}.</p>
 */
@Configuration
public class OpenApiConfiguration {

    /** Nombre del esquema de seguridad (JWT en la cabecera Authorization). */
    public static final String BEARER = "bearerAuth";

    private static final String ERROR_SCHEMA = "ErrorResponse";

    private static final String DESCRIPTION = """
            API REST de **NexusMarket**, un marketplace que intermedia entre compradores y vendedores.

            **Cómo probarla desde esta página**
            1. Ejecute `POST /api/auth/login` con un correo y una contraseña y copie el `token` de la respuesta.
            2. Pulse **Authorize**, pegue el token (sin escribir `Bearer`) y confirme.
            3. Ya puede ejecutar los endpoints protegidos con el rol de esa cuenta.

            **Roles:** COMPRADOR, VENDEDOR, OPERADOR logístico, ADMIN y SUPERVISOR (solo lectura). Cada operación indica
            quién puede usarla. Además del rol, un comprador o un vendedor solo accede a sus propios recursos.

            **Errores:** todos usan el mismo formato `{code, message, timestamp}`. 400 cuerpo inválido, 401 sin token o token
            inválido, 403 sin permiso, correo sin verificar o cuenta bloqueada, 404 no existe, 409 estado o duplicado,
            429 demasiados intentos y 503 base de datos no disponible.

            **Importante:** el pago, el envío y las notificaciones son **simulados**: no cobran, no crean envíos reales ni
            envían correos (los códigos de verificación se escriben en el log de la aplicación).
            """;

    @Bean
    public OpenAPI nexusMarketOpenApi() {
        return new OpenAPI()
                .info(new Info().title("NexusMarket API").version("1.0.0").description(DESCRIPTION))
                .servers(List.of(new Server().url("http://localhost:8080").description("Local (Docker Compose)")))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Token JWT de `POST /api/auth/login`. Se pega sin la palabra Bearer.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    /** Registra el esquema del cuerpo de error, que ningún controlador devuelve directamente (lo produce el manejador global). */
    @Bean
    public OpenApiCustomizer errorSchemaCustomizer() {
        return openApi -> {
            Map<String, Schema> schemas = ModelConverters.getInstance().read(ErrorResponse.class);
            Schema<?> error = schemas.get(ERROR_SCHEMA);
            if (error != null) {
                error.description("Formato uniforme de error de la API.");
                openApi.getComponents().addSchemas(ERROR_SCHEMA, error);
            }
        };
    }

    /** Respuestas de error comunes: 400 si la operación recibe cuerpo, y 401 y 403 si es protegida. */
    @Bean
    public OperationCustomizer standardErrorResponses() {
        return (operation, handlerMethod) -> {
            ApiResponses responses = operation.getResponses() == null ? new ApiResponses() : operation.getResponses();
            boolean secured = operation.getSecurity() == null || !operation.getSecurity().isEmpty();
            if (operation.getRequestBody() != null) {
                errorResponse(responses, "400", "Cuerpo o parámetros inválidos.");
            }
            if (secured) {
                errorResponse(responses, "401", "Falta el token, es inválido o venció.");
                errorResponse(responses, "403", "El rol no permite la operación, el recurso es de otra persona, "
                        + "el correo no está verificado o la cuenta está bloqueada.");
            }
            operation.setResponses(responses);
            return operation;
        };
    }

    private static void errorResponse(ApiResponses responses, String code, String description) {
        if (responses.get(code) == null) {
            responses.addApiResponse(code, new ApiResponse().description(description).content(new Content()
                    .addMediaType("application/json",
                            new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + ERROR_SCHEMA)))));
        }
    }
}
