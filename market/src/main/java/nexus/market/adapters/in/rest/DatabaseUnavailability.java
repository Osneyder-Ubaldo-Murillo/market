package nexus.market.adapters.in.rest;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;

import org.hibernate.exception.JDBCConnectionException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/**
 * Clasifica un fallo como <b>indisponibilidad de la base de datos</b> (no se pudo obtener o mantener una
 * conexión), para responder 503 en lugar de 500.
 *
 * <p>Es deliberadamente estrecho: solo reconoce causas de CONECTIVIDAD en la cadena de causas. No clasifica como
 * indisponibilidad las excepciones de programación (SQL mal formado, {@code NullPointerException}...), las
 * violaciones de integridad (SQLState 23), los errores de validación o de negocio, ni los tiempos de espera de
 * bloqueo o deadlocks (esos son 409, ver {@code GlobalExceptionHandler.isLockConflict}). Tampoco los errores de
 * autenticación contra MySQL (SQLState 28), que son de configuración.</p>
 *
 * <p>Reconoce: {@code SQLTransientConnectionException} (Hikari: "Connection is not available, request timed
 * out"), {@code SQLNonTransientConnectionException}, {@code SQLRecoverableException} (p. ej. "Communications
 * link failure"), cualquier {@code SQLException} con SQLState de la clase 08 (excepción de conexión),
 * {@code JDBCConnectionException} de Hibernate y {@code CannotGetJdbcConnectionException} de Spring; y los
 * errores de red ({@code ConnectException}, {@code UnknownHostException}, {@code NoRouteToHostException},
 * {@code SocketTimeoutException}) solo cuando cuelgan de una {@code SQLException} (para no confundir una caída de
 * otro servicio con la de la base de datos).</p>
 *
 * <p>Limitación: el tiempo de espera de una sentencia ya conectada ({@code SQLTimeoutException}) no se considera
 * indisponibilidad.</p>
 */
public final class DatabaseUnavailability {

    private DatabaseUnavailability() {
    }

    public static boolean isUnavailable(Throwable error) {
        boolean underSqlException = false;
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLTransientConnectionException
                    || t instanceof SQLNonTransientConnectionException
                    || t instanceof SQLRecoverableException
                    || t instanceof JDBCConnectionException
                    || t instanceof CannotGetJdbcConnectionException) {
                return true;
            }
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.startsWith("08")) {
                    return true;
                }
                underSqlException = true;
            }
            if (underSqlException && (t instanceof ConnectException || t instanceof UnknownHostException
                    || t instanceof NoRouteToHostException || t instanceof SocketTimeoutException)) {
                return true;
            }
        }
        return false;
    }

    /** Cadena de nombres de clase de las causas (sin mensajes: pueden contener hosts o parámetros). */
    public static String causeChain(Throwable error) {
        StringBuilder chain = new StringBuilder();
        for (Throwable t = error; t != null && chain.length() < 400; t = t.getCause() == t ? null : t.getCause()) {
            if (chain.length() > 0) {
                chain.append(" <- ");
            }
            chain.append(t.getClass().getSimpleName());
        }
        return chain.toString();
    }
}
