import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Crea una base H2 SINTÉTICA con el esquema real de Dependency-Check 12.2.2 (data/initialize.sql del jar core) y
 * los marcadores que se le indiquen. Solo sirve para probar la lógica del validador; NO contiene datos de NVD ni
 * demuestra nada sobre una base real.
 *
 * Uso: java -cp h2.jar:core.jar NvdFixtureBuilder.java DIR VERSION CHECKED MODIFIED VULNS CPES KEV
 *   VERSION/CHECKED/MODIFIED: el valor literal de la propiedad, o ABSENT para no escribirla.
 *   VULNS/CPES/KEV: número de filas sintéticas (CVE-0000-0001...) a insertar en cada tabla.
 */
public class NvdFixtureBuilder {

    public static void main(String[] args) throws Exception {
        if (args.length != 7) {
            System.err.println("Uso: NvdFixtureBuilder DIR VERSION CHECKED MODIFIED VULNS CPES KEV");
            System.exit(2);
        }
        Path dir = Paths.get(args[0]);
        Files.createDirectories(dir);
        Properties defaults = new Properties();
        try (InputStream in = NvdFixtureBuilder.class.getClassLoader().getResourceAsStream("dependencycheck.properties")) {
            if (in == null) {
                throw new IOException("Falta dependencycheck.properties en el classpath");
            }
            defaults.load(in);
        }
        String url = "jdbc:h2:file:" + args[0] + "/odc";
        try (Connection c = DriverManager.getConnection(url, defaults.getProperty("data.user"),
                defaults.getProperty("data.password"));
             Statement s = c.createStatement()) {
            s.execute("RUNSCRIPT FROM 'classpath:data/initialize.sql'");
            s.execute("DELETE FROM properties");
            property(c, "version", args[1]);
            property(c, "nvd.api.last.checked", args[2]);
            property(c, "nvd.api.last.modified", args[3]);
            int vulns = Integer.parseInt(args[4]);
            int cpes = Integer.parseInt(args[5]);
            int kev = Integer.parseInt(args[6]);
            for (int i = 1; i <= vulns; i++) {
                s.execute(String.format("INSERT INTO vulnerability(cve, description) VALUES ('CVE-0000-%04d', 'sintetico')", i));
            }
            for (int i = 1; i <= cpes; i++) {
                s.execute(String.format("INSERT INTO cpeEntry(part, vendor, product) VALUES ('a', 'sintetico', 'p%d')", i));
            }
            for (int i = 1; i <= kev; i++) {
                s.execute(String.format("INSERT INTO knownExploited(cveID) VALUES ('CVE-0000-%04d')", i));
            }
            s.execute("SHUTDOWN");
        }
    }

    private static void property(Connection c, String id, String value) throws SQLException {
        if ("ABSENT".equals(value)) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO properties(id, `value`) VALUES (?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }
}
