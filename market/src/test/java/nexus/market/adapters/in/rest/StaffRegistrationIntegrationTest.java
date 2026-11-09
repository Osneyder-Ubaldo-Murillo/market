package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import nexus.market.adapters.in.rest.requests.AuthRequests.RegisterStaffRequest;
import nexus.market.domain.valueobjects.SystemRole;

/**
 * Sin un camino para crear un operador logístico, un pedido pagado no podía despacharse ni entregarse (esas acciones exigen
 * ese rol) y se quedaba en PAID para siempre en un despliegue real. El administrador da de alta personal interno con
 * {@code POST /api/auth/register/staff}: solo operador logístico o supervisor, con el mismo flujo de correo verificado que
 * el resto de cuentas.
 */
class StaffRegistrationIntegrationTest extends RestTestSupport {

    private String registerStaff(String token, String role, int expected, String email, String documentId)
            throws Exception {
        return send(HttpMethod.POST, "/api/auth/register/staff", token,
                body("fullName", "Personal " + uniq(), "email", email, "documentId", documentId,
                        "password", PASSWORD, "role", role), expected);
    }

    private String loginToken(String email) throws Exception {
        return read(send(HttpMethod.POST, "/api/auth/login", null,
                body("email", email, "password", PASSWORD), 200), "$.token");
    }

    /** Crea un operador con la API, verifica su correo (como lo haría el código recibido) y devuelve su token. */
    private String operatorToken(String adminToken) throws Exception {
        String email = "operador-" + uniq() + "@mail.test";
        String created = registerStaff(adminToken, "LOGISTICS_OPERATOR", 201, email, "OP-" + uniq());
        verifyEmail(read(created, "$.userId"));
        return loginToken(email);
    }

    @Test
    void anAdminCreatesAnOperator_whoMustVerifyTheEmailBeforeLoggingIn() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        String email = "operador-" + uniq() + "@mail.test";

        String created = registerStaff(admin, "LOGISTICS_OPERATOR", 201, email, "OP-" + uniq());
        assertThat(read(created, "$.role")).isEqualTo("LOGISTICS_OPERATOR");
        assertThat(read(created, "$.status")).isEqualTo("ACTIVE");
        assertThat(created).doesNotContainIgnoringCase("password").doesNotContain(PASSWORD);

        // Sin verificar el correo no entra, igual que un comprador o un vendedor.
        String blocked = send(HttpMethod.POST, "/api/auth/login", null, body("email", email, "password", PASSWORD), 403);
        assertThat(read(blocked, "$.code")).isEqualTo("EMAIL_NOT_VERIFIED");

