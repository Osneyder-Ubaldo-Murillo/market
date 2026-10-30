package nexus.market.adapters.in.rest;

/**
 * Parámetros de paginación de los listados: {@code page} desde 0 y {@code size} entre 1 y {@value #MAX_SIZE}. Un valor
 * fuera de rango se rechaza con 400 (nunca se recorta en silencio) para que un cliente no crea haber recibido todo.
 */
public record PageParams(int page, int size) {

    public static final int MAX_SIZE = 100;
    public static final String TOTAL_COUNT_HEADER = "X-Total-Count";

    public static PageParams of(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page no puede ser negativo.");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size debe estar entre 1 y " + MAX_SIZE + ".");
        }
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("page es demasiado grande.");
        }
        return new PageParams(page, size);
    }

    public static final int MAX_SEARCH_LENGTH = 100;

    /** Texto de búsqueda normalizado: sin espacios sobrantes; vacío equivale a "sin filtro" (null); máximo 100 caracteres. */
    public static String searchText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.length() > MAX_SEARCH_LENGTH) {
            throw new IllegalArgumentException("q no puede exceder " + MAX_SEARCH_LENGTH + " caracteres.");
        }
        return trimmed;
    }

    public int offset() {
        return page * size;
    }
}
