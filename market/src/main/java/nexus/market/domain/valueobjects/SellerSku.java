package nexus.market.domain.valueobjects;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import nexus.market.domain.exceptions.BusinessException;

/**
 * Código comercial de un producto, definido por su vendedor (por ejemplo {@code TEC-001} o {@code PORTATIL-HP-15}). Es el
 * identificador legible del producto; el {@code ProductId} (UUID) sigue siendo la referencia técnica de inventario, carrito,
 * pedidos y facturas. Es único dentro del catálogo de un vendedor; dos vendedores pueden usar el mismo código.
 *
 * <p>Se normaliza a mayúsculas, de 1 a 40 caracteres: letras, dígitos, punto, guion y guion bajo (sin espacios), empezando y
 * terminando en letra o dígito.</p>
 */
public final class SellerSku {

    public static final int MAX_LENGTH = 40;

    private static final Pattern PATTERN = Pattern.compile("^[A-Z0-9](?:[A-Z0-9._-]*[A-Z0-9])?$");

    private final String value;

    private SellerSku(String value) {
        this.value = value;
    }

    public static SellerSku of(String raw) {
        String normalized = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > MAX_LENGTH || !PATTERN.matcher(normalized).matches()) {
            throw new BusinessException("INVALID_SELLER_SKU",
                    "El código del producto (sellerSku) debe tener de 1 a " + MAX_LENGTH + " caracteres: letras, dígitos, "
                            + "punto, guion o guion bajo, sin espacios, y empezar y terminar en letra o dígito.");
        }
        return new SellerSku(normalized);
    }

    /** Código automático para el vendedor que no define uno: {@code SKU-} y 8 caracteres hexadecimales. */
    public static SellerSku generate() {
        return new SellerSku("SKU-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8)
                .toUpperCase(Locale.ROOT));
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof SellerSku other && value.equals(other.value));
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
