package nexus.market.adapters.in.rest.requests;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import nexus.market.domain.enums.IncidentType;

/** DTOs de petición de la gestión logística y la supervisión de pedidos. */
public final class LogisticsRequests {

    private LogisticsRequests() {
    }

    public record RegisterPickupRequest(
            @NotBlank(message = "productId es obligatorio") @Schema(description = "Producto del pedido que se recogió") String productId,
            @Positive(message = "quantity debe ser mayor a cero") @Schema(description = "Unidades recogidas ahora (se acumulan)", example = "2") int quantity) {
    }

    public record ReportIncidentRequest(
            @Schema(description = "Producto relacionado (opcional)") String productId,
            @NotNull(message = "type es obligatorio") @Schema(description = "Tipo de novedad", example = "MISSING_PRODUCT") IncidentType type,
            @NotBlank(message = "description es obligatoria") @Size(max = 1000, message = "description admite hasta 1000 caracteres")
            @Schema(description = "Qué ocurrió", example = "El vendedor aún no entrega el producto") String description) {
    }

    public record AssignOperatorRequest(
            @NotBlank(message = "operator es obligatorio") @Size(max = 100)
            @Schema(description = "Operador logístico: correo, documento o UUID de la cuenta", example = "operador@demo.test") String operator) {
    }

    public record InstructionRequest(
            @NotBlank(message = "text es obligatorio") @Size(max = 1000, message = "text admite hasta 1000 caracteres")
            @Schema(description = "Instrucción u observación de seguimiento", example = "Contactar al vendedor B hoy") String text) {
    }

    public record ResponsibleRequest(
            @NotBlank(message = "responsible es obligatorio") @Size(max = 100)
            @Schema(description = "Responsable del seguimiento: correo, documento o UUID de la cuenta") String responsible) {
    }

    public record ResolveIncidentRequest(
            @NotBlank(message = "notes es obligatorio") @Size(max = 1000, message = "notes admite hasta 1000 caracteres")
            @Schema(description = "Observaciones de la solución", example = "El vendedor entregó el producto") String notes) {
    }

    public record DeliveryRequest(
            @Size(max = 500, message = "notes admite hasta 500 caracteres")
            @Schema(description = "Observaciones de la entrega (opcional)", example = "Recibió el comprador") String notes) {
    }
}
