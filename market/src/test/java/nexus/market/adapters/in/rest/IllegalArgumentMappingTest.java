package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Currency;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import nexus.market.adapters.in.rest.responses.UserResponses.ErrorResponse;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.Money;

/**
 * Un {@code IllegalArgumentException} lanzado por el código del proyecto (value objects) conserva su mensaje, escrito
 * para el cliente; el lanzado por una librería o por el JDK se sustituye por un mensaje genérico para no filtrar
 * detalles internos.
 */
class IllegalArgumentMappingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void valueObjectMessage_isKept() {
        IllegalArgumentException error = null;
        try {
            Email.of("no-es-un-email");
        } catch (IllegalArgumentException e) {
            error = e;
        }
        assertThat(error).isNotNull();
        ResponseEntity<ErrorResponse> response = handler.handleIllegalArgument(error);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo(error.getMessage()).isNotEqualTo(
                GlobalExceptionHandler.GENERIC_VALIDATION_MESSAGE);
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void jdkMessage_isReplacedByAGenericOne() {
        IllegalArgumentException jdkError = null;
        try {
            Currency.getInstance("ZZZ-interno");
        } catch (IllegalArgumentException e) {
            jdkError = e;
        }
        assertThat(jdkError).isNotNull();
        ResponseEntity<ErrorResponse> response = handler.handleIllegalArgument(jdkError);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo(GlobalExceptionHandler.GENERIC_VALIDATION_MESSAGE);
    }

    @Test
    void invalidCurrencyThroughTheDomain_doesNotEchoTheJdkText() {
        IllegalArgumentException error = null;
        try {
            Money.of(1, "ZZZ-interno");
        } catch (IllegalArgumentException e) {
            error = e;
        }
        assertThat(error).isNotNull();
        assertThat(handler.handleIllegalArgument(error).getBody().message())
                .doesNotContain("ZZZ-interno");
    }

    @Test
    void exceptionWithoutMessage_getsTheGenericOne() {
        assertThat(handler.handleIllegalArgument(new IllegalArgumentException()).getBody().message())
                .isEqualTo(GlobalExceptionHandler.GENERIC_VALIDATION_MESSAGE);
    }
}
