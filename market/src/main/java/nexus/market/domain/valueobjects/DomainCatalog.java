package nexus.market.domain.valueobjects;

import java.util.Objects;

/**
 * Estructura común para todos los catálogos de negocio del dominio.
 *
 * <p>Los catálogos NO son {@code enum} de Java: son clases inmutables que
 * heredan de esta clase abstracta y exponen instancias predefinidas como
 * constantes estáticas (p. ej. {@code UserStatus.ACTIVE}).</p>
 */
public abstract class DomainCatalog {

    private final String code;
    private final String name;
    private final String description;

    protected DomainCatalog(String code, String name, String description) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code es obligatorio y no puede estar vacío");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name es obligatorio y no puede estar vacío");
        }
        this.code = code.trim();
        this.name = name.trim();
        this.description = (description == null) ? "" : description.trim();
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DomainCatalog that = (DomainCatalog) o;
        return code.equals(that.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getClass(), code);
    }

    @Override
    public String toString() {
        return name + " (" + code + ")";
    }
}