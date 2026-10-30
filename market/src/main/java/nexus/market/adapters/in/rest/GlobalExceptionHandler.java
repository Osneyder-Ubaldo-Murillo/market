package nexus.market.adapters.in.rest;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import nexus.market.adapters.in.rest.responses.UserResponses.ErrorResponse;
import nexus.market.application.TooManyAttemptsException;
import nexus.market.domain.exceptions.BusinessException;

/**
 * Manejador global de excepciones de la API REST: traduce las excepciones de
 * negocio del dominio a respuestas HTTP con cuerpo {@code ErrorResponse}
 * (código de negocio + mensaje + timestamp).
 *
 * <p>Mapeo de códigos: {@code *_NOT_FOUND} → 404; unicidad violada y
 * conflictos de estado → 409; credenciales/autorización → 401/403; el resto
 * de reglas de negocio → 400; conflicto de bloqueo/deadlock → 409; base de datos no disponible
 * (sin conexión) → 503; errores inesperados → 500.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Segundos sugeridos al cliente antes de reintentar tras un 503. */
    private static final String RETRY_AFTER_SECONDS = "5";

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(statusFor(ex.getCode()));
        if (ex instanceof TooManyAttemptsException tooMany) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(tooMany.retryAfterSeconds()));
        }
        return response.body(new ErrorResponse(ex.getCode(), ex.getMessage(), LocalDateTime.now()));
    }

    /** Mensaje genérico para un {@link IllegalArgumentException} que no nace en el código de NexusMarket. */
    static final String GENERIC_VALIDATION_MESSAGE = "Algún valor de la petición no es válido.";

    /**
     * Errores de validación de los Value Objects (lanzados al construir un VO
     * inválido desde un DTO) → 400. Solo se devuelve el mensaje si la excepción la lanzó código del propio proyecto
     * ({@code nexus.market.*}, mensajes escritos para el cliente); la de una librería o del JDK (p. ej. el texto de un
     * parser) puede contener detalles internos, así que se sustituye por un mensaje genérico y el detalle queda en el log.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        String message = ex.getMessage();
        if (!thrownByApplicationCode(ex) || message == null || message.isBlank()) {
            log.debug("IllegalArgumentException ajena al dominio; se responde con un mensaje genérico.", ex);
            message = GENERIC_VALIDATION_MESSAGE;
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", message, LocalDateTime.now()));
    }

    private static boolean thrownByApplicationCode(Throwable error) {
        StackTraceElement[] trace = error.getStackTrace();
        return trace.length > 0 && trace[0].getClassName().startsWith("nexus.market.");
    }

    /** Fallo de Bean Validation (@Valid) en el cuerpo de la petición → 400. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBeanValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(java.util.stream.Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", message, LocalDateTime.now()));
    }

    /** Propiedad del recurso no demostrada (AccessPolicy) → 403 con el formato de la API. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("ACCESS_DENIED",
                        "No tiene permiso para realizar esta operación.", LocalDateTime.now()));
    }

    /** Ruta inexistente (o endpoint no expuesto) para un usuario autorizado → 404, no 500. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", "Recurso inexistente.", LocalDateTime.now()));
    }

    /** JSON mal formado o cuerpo ilegible → 400 (sin exponer el detalle del parser). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "El cuerpo de la petición es inválido o ilegible.");
    }

    /** Parámetro/ID con tipo inválido o parámetro obligatorio ausente → 400. */
    @ExceptionHandler({ MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class })
    public ResponseEntity<ErrorResponse> handleBadParameter(Exception ex) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Parámetro de la petición inválido o ausente.");
    }

    /** Método no permitido para una ruta existente → 405 con la cabecera Allow. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (ex.getSupportedHttpMethods() != null && !ex.getSupportedHttpMethods().isEmpty()) {
            response.allow(ex.getSupportedHttpMethods().toArray(new org.springframework.http.HttpMethod[0]));
        }
        return response.body(new ErrorResponse("METHOD_NOT_ALLOWED",
                "Método no permitido para este recurso.", LocalDateTime.now()));
    }

    /** Content-Type no soportado → 415. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "Tipo de contenido no soportado; use application/json.");
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message, LocalDateTime.now()));
    }

    /** Cualquier error no previsto → 500 (con mensaje genérico). */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        if (isLockConflict(ex)) {
            // Conflicto de concurrencia identificable (bloqueo agotado o deadlock): reintentable, no un fallo interno.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("CONCURRENT_MODIFICATION",
                    "La operación entró en conflicto con otra en curso. Intente de nuevo.", LocalDateTime.now()));
        }
        if (DatabaseUnavailability.isUnavailable(ex)) {
            // La base de datos no está disponible (no hay conexión): 503 reintentable, sin detalles internos.
            log.warn("Base de datos no disponible; se responde 503 ({}).", DatabaseUnavailability.causeChain(ex));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                    .body(new ErrorResponse("SERVICE_UNAVAILABLE",
                            "Servicio temporalmente no disponible. Intente de nuevo más tarde.", LocalDateTime.now()));
        }
        // Error inesperado (de programación, de datos...): 500 genérico; el detalle va solo al log.
        log.error("Error no controlado; se responde 500.", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "Error interno del servidor.", LocalDateTime.now()));
    }

    /** Tiempo de espera de bloqueo o deadlock de la base de datos en la cadena de causas. */
    static boolean isLockConflict(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof jakarta.persistence.PessimisticLockException
                    || t instanceof jakarta.persistence.LockTimeoutException
                    || t instanceof org.hibernate.exception.LockAcquisitionException
                    || t instanceof org.hibernate.exception.LockTimeoutException) {
                return true;
            }
            if (t instanceof java.sql.SQLException sql
                    && (sql.getErrorCode() == 1205 || sql.getErrorCode() == 1213 || "40001".equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }

    private static HttpStatus statusFor(String code) {
        if (code == null) {
            return HttpStatus.BAD_REQUEST;
        }
        if (code.endsWith("NOT_FOUND")) {
            return HttpStatus.NOT_FOUND;
        }
        if (code.equals("USER_ALREADY_EXISTS")
                || code.equals("INVENTORY_ALREADY_EXISTS")
                || code.equals("RETURN_ALREADY_EXISTS")
                || code.equals("INVALID_STATE_TRANSITION")
                || code.equals("ADDRESS_LIMIT_EXCEEDED")) {
            return HttpStatus.CONFLICT;
        }
        if (code.equals(TooManyAttemptsException.CODE)) {
            return HttpStatus.TOO_MANY_REQUESTS;
        }
        if (code.equals("INVALID_CREDENTIALS")) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (code.equals("USER_NOT_ACTIVE") || code.equals("INACTIVE_BUYER") || code.equals("EMAIL_NOT_VERIFIED")) {
            return HttpStatus.FORBIDDEN;
        }
        return HttpStatus.BAD_REQUEST;
    }
}
