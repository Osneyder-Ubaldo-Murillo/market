package nexus.market.application;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Política de la contraseña del administrador inicial (más estricta que la del registro público, que
 * exige 8): mínimo 12 caracteres, máximo 72 bytes UTF-8 (límite de BCrypt; se rechaza, no se trunca), sin
 * valores de ejemplo ni contraseñas triviales ni el correo del propio administrador.
 *
 * <p>Los mensajes describen la regla incumplida; nunca incluyen la contraseña.</p>
 */
public final class AdminPasswordPolicy {

    public static final int MIN_CHARACTERS = 12;
    public static final int MAX_BYTES = 72;
    private static final int MIN_DISTINCT_CHARACTERS = 6;

    private static final List<String> WEAK_FRAGMENTS = List.of(
            "password", "contrasena", "contraseña", "changeme", "change-me", "nexusmarket", "qwerty",
            "letmein", "123456", "admin", "welcome", "secret");

    private AdminPasswordPolicy() {
    }

    /**
     * @param email correo del administrador (puede ser {@code null}); la contraseña no puede contenerlo
     * @throws IllegalArgumentException con la regla incumplida, sin la contraseña
     */
    public static void validate(String password, String email) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("la contraseña no está definida o está vacía");
        }
        if (password.codePointCount(0, password.length()) < MIN_CHARACTERS) {
            throw new IllegalArgumentException("la contraseña debe tener al menos " + MIN_CHARACTERS + " caracteres");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "la contraseña no puede superar " + MAX_BYTES + " bytes en UTF-8 (límite de BCrypt)");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        for (String weak : WEAK_FRAGMENTS) {
            if (lower.contains(weak)) {
                throw new IllegalArgumentException("la contraseña contiene un valor de ejemplo o predecible");
            }
        }
        if (password.chars().distinct().count() < MIN_DISTINCT_CHARACTERS) {
            throw new IllegalArgumentException("la contraseña tiene muy poca variedad de caracteres");
        }
        if (email != null && !email.isBlank()) {
            String normalizedEmail = email.toLowerCase(Locale.ROOT);
            String local = normalizedEmail.contains("@")
                    ? normalizedEmail.substring(0, normalizedEmail.indexOf('@')) : normalizedEmail;
            if (lower.contains(normalizedEmail) || (local.length() >= 3 && lower.contains(local))) {
                throw new IllegalArgumentException("la contraseña no puede contener el correo del administrador");
            }
        }
    }
}