        verifyEmail(read(created, "$.userId"));
        String logged = send(HttpMethod.POST, "/api/auth/login", null, body("email", email, "password", PASSWORD), 200);
        assertThat(read(logged, "$.role")).isEqualTo("LOGISTICS_OPERATOR");
    }

    @Test
    void anOperatorCreatedThroughTheApi_takesAPaidOrderAllTheWayToDelivered() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        String operator = operatorToken(admin);
        SellerCatalog catalog = seedSellerCatalog(admin, new BigDecimal("1500.00"));
        Account buyer = registerBuyer();

        send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                body("productId", catalog.productId(), "quantity", 2), 200);
        String orderId = read(send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders", buyer.token(),
                body("shippingAddress", addressJson()), 201), "$.orderId");
        send(HttpMethod.POST, "/api/orders/" + orderId + "/payment", buyer.token(), body("paymentMethod", "CARD"), 200);

        // Quienes NO son operador siguen sin poder despachar: el rol no se relaja.
        String dispatch = body("warehouseId", catalog.warehouseId());
        send(HttpMethod.POST, "/api/orders/" + orderId + "/dispatch", catalog.seller().token(), dispatch, 403);
        send(HttpMethod.POST, "/api/orders/" + orderId + "/dispatch", admin, dispatch, 403);
        send(HttpMethod.POST, "/api/orders/" + orderId + "/dispatch", buyer.token(), dispatch, 403);

        assignAndPick(admin, operator, orderId);
        send(HttpMethod.POST, "/api/orders/" + orderId + "/dispatch", operator, dispatch, 200);
        String shippingId = read(send(HttpMethod.GET, "/api/orders/" + orderId + "/shipping", operator, null, 200),
                "$.shippingId");
        send(HttpMethod.POST, "/api/shippings/" + shippingId + "/dispatch", operator,
                body("trackingNumber", "TRK-" + uniq(), "carrier", "TCC"), 200);
        send(HttpMethod.POST, "/api/shippings/" + shippingId + "/in-transit", operator, "{}", 200);
        send(HttpMethod.POST, "/api/shippings/" + shippingId + "/deliver", operator, "{}", 200);

        String order = send(HttpMethod.GET, "/api/orders/" + orderId, buyer.token(), null, 200);
        assertThat(read(order, "$.status")).isEqualTo("DELIVERED");
    }

    @Test
    void aSupervisorCanBeCreated_andIsReadOnly() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        String email = "supervisor-" + uniq() + "@mail.test";
        String created = registerStaff(admin, "SUPERVISOR", 201, email, "SUP-" + uniq());
        assertThat(read(created, "$.role")).isEqualTo("SUPERVISOR");
        verifyEmail(read(created, "$.userId"));
        String supervisor = loginToken(email);

        send(HttpMethod.GET, "/api/users/" + read(created, "$.userId"), supervisor, null, 200);
        send(HttpMethod.POST, "/api/orders/" + UUID.randomUUID() + "/dispatch", supervisor,
                body("warehouseId", UUID.randomUUID().toString()), 403);
    }

    @Test
    void onlyAnAdministratorCanCreateStaff() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        String payload = body("fullName", "Intruso", "email", "intruso-" + uniq() + "@mail.test",
                "documentId", "INT-" + uniq(), "password", PASSWORD, "role", "LOGISTICS_OPERATOR");

        send(HttpMethod.POST, "/api/auth/register/staff", null, payload, 401);
        send(HttpMethod.POST, "/api/auth/register/staff", registerBuyer().token(), payload, 403);
        send(HttpMethod.POST, "/api/auth/register/staff", registerApprovedSeller(admin).token(), payload, 403);
        send(HttpMethod.POST, "/api/auth/register/staff", staffToken(SystemRole.LOGISTICS_OPERATOR), payload, 403);
        send(HttpMethod.POST, "/api/auth/register/staff", staffToken(SystemRole.SUPERVISOR), payload, 403);
    }

    @Test
    void onlyOperatorAndSupervisorRolesAreAccepted_neverAdminOrAnythingElse() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        for (String role : new String[] { "ADMIN", "BUYER", "SELLER", "supervisor", "ROLE_ADMIN", "X", "" }) {
            String error = registerStaff(admin, role, 400, "rol-" + uniq() + "@mail.test", "R-" + uniq());
            assertThat(read(error, "$.code")).as("rol [%s]", role).isEqualTo("VALIDATION_ERROR");
        }
        send(HttpMethod.POST, "/api/auth/register/staff", admin,
                body("fullName", "Sin rol", "email", "sinrol-" + uniq() + "@mail.test", "documentId", "SR-" + uniq(),
                        "password", PASSWORD), 400);
    }

    @Test
    void duplicatedEmailOrDocument_isRejectedWith409_andNothingIsOverwritten() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        String email = "dup-" + uniq() + "@mail.test";
        String document = "DUP-" + uniq();
        registerStaff(admin, "LOGISTICS_OPERATOR", 201, email, document);

        String sameEmail = registerStaff(admin, "LOGISTICS_OPERATOR", 409, email, "OTRO-" + uniq());
        assertThat(read(sameEmail, "$.code")).isEqualTo("USER_ALREADY_EXISTS");
        registerStaff(admin, "SUPERVISOR", 409, "otro-" + uniq() + "@mail.test", document);
    }

    @Test
    void aShortPasswordIsRejected_andThePasswordNeverReachesTheRequestToString() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        send(HttpMethod.POST, "/api/auth/register/staff", admin,
                body("fullName", "Clave corta", "email", "corta-" + uniq() + "@mail.test", "documentId", "CC-" + uniq(),
                        "password", "corta", "role", "LOGISTICS_OPERATOR"), 400);

        RegisterStaffRequest request = new RegisterStaffRequest("Ana", "ana@mail.test", "D-1", "clave-muy-secreta-123",
                "LOGISTICS_OPERATOR");
        assertThat(request.toString()).doesNotContain("clave-muy-secreta-123").contains("password=***");
    }
}
