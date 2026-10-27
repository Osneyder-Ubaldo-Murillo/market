package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.Test;

import nexus.market.adapters.in.rest.requests.AuthRequests.ChangePasswordRequest;

/** La solicitud no expone las contraseñas en su representación textual y valida longitud en caracteres y bytes. */
class ChangePasswordRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void toString_neverShowsThePasswords() {
        ChangePasswordRequest request = new ChangePasswordRequest("la-actual-SECRETA-1", "la-nueva-SECRETA-2-Zq");
        assertThat(request.toString()).doesNotContain("SECRETA").doesNotContain("la-actual").doesNotContain("la-nueva")
                .contains("***");
        assertThat(String.valueOf(request)).doesNotContain("SECRETA");
        assertThat("" + request).doesNotContain("SECRETA");
    }

    @Test
    void validation_rejectsBlankShortAndOverlongInBytes() {
        assertThat(validator.validate(new ChangePasswordRequest("actual-1", "nueva-valida-123Zq"))).isEmpty();
        assertThat(validator.validate(new ChangePasswordRequest("", "nueva-valida-123Zq"))).isNotEmpty();
        assertThat(validator.validate(new ChangePasswordRequest("actual-1", "corta-1Aa!"))).isNotEmpty();
        assertThat(validator.validate(new ChangePasswordRequest("actual-1", "ñ".repeat(37)))).isNotEmpty(); // 74 bytes
        assertThat(validator.validate(new ChangePasswordRequest("a".repeat(73), "nueva-valida-123Zq"))).isNotEmpty();
    }

    @Test
    void theRecordHasNoUserIdComponent() {
        assertThat(ChangePasswordRequest.class.getRecordComponents()).extracting(c -> c.getName())
                .containsExactly("currentPassword", "newPassword");
    }
}
