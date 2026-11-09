package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

import io.swagger.v3.core.util.Json;

/**
 * La documentación OpenAPI (Swagger) no puede desviarse del código: cada operación lleva resumen, roles y etiqueta; lo que
 * declara como público o protegido coincide con la seguridad real; y el documento publicado en
 * {@code docs/api/openapi.json} es exactamente el que genera la aplicación.
 *
 * <p>Si cambia un endpoint, la prueba del documento fallará con un mensaje: regenérelo con
 * {@code ./mvnw test -Dtest=OpenApiDocumentationIntegrationTest -Dopenapi.update=true}.</p>
 */
class OpenApiDocumentationIntegrationTest extends RestTestSupport {

    private static final Path PUBLISHED = Paths.get("docs", "api", "openapi.json");
    private static final List<String> VERBS = List.of("get", "post", "put", "patch", "delete");

    /** Endpoints que la seguridad real deja abiertos (SecurityConfiguration). */
    private static final Set<String> PUBLIC = Set.of(
            "POST /api/auth/register/buyer", "POST /api/auth/login", "POST /api/auth/password/forgot",
            "POST /api/auth/password/reset", "POST /api/auth/email/verify", "POST /api/auth/email/resend",
            "GET /api/products", "GET /api/products/{productId}");

    private record Operation(String verb, String path, JsonNode node) {
        String key() {
            return verb.toUpperCase() + " " + path;
        }
    }

    private JsonNode document() throws Exception {
        return Json.mapper().readTree(send(HttpMethod.GET, "/v3/api-docs", null, null, 200));
    }

    private static List<Operation> operations(JsonNode doc) {
        List<Operation> list = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> paths = doc.get("paths").fields();
        while (paths.hasNext()) {
            Map.Entry<String, JsonNode> path = paths.next();
            for (String verb : VERBS) {
                if (path.getValue().has(verb)) {
                    list.add(new Operation(verb, path.getKey(), path.getValue().get(verb)));
                }
            }
        }
        return list;
    }

    @Test
    void theDocumentDeclaresTheApiAndTheBearerSchemeThatEnablesTheAuthorizeButton() throws Exception {
        JsonNode doc = document();
        assertThat(doc.at("/info/title").asText()).isEqualTo("NexusMarket API");
        assertThat(doc.at("/info/description").asText()).contains("Authorize").contains("simulados");
        assertThat(doc.at("/servers/0/url").asText()).isEqualTo("http://localhost:8080");

        JsonNode scheme = doc.at("/components/securitySchemes/bearerAuth");
        assertThat(scheme.get("type").asText()).isEqualTo("http");
        assertThat(scheme.get("scheme").asText()).isEqualTo("bearer");
        assertThat(scheme.get("bearerFormat").asText()).isEqualTo("JWT");
        assertThat(doc.at("/security/0").has("bearerAuth")).isTrue();
    }

    @Test
    void everyOperationHasASummary_theRolesThatCanUseIt_andExactlyOneTag() throws Exception {
        List<Operation> operations = operations(document());

        assertThat(operations).hasSizeGreaterThanOrEqualTo(61);
        for (Operation op : operations) {
            assertThat(op.node().path("summary").asText()).as("resumen de %s", op.key()).isNotBlank();
            assertThat(op.node().path("description").asText()).as("descripcion de %s", op.key()).isNotBlank();
            assertThat(op.node().path("tags")).as("etiquetas de %s", op.key()).hasSize(1);
        }
        assertThat(operations).extracting(Operation::key)
                .contains("POST /api/auth/register/staff", "POST /api/auth/login", "POST /api/orders/{orderId}/payment")
                // reserve y release existen en el codigo pero la seguridad los deniega: no se documentan.
                .doesNotContain("POST /api/inventory/{inventoryId}/reserve", "POST /api/inventory/{inventoryId}/release");
    }

    @Test
    void whatTheDocumentSaysIsPublicOrProtected_matchesTheRealSecurity() throws Exception {
        for (Operation op : operations(document())) {
            boolean documentedPublic = op.node().has("security") && op.node().get("security").isEmpty();
            assertThat(documentedPublic).as("%s documentado como publico", op.key()).isEqualTo(PUBLIC.contains(op.key()));

            // La seguridad real, sin token: una operacion protegida responde 401; una publica nunca.
            String path = op.path().replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
            String body = op.node().has("requestBody") ? "{}" : null;
            MvcResult result = perform(HttpMethod.valueOf(op.verb().toUpperCase()), path, null, body);
            int status = result.getResponse().getStatus();
            if (documentedPublic) {
                assertThat(status).as("%s es publico: no debe pedir token", op.key()).isNotIn(401, 403);
            } else {
                assertThat(status).as("%s es protegido: sin token debe dar 401", op.key()).isEqualTo(401);
            }
        }
    }

