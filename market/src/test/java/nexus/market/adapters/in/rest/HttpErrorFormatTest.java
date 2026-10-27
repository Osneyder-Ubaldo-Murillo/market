package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jayway.jsonpath.JsonPath;

import nexus.market.domain.valueobjects.SystemRole;

/**
 * Códigos HTTP coherentes (400, 401, 403, 404, 405, 415) con el formato de error de la API
 * ({@code code}, {@code message}, {@code timestamp}).
 */
class HttpErrorFormatTest extends RestTestSupport {

    private static void assertApiError(String body, String code) {
        assertThat((String) JsonPath.read(body, "$.code")).isEqualTo(code);
        assertThat((String) JsonPath.read(body, "$.message")).isNotBlank();
        assertThat((String) JsonPath.read(body, "$.timestamp")).isNotBlank();
    }

    @Test
    void malformedJson_is400_notFound500() throws Exception {
        String body = send(HttpMethod.POST, "/api/auth/login", null, "{\"email\": ", 400);
        assertApiError(body, "MALFORMED_REQUEST");
        assertThat(body).doesNotContain("Jackson").doesNotContain("Unexpected").doesNotContain("nexus.market");
    }

    @Test
    void unsupportedContentType_is415() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnsupportedMediaType())
                .andReturn().getResponse().getContentAsString();
        assertApiError(body, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void beanValidationAndValueObjects_are400() throws Exception {
        assertApiError(send(HttpMethod.POST, "/api/auth/login", null, "{}", 400), "VALIDATION_ERROR");
        String admin = staffToken(SystemRole.ADMIN);
        assertApiError(send(HttpMethod.GET, "/api/users/no-es-uuid", admin, null, 400), "VALIDATION_ERROR");
    }

    @Test
    void authenticationAndAuthorization_are401And403_inApiFormat() throws Exception {
        assertApiError(send(HttpMethod.GET, "/api/orders/" + UUID.randomUUID(), null, null, 401), "UNAUTHORIZED");
        String buyer = registerBuyer().token();
        assertApiError(send(HttpMethod.POST, "/api/auth/register/seller", buyer, "{}", 403), "ACCESS_DENIED");
    }

    @Test
    void notFound_is404_forBusinessAndUnexposedRoutes() throws Exception {
        assertApiError(send(HttpMethod.GET, "/api/products/" + UUID.randomUUID(), null, null, 404),
                "PRODUCT_NOT_FOUND");
        // Ruta sin regla de seguridad: denegada (no revela qué rutas existen).
        assertApiError(send(HttpMethod.GET, "/api/no-existe", null, null, 401), "UNAUTHORIZED");
        assertApiError(send(HttpMethod.GET, "/api/no-existe", staffToken(SystemRole.ADMIN), null, 403),
                "ACCESS_DENIED");
    }

    @Test
    void wrongMethodOnKnownRoute_isDeniedBySecurityBeforeReachingTheController() throws Exception {
        // denyAll: un método sin regla da 401/403 (no 405), para no enumerar rutas ni métodos.
        assertApiError(send(HttpMethod.DELETE, "/api/products/" + UUID.randomUUID(), null, null, 401),
                "UNAUTHORIZED");
        assertApiError(send(HttpMethod.PUT, "/api/auth/login", registerBuyer().token(), "{}", 403),
                "ACCESS_DENIED");
    }

    @RestController
    static class OnlyGet {
        @GetMapping("/solo-get")
        String ok() {
            return "ok";
        }
    }

    @Test
    void methodNotAllowed_is405_withAllowHeader_inApiFormat() throws Exception {
        MockMvc standalone = MockMvcBuilders.standaloneSetup(new OnlyGet())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        String body = standalone.perform(put("/solo-get"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andReturn().getResponse().getContentAsString();
        assertApiError(body, "METHOD_NOT_ALLOWED");
    }
}
