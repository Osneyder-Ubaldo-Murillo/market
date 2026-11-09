package nexus.market.adapters.in.rest.requests;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import nexus.market.domain.valueobjects.Address;
import nexus.market.domain.valueobjects.Money;
import nexus.market.domain.valueobjects.Quantity;

/**
 * DTOs de petición compartidos entre varios controladores (direcciones,
 * dinero y cantidades).
 */
public final class CommonRequests {

    private CommonRequests() {
    }

    /** Dirección postal completa (8 campos de {@link Address}). */
    public record AddressRequest(
            @NotBlank(message = "street es obligatorio") @Size(max = 120, message = "street no puede exceder 120 caracteres") @Schema(description = "Calle", example = "Calle 1") String street,
            @NotBlank(message = "number es obligatorio") @Size(max = 30, message = "number no puede exceder 30 caracteres") @Schema(description = "Número", example = "12-34") String number,
            @Size(max = 120, message = "complement no puede exceder 120 caracteres") @Schema(description = "Complemento (opcional)", example = "Piso 3") String complement,
            @NotBlank(message = "neighborhood es obligatorio") @Size(max = 120, message = "neighborhood no puede exceder 120 caracteres") @Schema(description = "Barrio", example = "Centro") String neighborhood,
            @NotBlank(message = "city es obligatorio") @Size(max = 80, message = "city no puede exceder 80 caracteres") @Schema(description = "Ciudad", example = "Bogota") String city,
            @NotBlank(message = "state es obligatorio") @Size(max = 80, message = "state no puede exceder 80 caracteres") @Schema(description = "Departamento o estado", example = "Cundinamarca") String state,
            @NotBlank(message = "postalCode es obligatorio") @Size(max = 10, message = "postalCode no puede exceder 10 caracteres") @Schema(description = "Código postal", example = "110111") String postalCode,
            @NotBlank(message = "country es obligatorio") @Size(max = 80) @Pattern(regexp = "^[A-Z]{2}$", message = "country debe ser un código ISO de 2 letras mayúsculas") @Schema(description = "País (código de 2 letras)", example = "CO") String country) {

        public Address toDomain() {
            return Address.of(street, number, complement, neighborhood, city, state, postalCode, country);
        }
    }

    /** Monto monetario con código de moneda ISO-4217 (p. ej. COP). */
    public record MoneyRequest(
            @NotNull(message = "amount es obligatorio") @Positive(message = "amount debe ser mayor a cero") @Schema(description = "Monto", example = "1500.00") BigDecimal amount,
            @NotBlank(message = "currency es obligatorio") @Size(min = 3, max = 3, message = "currency debe ser un código ISO de 3 letras") @Schema(description = "Moneda (ISO 4217)", example = "COP") String currency) {

        public Money toDomain() {
            return Money.of(amount, currency);
        }
    }

    /** Cantidad de unidades (entero no negativo). */
    public record QuantityRequest(@Positive(message = "quantity debe ser mayor a cero") @Schema(description = "Cantidad (mayor que cero)", example = "2") int quantity) {

        public Quantity toDomain() {
            return Quantity.of(quantity);
        }
    }
}
