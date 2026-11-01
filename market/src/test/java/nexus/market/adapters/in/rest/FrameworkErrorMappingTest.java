package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

import com.jayway.jsonpath.JsonPath;

import nexus.market.adapters.in.rest.responses.UserResponses.ErrorResponse;

/**
 * Un error del cliente detectado por el propio framework (Accept sin representación disponible, cabecera o parte
 * obligatoria ausente, validación de parámetros...) debe responder 4xx con el formato de la API, nunca 500. El 500 queda
 * para los errores inesperados reales, sin filtrar detalles internos.
 */
class FrameworkErrorMappingTest extends RestTestSupport {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static void assertApiError(ResponseEntity<ErrorResponse> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(code);
        assertThat(response.getBody().message()).isNotBlank();
        assertThat(response.getBody().timestamp()).isNotNull();
    }

    @Test
    void acceptHeaderWithoutAnAvailableRepresentation_is406_inApiFormat_throughTheWholeStack() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/products").accept(MediaType.APPLICATION_XML)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(406);
        String body = result.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.code")).isEqualTo("NOT_ACCEPTABLE");
        assertThat((String) JsonPath.read(body, "$.message")).isNotBlank();
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    }

    @Test
    void missingRequestPartOrBindingValue_is400_withoutLeakingTheFrameworkMessage() {
        ResponseEntity<ErrorResponse> binding = handler
                .handleUnexpected(new ServletRequestBindingException("Missing header 'X-Interno-12345'"));
        assertApiError(binding, HttpStatus.BAD_REQUEST, "BAD_REQUEST");
        assertThat(binding.getBody().message()).doesNotContain("X-Interno-12345");

        ResponseEntity<ErrorResponse> part = handler
                .handleUnexpected(new MissingServletRequestPartException("archivo-interno"));
        assertApiError(part, HttpStatus.BAD_REQUEST, "BAD_REQUEST");
        assertThat(part.getBody().message()).doesNotContain("archivo-interno");
    }

    @Test
    void methodParameterValidationFailure_is400() {
        // Su constructor exige resultados de validación reales; aquí solo importa cómo se mapea su estado (400).
        HandlerMethodValidationException failure = mock(HandlerMethodValidationException.class);
        when(failure.getStatusCode()).thenReturn(HttpStatus.BAD_REQUEST);
        when(failure.getHeaders()).thenReturn(HttpHeaders.EMPTY);
        ResponseEntity<ErrorResponse> response = handler.handleUnexpected(failure);
        assertApiError(response, HttpStatus.BAD_REQUEST, "BAD_REQUEST");
    }

    @Test
    void notAcceptable_is406_andKeepsTheFrameworkHeaders() {
        ResponseEntity<ErrorResponse> response = handler
                .handleUnexpected(new HttpMediaTypeNotAcceptableException("detalle interno"));
        assertApiError(response, HttpStatus.NOT_ACCEPTABLE, "NOT_ACCEPTABLE");
        assertThat(response.getBody().message()).doesNotContain("detalle interno");
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    }

    @Test
    void otherClientStatusesFromTheFramework_keepTheirOwnStatus_withAGenericMessage() {
        ResponseEntity<ErrorResponse> response = handler
                .handleUnexpected(new ResponseStatusException(HttpStatus.CONFLICT, "razon interna: tabla orders"));
        assertApiError(response, HttpStatus.CONFLICT, "REQUEST_ERROR");
        assertThat(response.getBody().message()).doesNotContain("orders").doesNotContain("razon");
    }

    @Test
    void serverSideStatusesFromTheFramework_stayAsAGeneric500() {
        ResponseEntity<ErrorResponse> response = handler
                .handleUnexpected(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "fallo aguas arriba: 10.0.0.7"));
        assertApiError(response, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        assertThat(response.getBody().message()).doesNotContain("10.0.0.7");
    }

    @Test
    void unexpectedErrors_remain500_withoutDetails() {
        ResponseEntity<ErrorResponse> response = handler
                .handleUnexpected(new IllegalStateException("SELECT * FROM users WHERE password = 'x'"));
        assertApiError(response, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        assertThat(response.getBody().message()).doesNotContain("SELECT").doesNotContain("password");
        assertThat(response.getHeaders().containsHeader(HttpHeaders.RETRY_AFTER)).isFalse();
    }
}
