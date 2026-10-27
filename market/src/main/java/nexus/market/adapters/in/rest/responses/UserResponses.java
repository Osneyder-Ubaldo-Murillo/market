package nexus.market.adapters.in.rest.responses;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import nexus.market.adapters.in.rest.requests.CommonRequests.AddressRequest;

/**
 * DTOs de respuesta compartidos (dinero, direcciones) y de identidad
 * (usuarios, compradores, vendedores, token).
 */
public final class UserResponses {

    private UserResponses() {
    }

    /** Monto monetario serializado como {amount, currency}. */
    public record MoneyResponse(BigDecimal amount, String currency) {
    }

    /** Dirección postal (8 campos). */
    public record AddressResponse(String street, String number, String complement,
                                  String neighborhood, String city, String state,
                                  String postalCode, String country) {

        public static AddressResponse of(nexus.market.domain.valueobjects.Address address) {
            return new AddressResponse(address.getStreet(), address.getNumber(), address.getComplement(),
                    address.getNeighborhood(), address.getCity(), address.getState(),
                    address.getPostalCode(), address.getCountry());
        }
    }

    /** Usuario con rol y estado. */
    public record UserResponse(String userId, String fullName, String email, String documentId,
                               String role, String status, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /** Perfil de comprador. */
    public record BuyerResponse(String buyerId, String userId, AddressResponse mainAddress,
                                List<AddressResponse> additionalAddresses, String commercialStatus) {
    }

    /** Perfil de vendedor. */
    public record SellerResponse(String sellerId, String userId, String businessName, String taxId,
                                 String status) {
    }

    /** Token emitido tras el login. */
    public record TokenResponse(String token, String userId, String role) {
    }

    /** Aviso de éxito sin cuerpo (registro, acciones). */
    public record MessageResponse(String message) {
    }

    /** Error de API: código de negocio + mensaje + timestamp. */
    public record ErrorResponse(String code, String message, LocalDateTime timestamp) {
    }

    /** Convierte un {@link AddressRequest} ya usado en dominio a respuesta. */
    public static AddressResponse fromDomain(nexus.market.domain.valueobjects.Address address) {
        return AddressResponse.of(address);
    }
}
