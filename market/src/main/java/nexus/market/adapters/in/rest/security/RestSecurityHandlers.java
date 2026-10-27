package nexus.market.adapters.in.rest.security;

import java.io.IOException;
import java.time.LocalDateTime;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Respuestas 401/403 generadas por la cadena de Spring Security, con el mismo
 * formato JSON que {@code UserResponses.ErrorResponse}
 * ({@code code}, {@code message}, {@code timestamp}).
 */
public class RestSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED",
                "Autenticación requerida.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        writeError(response, HttpServletResponse.SC_FORBIDDEN, "ACCESS_DENIED",
                "No tiene permiso para realizar esta operación.");
    }

    /** Escribe un cuerpo de error JSON; code y message son literales controlados por la aplicación. */
    public static void writeError(HttpServletResponse response, int status, String code,
                                  String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\""
                + message.replace("\"", "'") + "\",\"timestamp\":\"" + LocalDateTime.now() + "\"}");
    }
}