    @Test
    void protectedOperationsDocumentTheirErrorResponses_withTheApiErrorFormat() throws Exception {
        JsonNode doc = document();
        JsonNode error = doc.at("/components/schemas/ErrorResponse");
        assertThat(error.at("/properties").has("code")).isTrue();
        assertThat(error.at("/properties").has("message")).isTrue();
        assertThat(error.at("/properties").has("timestamp")).isTrue();

        for (Operation op : operations(doc)) {
            if (!PUBLIC.contains(op.key())) {
                assertThat(op.node().at("/responses").has("401")).as("401 de %s", op.key()).isTrue();
                assertThat(op.node().at("/responses").has("403")).as("403 de %s", op.key()).isTrue();
                assertThat(op.node().at("/responses/401/content/application~1json/schema/$ref").asText())
                        .endsWith("/ErrorResponse");
            }
            if (op.node().has("requestBody")) {
                assertThat(op.node().at("/responses").has("400")).as("400 de %s", op.key()).isTrue();
            }
        }
    }

    @Test
    void theRequestBodiesCarryExamples_theStaffRoleIsRestricted_andPasswordsNeverShowARealValue() throws Exception {
        JsonNode schemas = document().at("/components/schemas");

        JsonNode staff = schemas.get("RegisterStaffRequest").get("properties");
        assertThat(staff.at("/role/enum")).extracting(JsonNode::asText).containsExactly("LOGISTICS_OPERATOR", "SUPERVISOR");
        assertThat(staff.at("/email/example").asText()).contains("@");
        assertThat(staff.at("/password/format").asText()).isEqualTo("password");
        assertThat(staff.at("/password/writeOnly").asBoolean()).isTrue();
        assertThat(staff.at("/password/example").asText()).startsWith("<").endsWith(">");

        assertThat(schemas.at("/LoginRequest/properties/password/format").asText()).isEqualTo("password");
        assertThat(schemas.at("/CreateReturnRequest/properties/items").isMissingNode()).isFalse();
        assertThat(schemas.at("/ReturnLineRequest/properties/reason/enum"))
                .extracting(JsonNode::asText).containsExactly("DAMAGED", "WRONG_ITEM", "NOT_AS_DESCRIBED", "OTHER");
    }

    /** Operaciones que en el código no responden 200 (devuelven CREATED o ACCEPTED). */
    private static final Map<String, String> NOT_200 = Map.ofEntries(
            Map.entry("POST /api/auth/register/buyer", "201"), Map.entry("POST /api/auth/register/seller", "201"),
            Map.entry("POST /api/auth/register/staff", "201"), Map.entry("POST /api/auth/password/forgot", "202"),
            Map.entry("POST /api/auth/email/resend", "202"), Map.entry("POST /api/inventory", "201"),
            Map.entry("POST /api/orders/{orderId}/invoice", "201"), Map.entry("POST /api/buyers/{buyerId}/orders", "201"),
            Map.entry("POST /api/products", "201"), Map.entry("POST /api/orders/{orderId}/returns", "201"),
            Map.entry("POST /api/orders/{orderId}/shippings", "201"), Map.entry("POST /api/warehouses/marketplace", "201"),
            Map.entry("POST /api/sellers/{sellerId}/warehouses", "201"),
            Map.entry("POST /api/logistics/orders/{reference}/incidents", "201"));

    @Test
    void theDocumentedSuccessStatus_isTheRealOne() throws Exception {
        for (Operation op : operations(document())) {
            String expected = NOT_200.getOrDefault(op.key(), "200");
            assertThat(op.node().at("/responses").has(expected)).as("%s documenta %s", op.key(), expected).isTrue();
            if (!"200".equals(expected)) {
                assertThat(op.node().at("/responses").has("200")).as("%s no debe documentar 200", op.key()).isFalse();
            }
        }
        // Lo documentado coincide con lo que la API responde de verdad.
        send(HttpMethod.POST, "/api/auth/password/forgot", null, body("email", "nadie-" + uniq() + "@mail.test"), 202);
        send(HttpMethod.POST, "/api/auth/email/resend", null, body("email", "nadie-" + uniq() + "@mail.test"), 202);
        registerBuyer(); // el registro responde 201 (lo exige el propio helper)
    }

    @Test
    void swaggerUiIsServedInDevelopment() throws Exception {
        MvcResult page = perform(HttpMethod.GET, "/swagger-ui/index.html", null, null);
        assertThat(page.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void thePublishedOpenApiFile_isExactlyTheOneTheApplicationGenerates() throws Exception {
        JsonNode generated = document();
        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(PUBLISHED.getParent());
            Files.writeString(PUBLISHED, Json.pretty(generated) + "\n", StandardCharsets.UTF_8);
            return;
        }
        assertThat(PUBLISHED).as("falta %s: genérelo con -Dopenapi.update=true", PUBLISHED).exists();
        JsonNode published = readPublished();
        assertThat(generated).as("docs/api/openapi.json está desactualizado: regenérelo con "
                + "./mvnw test -Dtest=OpenApiDocumentationIntegrationTest -Dopenapi.update=true").isEqualTo(published);
    }

    private static JsonNode readPublished() throws IOException {
        return Json.mapper().readTree(Files.readString(PUBLISHED, StandardCharsets.UTF_8));
    }
}
