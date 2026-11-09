package nexus.market.adapters.in.rest.requests;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import nexus.market.adapters.in.rest.requests.CommonRequests.MoneyRequest;

/**
 * DTOs de petición del catálogo de productos.
 */
public final class ProductRequests {

    private ProductRequests() {
    }

    /**
     * Creación de producto. {@code type} es {@code PHYSICAL} o {@code DIGITAL};
     * queda {@code INACTIVE} hasta publicarse.
     */
    public record CreateProductRequest(
            @NotBlank(message = "sellerId es obligatorio") @Schema(description = "Identificador del vendedor", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") String sellerId,
            @NotBlank(message = "name es obligatorio") @Size(max = 100, message = "name no puede exceder 100 caracteres") @Schema(description = "Nombre", example = "Producto de ejemplo") String name,
            @NotBlank(message = "description es obligatorio") @Size(max = 500, message = "description no puede exceder 500 caracteres") @Schema(description = "Descripción", example = "Descripción del producto") String description,
            @NotBlank(message = "type es obligatorio (PHYSICAL o DIGITAL)") @Schema(description = "Tipo de producto", example = "PHYSICAL") String type,
            @NotNull(message = "price es obligatorio") @Valid @Schema(description = "Precio") MoneyRequest price,
            @Pattern(regexp = SELLER_SKU_REGEX, message = SELLER_SKU_MESSAGE) @Schema(description = "Código comercial del producto, definido por el vendedor: único en su catálogo (otro vendedor puede usar el mismo). Opcional: si se omite, se genera uno automático (SKU-XXXXXXXX).", example = "TEC-001") String sellerSku) {
    }

    public static final String SELLER_SKU_REGEX = "^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,38}[A-Za-z0-9])?$";
    public static final String SELLER_SKU_MESSAGE = "sellerSku debe tener de 1 a 40 caracteres: letras, dígitos, punto, guion o guion bajo, sin espacios";

    /** Cambio de nombre y descripción (el tipo, el vendedor, el precio y el estado no se editan aquí). */
    public record UpdateProductRequest(
            @NotBlank(message = "name es obligatorio") @Size(max = 100, message = "name no puede exceder 100 caracteres") @Schema(description = "Nombre", example = "Producto de ejemplo") String name,
            @NotBlank(message = "description es obligatorio") @Size(max = 500, message = "description no puede exceder 500 caracteres") @Schema(description = "Descripción", example = "Descripción del producto") String description,
            @Pattern(regexp = SELLER_SKU_REGEX, message = SELLER_SKU_MESSAGE) @Schema(description = "Nuevo código comercial (opcional). Cambiarlo no afecta el inventario, los carritos ni los pedidos, que usan el productId.", example = "TEC-001") String sellerSku) {
    }

    /** Alta de una variante (color, talla, modelo...). */
    public record AddVariantRequest(
            @NotBlank(message = "variant es obligatorio") @Size(max = 50, message = "variant no puede exceder 50 caracteres") @Schema(description = "Variante: color, talla, modelo...", example = "Rojo - talla M") String variant) {
    }

    /** Actualización de precio. */
    public record UpdatePriceRequest(@NotNull(message = "price es obligatorio") @Valid @Schema(description = "Precio") MoneyRequest price) {
    }
}
