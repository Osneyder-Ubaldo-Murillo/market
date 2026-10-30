package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import com.jayway.jsonpath.JsonPath;

import nexus.market.domain.valueobjects.SystemRole;

/**
 * Los datos que no caben en el esquema se rechazan con 400 (formato de error de la API) ANTES de
 * escribir, y el estado final de la base de datos lo demuestra: ni usuarios, ni credenciales, ni
 * perfiles huérfanos.
 */
class RegistrationValidationIntegrationTest extends RestTestSupport {

    @Autowired
    private EntityManagerFactory emf;

    private long count(String table) {
        try (EntityManager em = emf.createEntityManager()) {
            return ((Number) em.createNativeQuery("select count(*) from " + table).getSingleResult()).longValue();
        }
    }

    private record Snapshot(long users, long credentials, long buyers, long sellers) {
    }

    private Snapshot snapshot() {
        return new Snapshot(count("users"), count("user_credentials"), count("buyers"), count("sellers"));
    }

    private static String address(String field, String value) {
        Map<String, String> fields = new java.util.LinkedHashMap<>(Map.of(
                "street", "Calle 1", "number", "12-34", "complement", "Piso 3", "neighborhood", "Centro",
                "city", "Bogota", "state", "Cundinamarca", "postalCode", "110111", "country", "CO"));
        fields.put(field, value);
        StringBuilder json = new StringBuilder("{");
        fields.forEach((k, v) -> json.append(json.length() > 1 ? "," : "").append('"').append(k).append("\":\"")
                .append(v).append('"'));
        return json.append('}').toString();
    }

    private String buyerBody(String email, String documentId, String password, String addressJson) {
        return body("fullName", "Comprador Prueba", "email", email, "documentId", documentId,
                "password", password, "address", addressJson);
    }

    private void assertRejectedWithoutWrites(String json, String path, String token, String expectedFieldHint) throws Exception {
        Snapshot before = snapshot();
        String response = send(HttpMethod.POST, path, token, json, 400);
        assertThat((String) JsonPath.read(response, "$.code")).isEqualTo("VALIDATION_ERROR");
        assertThat((String) JsonPath.read(response, "$.message")).contains(expectedFieldHint);
        assertThat(snapshot()).as("la base no debe cambiar").isEqualTo(before);
    }

    @Test
    void cityTooLong_is400_andLeavesNoOrphans() throws Exception {
        assertRejectedWithoutWrites(
                buyerBody("ciudad-" + uniq() + "@mail.test", "CC-" + uniq(), PASSWORD, address("city", "c".repeat(200))),
                "/api/auth/register/buyer", null, "city");
    }

    @Test
    void everyAddressField_overItsColumnLength_is400_withoutWrites() throws Exception {
        Map<String, Integer> limits = Map.of("street", 120, "number", 30, "complement", 120,
                "neighborhood", 120, "city", 80, "state", 80, "postalCode", 10);
        for (var limit : limits.entrySet()) {
            String tooLong = "9".repeat(limit.getValue() + 1);
            assertRejectedWithoutWrites(
                    buyerBody("campo-" + uniq() + "@mail.test", "CC-" + uniq(), PASSWORD,
                            address(limit.getKey(), tooLong)),
                    "/api/auth/register/buyer", null, limit.getKey());
        }
        // Exactamente en el límite: válido (no se rechaza de más).
        Snapshot before = snapshot();
        send(HttpMethod.POST, "/api/auth/register/buyer", null,
                buyerBody("limite-" + uniq() + "@mail.test", "CC-" + uniq(), PASSWORD,
                        address("city", "c".repeat(80))), 201);
        Snapshot after = snapshot();
        assertThat(after.users()).isEqualTo(before.users() + 1);
        assertThat(after.credentials()).isEqualTo(before.credentials() + 1);
        assertThat(after.buyers()).isEqualTo(before.buyers() + 1);
    }

    @Test
    void emailTooLong_is400_andCreatesNothing() throws Exception {
        String email = "a".repeat(151) + "@mail.test"; // 161 caracteres
        assertRejectedWithoutWrites(buyerBody(email, "CC-" + uniq(), PASSWORD, addressJson()),
                "/api/auth/register/buyer", null, "email");
    }

