package nexus.market.domain.valueobjects;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Base común para los identificadores UUID del dominio (UserId, ProductId,
 * OrderId, etc.). Package-private: es un detalle de implementación, no forma
 * parte del API público del dominio.
 *
 * <p>Garantiza que el valor sea un UUID válido, es inmutable y proporciona
 * {@code equals}/{@code hashCode} seguros por tipo (un {@code ProductId} nunca
 * es igual a un {@code UserId} aunque coincidan los valores).</p>
 */
abstract class AbstractIdentifier {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final String value;

    protected AbstractIdentifier(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("El identificador no puede ser nulo ni vacío");
        }
        String trimmed = value.trim();
        if (!UUID_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("El identificador debe ser un UUID válido: " + trimmed);
        }
        this.value = trimmed.toLowerCase();
    }

    protected AbstractIdentifier() {
        this(UUID.randomUUID().toString());
    }

    public String value() {
        return value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AbstractIdentifier that = (AbstractIdentifier) o;
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getClass(), value);
    }

    @Override
    public String toString() {
        return value;
    }
}