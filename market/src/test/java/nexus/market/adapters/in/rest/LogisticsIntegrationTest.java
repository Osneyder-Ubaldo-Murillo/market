package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import nexus.market.domain.valueobjects.SystemRole;

/**
 * Gestión logística de un pedido de tres vendedores: operador asignado, recogidas parciales por producto, novedades,
 * supervisión, historial y avance hasta la entrega; más el aislamiento entre operadores y la matriz de permisos.
 */
class LogisticsIntegrationTest extends RestTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private String admin;
    private String operator;
    private String otherOperator;
    private String supervisor;
    private SellerCatalog a; // Teclado, 2 unidades
    private SellerCatalog b; // Mouse, 3 unidades
    private SellerCatalog c; // Monitor, 1 unidad
    private Account buyer;
    private String orderId;
    private String orderCode;

    @BeforeEach
    void paidOrderOfThreeSellers() throws Exception {
        admin = staffToken(SystemRole.ADMIN);
        operator = staffToken(SystemRole.LOGISTICS_OPERATOR);
        otherOperator = staffToken(SystemRole.LOGISTICS_OPERATOR);
        supervisor = staffToken(SystemRole.SUPERVISOR);
        a = seedSellerCatalog(admin, new BigDecimal("50000"));
        b = seedSellerCatalog(admin, new BigDecimal("30000"));
        c = seedSellerCatalog(admin, new BigDecimal("400000"));
        buyer = registerBuyer();
        for (Object[] line : new Object[][] { { a, 2 }, { b, 3 }, { c, 1 } }) {
            send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                    body("productId", ((SellerCatalog) line[0]).productId(), "quantity", line[1]), 200);
        }
        JsonNode order = JSON.readTree(send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders",
                buyer.token(), body("shippingAddress", addressJson()), 201));
        orderId = order.get("orderId").asText();
        orderCode = order.get("orderCode").asText();
        send(HttpMethod.POST, "/api/orders/" + orderId + "/payment", buyer.token(), body("paymentMethod", "CARD"), 200);
    }

    // ------------------------------------------------------------------
    // Ayudas
    // ------------------------------------------------------------------

    private JsonNode get(String path, String token, int expected) throws Exception {
        return JSON.readTree(send(HttpMethod.GET, path, token, null, expected));
    }

    private JsonNode post(String path, String token, String json, int expected) throws Exception {
        return JSON.readTree(send(HttpMethod.POST, path, token, json, expected));
    }

    private String userId(String token) {
        return jwt.getUserId(token).orElseThrow().value();
    }

    private void assign(String operatorToken) throws Exception {
        send(HttpMethod.PUT, "/api/logistics/orders/" + orderId + "/operator", admin,
                body("operator", userId(operatorToken)), 200);
    }

    private JsonNode line(JsonNode detail, SellerCatalog catalog) {
        for (JsonNode line : detail.get("lines")) {
            if (catalog.productId().equals(line.get("productId").asText())) {
                return line;
            }
        }
        throw new AssertionError("la línea del producto no está en el pedido");
    }

    private JsonNode pick(String operatorToken, SellerCatalog catalog, int quantity, int expected) throws Exception {
        return post("/api/logistics/orders/" + orderId + "/pickups", operatorToken,
                body("productId", catalog.productId(), "quantity", quantity), expected);
    }

    private void pickEverything(String operatorToken) throws Exception {
        pick(operatorToken, a, 2, 200);
        pick(operatorToken, b, 3, 200);
        pick(operatorToken, c, 1, 200);
    }

    private String dispatchBody() {
        return body("warehouseId", c.warehouseId());
    }

    // ------------------------------------------------------------------
    // Escenario obligatorio: comprador, tres vendedores, operador y supervisor
    // ------------------------------------------------------------------

    @Test
    void threeSellersScenario_fromThePurchaseToTheDelivery() throws Exception {
        // El pedido tiene un código legible y el comprador lo ve con las cantidades correctas.
        assertThat(orderCode).matches("NM-PED-[0-9]{4}-[A-Z0-9]{6}");
        JsonNode purchased = get("/api/orders/" + orderId, buyer.token(), 200);
        assertThat(purchased.get("orderCode").asText()).isEqualTo(orderCode);
        assertThat(purchased.at("/total/amount").decimalValue()).isEqualByComparingTo("590000");

        // Sin operador asignado, ningún operador lo ve, pero el supervisor sí detecta que está sin asignar.
        assertThat(get("/api/logistics/orders", operator, 200)).isEmpty();
        JsonNode unassigned = get("/api/supervision/orders?unassigned=true&q=" + orderCode, supervisor, 200);
        assertThat(unassigned).hasSize(1);
        assertThat(unassigned.get(0).get("operator").isNull()).isTrue();

        // Un administrador asigna el operador; el operador encuentra el pedido en su módulo.
        assign(operator);
        JsonNode mine = get("/api/logistics/orders", operator, 200);
        assertThat(mine).hasSize(1);
        JsonNode summary = mine.get(0);
        assertThat(summary.get("orderCode").asText()).isEqualTo(orderCode);
        assertThat(summary.get("buyerName").asText()).startsWith("Comprador");
        assertThat(summary.get("productLines").asInt()).isEqualTo(3);
        assertThat(summary.get("totalUnits").asInt()).isEqualTo(6);
        assertThat(summary.get("sellers")).hasSize(3);
        assertThat(summary.get("productsSummary").asText()).contains("2 x ").contains("3 x ").contains("1 x ");
        assertThat(summary.get("logisticsStage").asText()).isEqualTo("PENDING_PICKUP");
        assertThat(summary.toString()).as("el listado no expone datos personales del comprador")
                .doesNotContain("@mail.test").doesNotContain("shippingAddress");

        // Detalle: tres productos con su código, vendedor y cantidades; la dirección de entrega es del operador.
        JsonNode detail = get("/api/logistics/orders/" + orderCode, operator, 200);
        assertThat(detail.get("lines")).hasSize(3);
        assertThat(line(detail, a).get("requested").asInt()).isEqualTo(2);
        assertThat(line(detail, b).get("requested").asInt()).isEqualTo(3);
        assertThat(line(detail, c).get("requested").asInt()).isEqualTo(1);
        assertThat(line(detail, b).get("sellerName").asText()).startsWith("Comercial");
        assertThat(line(detail, b).get("sellerSku").asText()).isNotBlank();
        assertThat(line(detail, b).get("status").asText()).isEqualTo("PENDING");
        assertThat(detail.get("shippingAddress").get("city").asText()).isNotBlank();

        // Recoge A (2) y C (1); B sigue pendiente: dos productos recogidos y uno pendiente, y también por unidades.
        pick(operator, a, 2, 200);
        JsonNode afterPickups = pick(operator, c, 1, 200);
        assertThat(line(afterPickups, a).get("status").asText()).isEqualTo("PICKED");
        assertThat(line(afterPickups, c).get("status").asText()).isEqualTo("PICKED");
        assertThat(line(afterPickups, b).get("status").asText()).isEqualTo("PENDING");
        assertThat(line(afterPickups, b).get("pending").asInt()).isEqualTo(3);
        JsonNode progress = afterPickups.get("order");
        assertThat(progress.get("linesPicked").asInt()).isEqualTo(2);
        assertThat(progress.get("linesPending").asInt()).isEqualTo(1);
        assertThat(progress.get("unitsPicked").asInt()).isEqualTo(3);
        assertThat(progress.get("unitsRequested").asInt()).isEqualTo(6);
        assertThat(progress.get("logisticsStage").asText()).isEqualTo("PARTIALLY_PICKED");

        // No se puede despachar con una recogida pendiente.
        assertThat(send(HttpMethod.POST, "/api/orders/" + orderId + "/dispatch", operator, dispatchBody(), 409))
                .contains("PICKUP_INCOMPLETE");

        // El operador registra la novedad: el vendedor B todavía no entrega.
        JsonNode incident = post("/api/logistics/orders/" + orderId + "/incidents", operator,
                body("productId", b.productId(), "type", "MISSING_PRODUCT",
                        "description", "El vendedor B todavía no ha entregado el mouse"), 201);
        String incidentId = incident.get("incidentId").asText();
        assertThat(incident.get("status").asText()).isEqualTo("OPEN");
        assertThat(incident.get("orderCode").asText()).isEqualTo(orderCode);
        assertThat(incident.get("reportedBy").get("userId").asText()).isEqualTo(userId(operator));

        // Supervisor: consulta por código, ve tres productos, dos recogidos y uno pendiente, el operador y la novedad.
        JsonNode panel = get("/api/supervision/orders?q=" + orderCode.toLowerCase(), supervisor, 200);
        assertThat(panel).hasSize(1);
        assertThat(panel.get(0).get("operator").get("userId").asText()).isEqualTo(userId(operator));
        assertThat(panel.get(0).get("openIncidents").asInt()).isEqualTo(1);
        assertThat(panel.get(0).get("linesPending").asInt()).isEqualTo(1);
        assertThat(get("/api/supervision/orders?withIncidents=true&pickupsPending=true&operator=" + userId(operator),
                supervisor, 200)).extracting(n -> n.get("orderId").asText()).contains(orderId);
        assertThat(get("/api/supervision/orders?withIncidents=false&q=" + orderCode, supervisor, 200)).isEmpty();
        JsonNode supervised = get("/api/supervision/orders/" + orderCode, supervisor, 200);
        assertThat(supervised.get("lines")).hasSize(3);
        assertThat(line(supervised, a).get("picked").asInt()).isEqualTo(2);
        assertThat(line(supervised, b).get("picked").asInt()).isZero();
        assertThat(supervised.get("incidents")).hasSize(1);
        assertThat(supervised.has("shippingAddress")).as("el supervisor no necesita la dirección").isFalse();

        // Supervisor: deja una instrucción y un responsable; la novedad queda trazada.
        JsonNode instructed = post("/api/supervision/incidents/" + incidentId + "/instructions", supervisor,
                body("text", "Contactar hoy al vendedor B y reprogramar la recogida"), 200);
        assertThat(instructed.get("status").asText()).isEqualTo("IN_PROGRESS");
        JsonNode withOwner = post("/api/supervision/incidents/" + incidentId + "/responsible", supervisor,
                body("responsible", userId(operator)), 200);
        assertThat(withOwner.get("responsible").get("userId").asText()).isEqualTo(userId(operator));

        // Mientras la novedad siga abierta el pedido no avanza, aunque ya se haya recogido todo.
        JsonNode complete = pick(operator, b, 3, 200);
        assertThat(complete.get("order").get("logisticsStage").asText()).isEqualTo("PICKED");
        assertThat(complete.get("order").get("availableActions").toString()).doesNotContain("CREATE_SHIPPING");
        assertThat(send(HttpMethod.POST, "/api/orders/" + orderId + "/shippings", operator, dispatchBody(), 409))
                .contains("INCIDENT_OPEN");

        // Supervisor la resuelve: el registro original se conserva, con quién la reportó y quién la cerró.
        JsonNode resolved = post("/api/supervision/incidents/" + incidentId + "/resolve", supervisor,
                body("notes", "El vendedor B entregó el mouse"), 200);
        assertThat(resolved.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(resolved.get("description").asText()).contains("vendedor B todavía");
        assertThat(resolved.get("reportedBy").get("userId").asText()).isEqualTo(userId(operator));
        assertThat(resolved.get("resolvedBy").get("userId").asText()).isEqualTo(userId(supervisor));
        assertThat(resolved.get("resolutionNotes").asText()).contains("entregó el mouse");
        assertThat(resolved.get("resolvedAt").isNull()).isFalse();
        send(HttpMethod.POST, "/api/supervision/incidents/" + incidentId + "/resolve", supervisor,
                body("notes", "otra vez"), 409);
        send(HttpMethod.POST, "/api/supervision/incidents/" + incidentId + "/instructions", supervisor,
                body("text", "ya cerrada"), 409);

        // El pedido continúa: envío, despacho, tránsito y entrega, todos por el operador asignado.
        String shippingId = read(send(HttpMethod.POST, "/api/orders/" + orderId + "/shippings", operator,
                dispatchBody(), 201), "$.shippingId");
        assertThat(get("/api/logistics/orders/" + orderId, operator, 200).get("order").get("logisticsStage").asText())
                .isEqualTo("PREPARING");
        send(HttpMethod.POST, "/api/shippings/" + shippingId + "/dispatch", operator,
                body("trackingNumber", "TRK-" + uniq(), "carrier", "TCC"), 200);
        send(HttpMethod.POST, "/api/shippings/" + shippingId + "/in-transit", operator, "{}", 200);
        // Ya no se pueden registrar recogidas de un pedido despachado.
        assertThat(send(HttpMethod.POST, "/api/logistics/orders/" + orderId + "/pickups", operator,
                body("productId", b.productId(), "quantity", 1), 409)).contains("INVALID_STATE_TRANSITION");
        send(HttpMethod.POST, "/api/shippings/" + shippingId + "/deliver", operator,
                body("notes", "Recibió el comprador"), 200);

        JsonNode finalOrder = get("/api/orders/" + orderId, buyer.token(), 200);
        assertThat(finalOrder.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(finalOrder.at("/total/amount").decimalValue()).isEqualByComparingTo("590000");

        // Historial completo y en orden, sin perder nada de lo ocurrido.
        JsonNode timeline = get("/api/supervision/orders/" + orderCode, supervisor, 200).get("timeline");
        List<String> types = new ArrayList<>();
        timeline.forEach(e -> types.add(e.get("type").asText()));
        assertThat(types).containsSubsequence("OPERATOR_ASSIGNED", "PICKUP_REGISTERED", "INCIDENT_REPORTED",
                "INCIDENT_INSTRUCTION", "INCIDENT_RESPONSIBLE_ASSIGNED", "INCIDENT_RESOLVED", "SHIPPING_CREATED",
                "DISPATCHED", "IN_TRANSIT", "DELIVERED");
        JsonNode delivered = timeline.get(timeline.size() - 1);
        assertThat(delivered.get("detail").asText()).contains("Recibió el comprador");
        assertThat(delivered.get("actor").get("userId").asText()).isEqualTo(userId(operator));
        assertThat(delivered.get("actorRole").asText()).isEqualTo("LOGISTICS_OPERATOR");
        assertThat(delivered.get("at").asText()).isNotBlank();
        JsonNode instruction = null;
        for (JsonNode event : timeline) {
            if ("INCIDENT_INSTRUCTION".equals(event.get("type").asText())) {
                instruction = event;
            }
        }
        assertThat(instruction).isNotNull();
        assertThat(instruction.get("actorRole").asText()).isEqualTo("SUPERVISOR");
        assertThat(instruction.get("incidentId").asText()).isEqualTo(incidentId);
        assertThat(get("/api/supervision/incidents?status=RESOLVED", supervisor, 200))
                .extracting(n -> n.get("incidentId").asText()).contains(incidentId);
    }

    // ------------------------------------------------------------------
    // Operador: solo lo suyo
    // ------------------------------------------------------------------

    @Test
    void anOperator_onlySeesAndManagesTheOrdersAssignedToHim() throws Exception {
        assign(operator);

        assertThat(get("/api/logistics/orders", otherOperator, 200)).isEmpty();
        String[] foreign = { "/api/logistics/orders/" + orderId, "/api/logistics/orders/" + orderCode,
            "/api/orders/" + orderId, "/api/orders/" + orderId + "/shipping" };
        for (String path : foreign) {
            send(HttpMethod.GET, path, otherOperator, null, 403);
        }
        send(HttpMethod.POST, "/api/logistics/orders/" + orderId + "/pickups", otherOperator,
                body("productId", a.productId(), "quantity", 1), 403);
        send(HttpMethod.POST, "/api/logistics/orders/" + orderId + "/incidents", otherOperator,
                body("type", "OTHER", "description", "no es mío"), 403);
        pickEverything(operator);
        send(HttpMethod.POST, "/api/orders/" + orderId + "/dispatch", otherOperator, dispatchBody(), 403);
        send(HttpMethod.POST, "/api/orders/" + orderId + "/shippings", otherOperator, dispatchBody(), 403);
        String shippingId = read(send(HttpMethod.POST, "/api/orders/" + orderId + "/shippings", operator,
                dispatchBody(), 201), "$.shippingId");
        send(HttpMethod.POST, "/api/shippings/" + shippingId + "/dispatch", otherOperator,
                body("trackingNumber", "TRK-1", "carrier", "TCC"), 403);
        send(HttpMethod.POST, "/api/shippings/" + shippingId + "/in-transit", otherOperator, "{}", 403);
        send(HttpMethod.POST, "/api/shippings/" + shippingId + "/deliver", otherOperator, "{}", 403);
        send(HttpMethod.GET, "/api/shippings/" + shippingId, otherOperator, null, 403);

        // Un pedido inexistente se ve igual que uno ajeno para el operador (no revela cuáles existen); el admin sí recibe 404.
        send(HttpMethod.GET, "/api/logistics/orders/" + UUID.randomUUID(), otherOperator, null, 403);
        send(HttpMethod.GET, "/api/logistics/orders/NM-PED-2026-ZZZZZZ", otherOperator, null, 403);
        send(HttpMethod.GET, "/api/supervision/orders/NM-PED-2026-ZZZZZZ", admin, null, 404);
        // Nada de lo anterior cambió el pedido del operador asignado.
        assertThat(get("/api/logistics/orders/" + orderId, operator, 200).get("order").get("logisticsStage").asText())
                .isEqualTo("PREPARING");
    }

    @Test
    void reassigning_movesTheOrderToTheOtherOperator_keepingTheHistory() throws Exception {
        assign(operator);
        pick(operator, a, 1, 200);
        assign(otherOperator);

        send(HttpMethod.GET, "/api/logistics/orders/" + orderId, operator, null, 403);
        JsonNode detail = get("/api/logistics/orders/" + orderId, otherOperator, 200);
        assertThat(line(detail, a).get("picked").asInt()).isEqualTo(1);
        List<String> types = new ArrayList<>();
        detail.get("timeline").forEach(e -> types.add(e.get("type").asText()));
        assertThat(types).containsExactly("OPERATOR_ASSIGNED", "PICKUP_REGISTERED", "OPERATOR_ASSIGNED");
    }

    @Test
    void assignment_requiresAnActiveOperator_andAPaidOrder() throws Exception {
        String url = "/api/logistics/orders/" + orderId + "/operator";
        send(HttpMethod.PUT, url, admin, body("operator", userId(supervisor)), 400);
        send(HttpMethod.PUT, url, admin, body("operator", buyer.userId()), 400);
        send(HttpMethod.PUT, url, admin, body("operator", UUID.randomUUID().toString()), 404);
        send(HttpMethod.PUT, url, admin, "{}", 400);

        // Un pedido que aún no se paga no tiene trabajo logístico.
        send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                body("productId", a.productId(), "quantity", 1), 200);
        String unpaid = read(send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders", buyer.token(),
                body("shippingAddress", addressJson()), 201), "$.orderId");
        send(HttpMethod.PUT, "/api/logistics/orders/" + unpaid + "/operator", admin,
                body("operator", userId(operator)), 409);
    }

    // ------------------------------------------------------------------
    // Recogidas, estados y entrega
    // ------------------------------------------------------------------

    @Test
    void pickups_areCumulative_neverExceedWhatWasRequested_andValidateTheirInput() throws Exception {
        assign(operator);
        String url = "/api/logistics/orders/" + orderId + "/pickups";

        assertThat(line(pick(operator, b, 1, 200), b).get("status").asText()).isEqualTo("PARTIAL");
        assertThat(line(pick(operator, b, 1, 200), b).get("picked").asInt()).isEqualTo(2);
        assertThat(send(HttpMethod.POST, url, operator, body("productId", b.productId(), "quantity", 2), 409))
                .contains("PICKUP_EXCEEDS_REQUESTED");
        JsonNode full = pick(operator, b, 1, 200);
        assertThat(line(full, b).get("status").asText()).isEqualTo("PICKED");
        assertThat(line(full, b).get("pending").asInt()).isZero();

        send(HttpMethod.POST, url, operator, body("productId", a.productId(), "quantity", 0), 400);
        send(HttpMethod.POST, url, operator, body("productId", a.productId(), "quantity", -2), 400);
        send(HttpMethod.POST, url, operator, "{\"quantity\":1}", 400);
        send(HttpMethod.POST, url, operator, body("productId", UUID.randomUUID().toString(), "quantity", 1), 404);
        // Nada de lo anterior modificó lo pedido ni lo ya recogido.
        JsonNode detail = get("/api/logistics/orders/" + orderId, operator, 200);
        assertThat(line(detail, a).get("picked").asInt()).isZero();
        assertThat(line(detail, b).get("requested").asInt()).isEqualTo(3);
    }

    @Test
    void theStatesAdvanceOnlyInOrder_andDeliveryIsBlockedByOpenIncidents() throws Exception {
        assign(operator);
        // Sin recoger no se crea ni se despacha el envío; el estado del pedido no cambia.
        send(HttpMethod.POST, "/api/orders/" + orderId + "/shippings", operator, dispatchBody(), 409);
        send(HttpMethod.POST, "/api/orders/" + orderId + "/deliver", operator, "{}", 409);
        pickEverything(operator);

        String shippingId = read(send(HttpMethod.POST, "/api/orders/" + orderId + "/shippings", operator,
                dispatchBody(), 201), "$.shippingId");
        // Transiciones fuera de orden.
        send(HttpMethod.POST, "/api/shippings/" + shippingId + "/in-transit", operator, "{}", 409);
        send(HttpMethod.POST, "/api/shippings/" + shippingId + "/deliver", operator, "{}", 409);
        send(HttpMethod.POST, "/api/shippings/" + shippingId + "/dispatch", operator,
                body("trackingNumber", "TRK-" + uniq(), "carrier", "TCC"), 200);
        send(HttpMethod.POST, "/api/shippings/" + shippingId + "/dispatch", operator,
                body("trackingNumber", "TRK-" + uniq(), "carrier", "TCC"), 409);
        send(HttpMethod.POST, "/api/shippings/" + shippingId + "/deliver", operator, "{}", 409);
        send(HttpMethod.POST, "/api/shippings/" + shippingId + "/in-transit", operator, "{}", 200);

        // Una dificultad de entrega detiene la entrega hasta que el supervisor la resuelve.
        String incidentId = post("/api/logistics/orders/" + orderId + "/incidents", operator,
                body("type", "DELIVERY_DIFFICULTY", "description", "Nadie recibe en la dirección"), 201)
                .get("incidentId").asText();
        assertThat(send(HttpMethod.POST, "/api/shippings/" + shippingId + "/deliver", operator, "{}", 409))
                .contains("INCIDENT_OPEN");
        assertThat(get("/api/orders/" + orderId, buyer.token(), 200).get("status").asText()).isEqualTo("DISPATCHED");
        post("/api/supervision/incidents/" + incidentId + "/resolve", supervisor,
                body("notes", "Se coordinó otra franja de entrega"), 200);
        send(HttpMethod.POST, "/api/shippings/" + shippingId + "/deliver", operator,
                body("notes", "Entregado en portería"), 200);
        assertThat(get("/api/orders/" + orderId, buyer.token(), 200).get("status").asText()).isEqualTo("DELIVERED");
        // Un pedido entregado no admite más novedades.
        send(HttpMethod.POST, "/api/logistics/orders/" + orderId + "/incidents", operator,
                body("type", "OTHER", "description", "tarde"), 409);
    }

    @Test
    void anIncident_doesNotChangeQuantitiesPaymentsOrStock() throws Exception {
        assign(operator);
        JsonNode before = get("/api/inventory/" + b.inventoryId(), b.seller().token(), 200);
        post("/api/logistics/orders/" + orderId + "/incidents", operator,
                body("productId", b.productId(), "type", "DAMAGED_PRODUCT", "description", "Caja golpeada"), 201);

        JsonNode order = get("/api/orders/" + orderId, buyer.token(), 200);
        assertThat(order.get("status").asText()).isEqualTo("PAID");
        assertThat(order.at("/total/amount").decimalValue()).isEqualByComparingTo("590000");
        JsonNode after = get("/api/inventory/" + b.inventoryId(), b.seller().token(), 200);
        assertThat(after.get("quantity").asInt()).isEqualTo(before.get("quantity").asInt());
        assertThat(after.get("reservedQuantity").asInt()).isEqualTo(before.get("reservedQuantity").asInt());
        // Los datos de la novedad se validan.
        send(HttpMethod.POST, "/api/logistics/orders/" + orderId + "/incidents", operator,
                body("type", "OTHER", "description", " "), 400);
        send(HttpMethod.POST, "/api/logistics/orders/" + orderId + "/incidents", operator,
                body("type", "INVENTADA", "description", "x"), 400);
        send(HttpMethod.POST, "/api/logistics/orders/" + orderId + "/incidents", operator,
                body("productId", UUID.randomUUID().toString(), "type", "OTHER", "description", "ajeno"), 404);
    }

    @Test
    void incidentFollowUp_validatesTheResponsible_andKeepsTheWholeHistory() throws Exception {
        assign(operator);
        String incidentId = post("/api/logistics/orders/" + orderId + "/incidents", operator,
                body("type", "PICKUP_DELAY", "description", "Retraso en la bodega del vendedor C"), 201)
                .get("incidentId").asText();
        String base = "/api/supervision/incidents/" + incidentId;

        send(HttpMethod.POST, base + "/responsible", supervisor, body("responsible", buyer.userId()), 400);
        send(HttpMethod.POST, base + "/responsible", supervisor, body("responsible", UUID.randomUUID().toString()), 404);
        send(HttpMethod.POST, base + "/instructions", supervisor, body("text", " "), 400);
        send(HttpMethod.POST, base + "/resolve", supervisor, body("notes", " "), 400);
        send(HttpMethod.POST, "/api/supervision/incidents/" + UUID.randomUUID() + "/resolve", supervisor,
                body("notes", "x"), 404);

        post(base + "/instructions", supervisor, body("text", "Primera instrucción"), 200);
        post(base + "/instructions", admin, body("text", "Segunda instrucción"), 200);
        JsonNode closed = post(base + "/resolve", admin, body("notes", "Resuelto por el administrador"), 200);
        assertThat(closed.get("resolvedBy").get("userId").asText()).isEqualTo(userId(admin));
        assertThat(closed.get("reportedBy").get("userId").asText()).isEqualTo(userId(operator));

        List<String> history = new ArrayList<>();
        get("/api/supervision/orders/" + orderId, supervisor, 200).get("timeline")
                .forEach(e -> history.add(e.get("type").asText() + ":" + e.get("actorRole").asText()));
        assertThat(history).containsSubsequence("INCIDENT_REPORTED:LOGISTICS_OPERATOR", "INCIDENT_INSTRUCTION:SUPERVISOR",
                "INCIDENT_INSTRUCTION:ADMIN", "INCIDENT_RESOLVED:ADMIN");
        assertThat(get("/api/supervision/incidents?status=OPEN", supervisor, 200))
                .extracting(n -> n.get("incidentId").asText()).doesNotContain(incidentId);
    }

    // ------------------------------------------------------------------
    // Seguridad
    // ------------------------------------------------------------------

    @Test
    void everyLogisticsEndpoint_requiresAuthentication_andTheRightRole() throws Exception {
        assign(operator);
        String pickup = body("productId", a.productId(), "quantity", 1);
        String incident = body("type", "OTHER", "description", "x");
        String text = body("text", "x");
        String anyId = UUID.randomUUID().toString();

        // Sin autenticación.
        send(HttpMethod.GET, "/api/logistics/orders", null, null, 401);
        send(HttpMethod.GET, "/api/logistics/orders/" + orderCode, null, null, 401);
        send(HttpMethod.POST, "/api/logistics/orders/" + orderId + "/pickups", null, pickup, 401);
        send(HttpMethod.PUT, "/api/logistics/orders/" + orderId + "/operator", null, body("operator", "x@y.z"), 401);
        send(HttpMethod.GET, "/api/supervision/orders", null, null, 401);
        send(HttpMethod.GET, "/api/supervision/orders/" + orderCode, null, null, 401);
        send(HttpMethod.POST, "/api/supervision/incidents/" + anyId + "/resolve", null, text, 401);

        // Comprador y vendedor: nada de logística ni supervisión.
        for (String token : List.of(buyer.token(), a.seller().token())) {
            send(HttpMethod.GET, "/api/logistics/orders", token, null, 403);
            send(HttpMethod.GET, "/api/logistics/orders/" + orderId, token, null, 403);
            send(HttpMethod.POST, "/api/logistics/orders/" + orderId + "/pickups", token, pickup, 403);
            send(HttpMethod.POST, "/api/logistics/orders/" + orderId + "/incidents", token, incident, 403);
            send(HttpMethod.PUT, "/api/logistics/orders/" + orderId + "/operator", token,
                    body("operator", userId(operator)), 403);
            send(HttpMethod.GET, "/api/supervision/orders", token, null, 403);
            send(HttpMethod.GET, "/api/supervision/incidents", token, null, 403);
            send(HttpMethod.POST, "/api/supervision/incidents/" + anyId + "/instructions", token, text, 403);
        }
        // El código del pedido no sirve de credencial: el comprador dueño no entra por él al módulo logístico.
        send(HttpMethod.GET, "/api/logistics/orders/" + orderCode, buyer.token(), null, 403);

        // El operador no supervisa ni asigna.
        send(HttpMethod.GET, "/api/supervision/orders", operator, null, 403);
        send(HttpMethod.GET, "/api/supervision/incidents", operator, null, 403);
        send(HttpMethod.POST, "/api/supervision/incidents/" + anyId + "/resolve", operator, text, 403);
        send(HttpMethod.PUT, "/api/logistics/orders/" + orderId + "/operator", operator,
                body("operator", userId(operator)), 403);

        // El supervisor consulta y atiende novedades, pero no opera la logística ni asigna ni despacha.
        send(HttpMethod.GET, "/api/supervision/orders", supervisor, null, 200);
        send(HttpMethod.GET, "/api/logistics/orders", supervisor, null, 403);
        send(HttpMethod.POST, "/api/logistics/orders/" + orderId + "/pickups", supervisor, pickup, 403);
        send(HttpMethod.POST, "/api/logistics/orders/" + orderId + "/incidents", supervisor, incident, 403);
        send(HttpMethod.PUT, "/api/logistics/orders/" + orderId + "/operator", supervisor,
                body("operator", userId(operator)), 403);
        send(HttpMethod.POST, "/api/orders/" + orderId + "/dispatch", supervisor, dispatchBody(), 403);
        send(HttpMethod.POST, "/api/orders/" + orderId + "/deliver", supervisor, "{}", 403);

        // El administrador conserva sus facultades: asigna y supervisa, pero no hace el trabajo del operador.
        send(HttpMethod.GET, "/api/supervision/orders", admin, null, 200);
        send(HttpMethod.GET, "/api/supervision/orders/" + orderCode, admin, null, 200);
        send(HttpMethod.POST, "/api/logistics/orders/" + orderId + "/pickups", admin, pickup, 403);
        send(HttpMethod.POST, "/api/orders/" + orderId + "/dispatch", admin, dispatchBody(), 403);
        assign(otherOperator);
        send(HttpMethod.GET, "/api/orders/" + orderId, admin, null, 200);
    }

    @Test
    void theOrderCode_isUnique_stable_andNeverReplacesTheId() throws Exception {
        assign(operator);
        // Otro pedido recibe otro código; el del primero no cambia al avanzar el pedido.
        send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                body("productId", a.productId(), "quantity", 1), 200);
        JsonNode second = JSON.readTree(send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders",
                buyer.token(), body("shippingAddress", addressJson()), 201));
        assertThat(second.get("orderCode").asText()).isNotEqualTo(orderCode);
        pick(operator, a, 2, 200);
        assertThat(get("/api/orders/" + orderId, buyer.token(), 200).get("orderCode").asText()).isEqualTo(orderCode);
        // Un código mal formado no es un UUID ni un código: el servidor responde 4xx, nunca 5xx.
        assertThat(status(HttpMethod.GET, "/api/supervision/orders/NM-PED-mal", supervisor, null)).isBetween(400, 404);
        // El vendedor ve el código en su vista del pedido (sin datos del comprador).
        JsonNode sellerView = get("/api/orders/" + orderId, a.seller().token(), 200);
        assertThat(sellerView.get("orderCode").asText()).isEqualTo(orderCode);
        assertThat(sellerView.toString()).doesNotContain("buyerEmail").doesNotContain("shippingAddress");
    }
}
