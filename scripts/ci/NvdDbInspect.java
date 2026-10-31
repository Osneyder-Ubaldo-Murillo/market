import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Lee, en modo de solo lectura, los marcadores internos de una base de datos de Dependency-Check 12.2.2 (H2).
 * Lo usa validate-nvd-database.sh; no se debe ejecutar sobre la base original sino sobre una COPIA.
 *
 * Uso (modo "source launcher", sin compilar nada en disco):
 *   java -cp h2-2.4.240.jar:dependency-check-core-12.2.2.jar NvdDbInspect.java DIRECTORIO_CON_odc.mv.db
 *
 * Salida: líneas clave=valor en la salida estándar. No imprime nunca las credenciales de la base.
 * Códigos de salida: 0 = se pudo abrir y leer; 2 = entorno incorrecto (faltan recursos del classpath);
 * 3 = la base no se pudo abrir o leer (archivo truncado, corrupto, cabecera inválida...).
 *
 * Qué mira (todo verificado en el bytecode de 12.2.2, no en una base real):
 *  - properties.version (esquema, 5.6 en 12.2.2).
 *  - nvd.api.last.checked / nvd.api.last.modified: los guarda NvdApiDataSource.processApi AL FINAL de una actualización
 *    completa, con el formato uuuu-MM-dd'T'HH:mm:ssX (DatabaseProperties.save).
 *  - recuentos de vulnerability, cpeEntry y knownExploited. Son informativos: no existe un umbral demostrado de
 *    "base completa" (ver scripts/ci/NVD_VALIDATOR.md).
 */
public class NvdDbInspect {

    private static final DateTimeFormatter ODC_TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssX").withResolverStyle(ResolverStyle.STRICT);

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Uso: NvdDbInspect <directorio con odc.mv.db>");
            System.exit(2);
        }
        String user;
        String password;
        try (InputStream in = NvdDbInspect.class.getClassLoader().getResourceAsStream("dependencycheck.properties")) {
            if (in == null) {
                System.err.println("Falta dependencycheck.properties en el classpath (jar dependency-check-core).");
                System.exit(2);
                return;
            }
            Properties defaults = new Properties();
            defaults.load(in);
            user = defaults.getProperty("data.user");
            password = defaults.getProperty("data.password");
        } catch (IOException e) {
            System.err.println("No se pudo leer dependencycheck.properties: " + e.getClass().getSimpleName());
            System.exit(2);
            return;
        }
        if (user == null || password == null) {
            System.err.println("dependencycheck.properties no define data.user/data.password.");
            System.exit(2);
            return;
        }

        // ACCESS_MODE_DATA=r: solo lectura. IFEXISTS=TRUE: nunca crear una base vacía si la ruta es incorrecta.
        String url = "jdbc:h2:file:" + args[0] + "/odc;ACCESS_MODE_DATA=r;IFEXISTS=TRUE";
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            Map<String, String> properties = new HashMap<>();
            try (ResultSet rs = statement.executeQuery("SELECT * FROM properties")) {
                while (rs.next()) {
                    properties.put(rs.getString(1), rs.getString(2));
                }
            }
            out("db_open", "ok");
            out("schema_version", properties.getOrDefault("version", "ABSENT"));
            for (String key : new String[] {"nvd.api.last.checked", "nvd.api.last.modified"}) {
                String raw = properties.get(key);
                out(key, raw == null ? "ABSENT" : raw);
                out(key + ".epoch", raw == null ? "ABSENT" : toEpoch(raw));
            }
            out("kev.checked", properties.getOrDefault("kev.checked", "ABSENT"));
            out("kev.version", properties.getOrDefault("kev.version", "ABSENT"));
            for (String table : new String[] {"vulnerability", "cpeEntry", "knownExploited"}) {
                out("count." + table, count(statement, table));
            }
        } catch (SQLException e) {
            out("db_open", "error");
            out("db_error", e.getSQLState() + " " + firstLine(e.getMessage()));
            System.exit(3);
        }
    }

    private static String toEpoch(String raw) {
        try {
            return Long.toString(OffsetDateTime.parse(raw, ODC_TIMESTAMP).toEpochSecond());
        } catch (RuntimeException e) {
            return "INVALID";
        }
    }

    private static String count(Statement statement, String table) {
        try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? Long.toString(rs.getLong(1)) : "MISSING";
        } catch (SQLException e) {
            return "MISSING";
        }
    }

    private static void out(String key, String value) {
        // "\n" explícito: println usaría \r\n en Windows y el valor llegaría al script con un \r final.
        System.out.print(key + "=" + firstLine(value) + "\n");
    }

    /** Una sola línea, acotada: evita que un valor extraño de la base rompa el formato clave=valor. */
    private static String firstLine(String value) {
        if (value == null) {
            return "";
        }
        String line = value.split("[\\r\\n]", 2)[0];
        line = line.length() > 200 ? line.substring(0, 200) : line;
        // Solo ASCII imprimible: el mensaje de H2 para un archivo corrupto puede incluir bytes del propio archivo.
        return line.replaceAll("[^\\x20-\\x7E]", "?");
    }
}