    @Test
    void businessNameTooLong_is400_andCreatesNothing() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        Snapshot before = snapshot();
        String json = body("fullName", "Vendedor Prueba", "email", "vend-" + uniq() + "@mail.test",
                "documentId", "NIT-" + uniq(), "password", PASSWORD,
                "businessName", "N".repeat(101), "taxId", "12345678");
        String response = send(HttpMethod.POST, "/api/auth/register/seller", admin, json, 400);
        assertThat((String) JsonPath.read(response, "$.message")).contains("businessName");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void passwordTooLongForBcrypt_is400_andCreatesNothing() throws Exception {
        assertRejectedWithoutWrites(
                buyerBody("clave-" + uniq() + "@mail.test", "CC-" + uniq(), "P".repeat(73), addressJson()),
                "/api/auth/register/buyer", null, "password");
    }

    @Test
    void nestedAddressInOrder_isValidated_notIgnored() throws Exception {
        Account buyer = registerBuyer();
        String response = send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders", buyer.token(),
                body("shippingAddress", address("city", "c".repeat(200))), 400);
        assertThat((String) JsonPath.read(response, "$.code")).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void validRegistration_stillReturns201_createsAllRows_andAllowsLogin() throws Exception {
        Snapshot before = snapshot();
        Account buyer = registerBuyer(); // 201 + login 200
        Snapshot after = snapshot();
        assertThat(after.users()).isEqualTo(before.users() + 1);
        assertThat(after.credentials()).isEqualTo(before.credentials() + 1);
        assertThat(after.buyers()).isEqualTo(before.buyers() + 1);
        send(HttpMethod.GET, "/api/users/" + buyer.userId(), buyer.token(), null, 200);
    }

    // ------------------------------------------------------------------
    // Contraseña: el límite de BCrypt se mide en bytes UTF-8
    // ------------------------------------------------------------------

    @Test
    void passwordOverBcryptLimitInBytes_isRejected_evenIfShortInCharacters() throws Exception {
        String multibyte = "ñ".repeat(37); // 37 caracteres, 74 bytes
        assertThat(multibyte.length()).isLessThan(72);
        assertRejectedWithoutWrites(
                buyerBody("bytes-" + uniq() + "@mail.test", "CC-" + uniq(), multibyte, addressJson()),
                "/api/auth/register/buyer", null, "password");
    }

    @Test
    void passwordOfExactly72Bytes_isAccepted_andTheWholePasswordIsUsed() throws Exception {
        String password = "ñ".repeat(35) + "abcd"; // 70 + 4 = 74? no: 35*2 + 2 = 72 bytes
        password = "ñ".repeat(35) + "ab";
        assertThat(password.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(72);
        String email = "exacto-" + uniq() + "@mail.test";
        String registered = send(HttpMethod.POST, "/api/auth/register/buyer", null,
                buyerBody(email, "CC-" + uniq(), password, addressJson()), 201);
        verifyEmail(read(registered, "$.userId")); // sin correo verificado no se puede iniciar sesión
        send(HttpMethod.POST, "/api/auth/login", null, body("email", email, "password", password), 200);
        // Sin truncamiento: cambiar el último carácter ya no autentica.
        send(HttpMethod.POST, "/api/auth/login", null,
                body("email", email, "password", password.substring(0, password.length() - 1) + "z"), 401);
    }

    @Test
    void shortPassword_stillFollowsTheExistingMinimumPolicy() throws Exception {
        assertRejectedWithoutWrites(
                buyerBody("corta-" + uniq() + "@mail.test", "CC-" + uniq(), "Ab1!xyz", addressJson()),
                "/api/auth/register/buyer", null, "password");
    }

    // ------------------------------------------------------------------
    // Producto: límites de la SDD (nombre 3-100, descripción 10-500)
    // ------------------------------------------------------------------

    @Test
    void productNameAndDescription_followTheSddLimits_withDomainAndApiAgreeing() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        Account seller = registerApprovedSeller(admin);
        java.util.function.BiFunction<String, String, Integer> create = (name, description) -> {
            try {
                return status(HttpMethod.POST, "/api/products", seller.token(),
                        body("sellerId", seller.profileId(), "name", name, "description", description,
                                "type", "DIGITAL", "price", moneyJson(new java.math.BigDecimal("10.00"))));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        assertThat(create.apply("n".repeat(100), "d".repeat(500))).isEqualTo(201);
        assertThat(create.apply("n".repeat(101), "d".repeat(20))).isEqualTo(400);
        assertThat(create.apply("Nombre valido", "d".repeat(501))).isEqualTo(400);
        assertThat(create.apply("ab", "d".repeat(20))).isEqualTo(400);
        assertThat(create.apply("Nombre valido", "corta")).isEqualTo(400);
    }
}
