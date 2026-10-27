package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import nexus.market.domain.valueobjects.SystemRole;

/**
 * Flujo de negocio completo de la API REST (adaptadores de entrada) con
 * autenticación real: cada paso lo ejecuta el rol que le corresponde.
 * Cubre además el mapeo de errores de negocio a respuestas HTTP.
 */
class RestApiIntegrationTest extends RestTestSupport {

    @Test
    void fullPurchaseFlow_endToEnd() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        String operator = staffToken(SystemRole.LOGISTICS_OPERATOR);

        // 1) El ADMIN registra y aprueba al vendedor; el vendedor prepara su catálogo.
        SellerCatalog catalog = seedSellerCatalog(admin, new BigDecimal("1500.00"));
        String seller = catalog.seller().token();

        // 2) Registro público de comprador y login.
        Account buyer = registerBuyer();
        assertThat(buyer.token().split("\\.")).hasSize(3); // JWT header.payload.signature

        // 3) Carrito: 2 unidades y total.
        send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                body("productId", catalog.productId(), "quantity", 2), 200);
        String cart = send(HttpMethod.GET, "/api/buyers/" + buyer.profileId() + "/cart", buyer.token(), null, 200);
        assertThat((int) JsonPath.read(cart, "$.items.length()")).isEqualTo(1);
        assertThat(((Number) JsonPath.read(cart, "$.total.amount")).doubleValue()).isEqualTo(3000.00);

        // 4) Pedido y pago (la pasarela simulada confirma y se genera la factura).
        String orderId = read(send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders",
                buyer.token(), body("shippingAddress", addressJson()), 201), "$.orderId");
        String payment = send(HttpMethod.POST, "/api/orders/" + orderId + "/payment", buyer.token(),
                body("paymentMethod", "CARD"), 200);
        assertThat((String) JsonPath.read(payment, "$.transactionId")).isNotEmpty();

        // 5) Factura emitida por el pago, visible para el comprador.
        String invoice = send(HttpMethod.GET, "/api/orders/" + orderId + "/invoice", buyer.token(), null, 200);
        assertThat((String) JsonPath.read(invoice, "$.status")).isEqualTo("ISSUED");
        assertThat(((Number) JsonPath.read(invoice, "$.total.amount")).doubleValue()).isEqualTo(3000.00);

        // 6) Solo el operador logístico despacha y actualiza los estados logísticos.
        send(HttpMethod.POST, "/api/orders/" + orderId + "/dispatch", seller,
                body("warehouseId", catalog.warehouseId()), 403);
        send(HttpMethod.POST, "/api/orders/" + orderId + "/dispatch", operator,
                body("warehouseId", catalog.warehouseId()), 200);
        String shippingId = read(send(HttpMethod.GET, "/api/orders/" + orderId + "/shipping", operator, null, 200),
                "$.shippingId");
        send(HttpMethod.POST, "/api/shippings/" + shippingId + "/dispatch", operator,
                body("trackingNumber", "TRK-" + uniq(), "carrier", "TCC"), 200);
        send(HttpMethod.POST, "/api/shippings/" + shippingId + "/in-transit", operator, "{}", 200);
        send(HttpMethod.POST, "/api/shippings/" + shippingId + "/deliver", operator, "{}", 200);

        // El pedido queda DELIVERED (lo marca ShippingService.deliver).
        String order = send(HttpMethod.GET, "/api/orders/" + orderId, buyer.token(), null, 200);
        assertThat((String) JsonPath.read(order, "$.status")).isEqualTo("DELIVERED");
        assertThat((String) JsonPath.read(order, "$.deliveredAt")).isNotEmpty();

        // 7) Devolución: la solicita el comprador, la aprueba el ADMIN, la recibe el operador
        //    y el ADMIN procesa el reembolso.
        String returnJson = "{\"items\":[{\"productId\":\"" + catalog.productId()
                + "\",\"quantity\":1,\"reason\":\"DAMAGED\"}]}";
        String returnId = read(send(HttpMethod.POST, "/api/orders/" + orderId + "/returns", buyer.token(),
                returnJson, 201), "$.returnId");
        String refundId = read(send(HttpMethod.POST, "/api/returns/" + returnId + "/approve", admin, "{}", 200),
                "$.refundId");
        send(HttpMethod.POST, "/api/returns/" + returnId + "/process", operator, "{}", 200);
        send(HttpMethod.POST, "/api/refunds/" + refundId + "/process", admin, "{}", 200);

        // La reposición de inventario vuelve a 9 (10 - 2 vendidos + 1 devuelto).
        String inventory = send(HttpMethod.GET, "/api/inventory/" + catalog.inventoryId(), seller, null, 200);
        assertThat((int) JsonPath.read(inventory, "$.quantity")).isEqualTo(9);
    }

    @Test
    void errorMapping_returnsExpectedHttpStatuses() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        String suffix = uniq();
        String email = "error-" + suffix + "@mail.test";
        String payload = body("fullName", "Persona " + suffix, "email", email, "documentId", "CC-" + suffix,
                "password", PASSWORD, "address", addressJson());

        // 404: producto inexistente (consulta pública).
        String notFound = send(HttpMethod.GET, "/api/products/" + UUID.randomUUID(), null, null, 404);
        assertThat((String) JsonPath.read(notFound, "$.code")).isEqualTo("PRODUCT_NOT_FOUND");

        // 409: email duplicado.
        send(HttpMethod.POST, "/api/auth/register/buyer", null, payload, 201);
        String duplicated = send(HttpMethod.POST, "/api/auth/register/buyer", null,
                payload.replace("CC-" + suffix, "CC-" + uniq()), 409);
        assertThat((String) JsonPath.read(duplicated, "$.code")).isEqualTo("USER_ALREADY_EXISTS");

        // 401: contraseña incorrecta (mensaje genérico, sin filtrar existencia).
        String wrongPassword = send(HttpMethod.POST, "/api/auth/login", null,
                body("email", email, "password", "mala"), 401);
        assertThat((String) JsonPath.read(wrongPassword, "$.code")).isEqualTo("INVALID_CREDENTIALS");

        // 400: tipo de producto inválido (el vendedor opera sobre su propio sellerId).
        Account seller = registerApprovedSeller(admin);
        String badType = send(HttpMethod.POST, "/api/products", seller.token(),
                body("sellerId", seller.profileId(), "name", "Producto valido", "description", "Descripcion valida",
                        "type", "VIRTUAL", "price", moneyJson(BigDecimal.ONE)), 400);
        assertThat((String) JsonPath.read(badType, "$.code")).isEqualTo("INVALID_PRODUCT_TYPE");

        // 400: validación de Value Object (email inválido).
        String badEmail = send(HttpMethod.POST, "/api/auth/register/buyer", null,
                body("fullName", "Persona", "email", "no-es-email", "documentId", "CC-" + uniq(),
                        "password", PASSWORD, "address", addressJson()), 400);
        assertThat((String) JsonPath.read(badEmail, "$.code")).isEqualTo("VALIDATION_ERROR");
    }
}
