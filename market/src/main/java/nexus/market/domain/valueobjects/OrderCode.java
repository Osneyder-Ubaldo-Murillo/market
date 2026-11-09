package nexus.market.domain.valueobjects;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Código legible de un pedido ({@code NM-PED-2026-7K3F9Q}): año de creación y seis caracteres. Sirve para nombrar,
 * comunicar y buscar el pedido; no reemplaza al {@link OrderId} (clave primaria) y no concede ningún acceso por sí solo.
 * Los códigos nuevos usan un alfabeto sin caracteres ambiguos (sin 0, 1, O, I, L).
 */
public final class OrderCode {

    private static final Pattern FORMAT = Pattern.compile("^NM-PED-[0-9]{4}-[A-Z0-9]{6}$");
    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String value;

    private OrderCode(String value) {
        this.value = value;
    }

    public static OrderCode generate(LocalDate today) {
        StringBuilder suffix = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            suffix.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return new OrderCode("NM-PED-" + today.getYear() + "-" + suffix);
    }

    public static OrderCode of(String raw) {
        String normalized = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!FORMAT.matcher(normalized).matches()) {
            throw new IllegalArgumentException("El código de pedido debe tener el formato NM-PED-AAAA-XXXXXX.");
        }
        return new OrderCode(normalized);
    }

    /** {@code true} si el texto tiene la forma de un código de pedido (para distinguirlo de un UUID). */
    public static boolean looksLikeCode(String raw) {
        return raw != null && raw.trim().toUpperCase(Locale.ROOT).startsWith("NM-PED-");
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof OrderCode other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
