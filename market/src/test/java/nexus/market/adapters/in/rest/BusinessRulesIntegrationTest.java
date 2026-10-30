package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import nexus.market.domain.valueobjects.SystemRole;

/**
 * Reglas de negocio de devoluciones, envíos y existencias que deben cumplirse de punta a punta por la API: nunca se
 * devuelve (ni se reembolsa) más de lo comprado, los dos caminos de envío dejan pedido y envío coherentes y los fallos
 * de negocio son errores 4xx, no 500.
 */
class BusinessRulesIntegrationTest extends RestTestSupport {

    private String admin;
    private String operator;
    private SellerCatalog catalog;
    private Account buyer;

    /** Pedido pagado de {@code quantity} unidades a 100,00 (el catálogo tiene 10 unidades). */
    private String paidOrder(int quantity) throws Exception {
        admin = staffToken(SystemRole.ADMIN);
        operator = staffToken(SystemRole.LOGISTICS_OPERATOR);
        catalog = seedSellerCatalog(admin, new BigDecimal("100.00"));
        buyer = registerBuyer();
        send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                body("productId", catalog.productId(), "quantity", quantity), 200);
        String orderId = read(send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders", buyer.token(),
                body("shippingAddress", addressJson()), 201), "$.orderId");
        send(HttpMethod.POST, "/api/orders/" + orderId + "/payment", buyer.token(), body("paymentMethod", "CARD"), 200);
        return orderId;
    }

    private String deliveredOrder(int quantity) throws Exception {
        String orderId = paidOrder(quantity);
        send(HttpMethod.POST, "/api/orders/" + orderId + "/dispatch", operator,
                body("warehouseId", catalog.warehouseId()), 200);
        send(HttpMethod.POST, "/api/orders/" + orderId + "/deliver", operator, "{}", 200);
        return orderId;
    }

    private String returnJson(String reason, Object... productAndQuantity) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < productAndQuantity.length; i += 2) {
            if (i > 0) {
                items.append(',');
            }
            items.append("{\"productId\":\"").append(productAndQuantity[i]).append("\",\"quantity\":")
                    .append(productAndQuantity[i + 1]).append(",\"reason\":\"").append(reason).append("\"}");
        }
        return "{\"items\":[" + items + "]}";
    }

    // ------------------------------------------------------------------
    // Devoluciones
    // ------------------------------------------------------------------

    @Test
    void returnMoreThanPurchased_isRejected() throws Exception {
        String orderId = deliveredOrder(2);
        String body = send(HttpMethod.POST, "/api/orders/" + orderId + "/returns", buyer.token(),
                returnJson("DAMAGED", catalog.productId(), 3), 400);
        assertThat((String) JsonPath.read(body, "$.code")).isEqualTo("RETURN_NOT_ELIGIBLE");
    }

    @Test
    void returnOfAProductThatIsNotInTheOrder_isRejected() throws Exception {
        String orderId = deliveredOrder(1);
        SellerCatalog other = seedSellerCatalog(admin, new BigDecimal("50.00"));
        String body = send(HttpMethod.POST, "/api/orders/" + orderId + "/returns", buyer.token(),
                returnJson("DAMAGED", other.productId(), 1), 400);
        assertThat((String) JsonPath.read(body, "$.code")).isEqualTo("RETURN_NOT_ELIGIBLE");
    }

    @Test
    void sameProductRepeatedInOneRequest_cannotExceedWhatWasBought() throws Exception {
        String orderId = deliveredOrder(2);
        // 2 + 2 > 2 comprados: sumar líneas repetidas no puede saltarse el límite.
        send(HttpMethod.POST, "/api/orders/" + orderId + "/returns", buyer.token(),
                returnJson("DAMAGED", catalog.productId(), 2, catalog.productId(), 2), 400);
    }

    @Test
    void refundIsNeverMoreThanWhatWasPaid() throws Exception {
        String orderId = deliveredOrder(2);
        String returnId = read(send(HttpMethod.POST, "/api/orders/" + orderId + "/returns", buyer.token(),
                returnJson("DAMAGED", catalog.productId(), 2), 201), "$.returnId");
        String refund = send(HttpMethod.POST, "/api/returns/" + returnId + "/approve", admin, "{}", 200);
        assertThat(((Number) JsonPath.read(refund, "$.amount.amount")).doubleValue()).isEqualTo(200.00);
    }

    @Test
    void secondReturnForTheSameOrder_isAConflict_notAServerError() throws Exception {
        String orderId = deliveredOrder(2);
        send(HttpMethod.POST, "/api/orders/" + orderId + "/returns", buyer.token(),
                returnJson("DAMAGED", catalog.productId(), 1), 201);
        int second = status(HttpMethod.POST, "/api/orders/" + orderId + "/returns", buyer.token(),
                returnJson("DAMAGED", catalog.productId(), 1));
        assertThat(second).isEqualTo(409);
    }

    @Test
    void returnOfAnOrderThatWasNotDelivered_isRejected() throws Exception {
        String orderId = paidOrder(1);
        send(HttpMethod.POST, "/api/orders/" + orderId + "/returns", buyer.token(),
                returnJson("DAMAGED", catalog.productId(), 1), 400);
    }

    // ------------------------------------------------------------------
    // Envíos: los dos caminos deben dejar pedido y envío coherentes
    // ------------------------------------------------------------------

    @Test
    void shippingCreatedFirst_thenDispatchedAndDelivered_leavesTheOrderDelivered() throws Exception {
        String orderId = paidOrder(1);
        String shippingId = read(send(HttpMethod.POST, "/api/orders/" + orderId + "/shippings", operator,
                body("warehouseId", catalog.warehouseId()), 201), "$.shippingId");
        send(HttpMethod.POST, "/api/shippings/" + shippingId + "/dispatch", operator,
                body("trackingNumber", "TRK-" + uniq(), "carrier", "TCC"), 200);
        send(HttpMethod.POST, "/api/shippings/" + shippingId + "/in-transit", operator, "{}", 200);
        send(HttpMethod.POST, "/api/shippings/" + shippingId + "/deliver", operator, "{}", 200);

        String order = send(HttpMethod.GET, "/api/orders/" + orderId, buyer.token(), null, 200);
        assertThat((String) JsonPath.read(order, "$.status")).isEqualTo("DELIVERED");
    }

    @Test
    void orderDispatch_afterTheShippingWasAlreadyCreated_doesNotDuplicateIt() throws Exception {
        String orderId = paidOrder(1);
        send(HttpMethod.POST, "/api/orders/" + orderId + "/shippings", operator,
                body("warehouseId", catalog.warehouseId()), 201);
        int status = status(HttpMethod.POST, "/api/orders/" + orderId + "/dispatch", operator,
                body("warehouseId", catalog.warehouseId()));
        assertThat(status).as("despachar un pedido con envío ya creado no es un error de servidor").isLessThan(500);
        assertThat(status(HttpMethod.GET, "/api/orders/" + orderId + "/shipping", operator, null)).isEqualTo(200);
    }

    // ------------------------------------------------------------------
    // Existencias y estados
    // ------------------------------------------------------------------

    @Test
    void orderingMoreThanTheStock_isRejected_andLeavesStockUntouched() throws Exception {
        admin = staffToken(SystemRole.ADMIN);
        catalog = seedSellerCatalog(admin, new BigDecimal("10.00"));
        buyer = registerBuyer();
        send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                body("productId", catalog.productId(), "quantity", 11), 200);
        int status = status(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders", buyer.token(),
                body("shippingAddress", addressJson()));
        assertThat(status).isBetween(400, 499);
        String inventory = send(HttpMethod.GET, "/api/inventory/" + catalog.inventoryId(), catalog.seller().token(),
                null, 200);
        assertThat((int) JsonPath.read(inventory, "$.quantity")).isEqualTo(10);
    }

    @Test
    void cancellingAPaidOrder_restoresStock_andACancelledOrderCannotBePaid() throws Exception {
        String orderId = paidOrder(3);
        send(HttpMethod.POST, "/api/orders/" + orderId + "/cancel", buyer.token(), "{}", 200);
        String inventory = send(HttpMethod.GET, "/api/inventory/" + catalog.inventoryId(), catalog.seller().token(),
                null, 200);
        assertThat((int) JsonPath.read(inventory, "$.quantity")).isEqualTo(10);
        assertThat(status(HttpMethod.POST, "/api/orders/" + orderId + "/payment", buyer.token(),
                body("paymentMethod", "CARD"))).isBetween(400, 499);
        assertThat(status(HttpMethod.POST, "/api/orders/" + orderId + "/cancel", buyer.token(), "{}"))
                .isBetween(400, 499);
    }

    @Test
    void dispatchBeforePayment_isRejected() throws Exception {
        admin = staffToken(SystemRole.ADMIN);
        operator = staffToken(SystemRole.LOGISTICS_OPERATOR);
        catalog = seedSellerCatalog(admin, new BigDecimal("10.00"));
        buyer = registerBuyer();
        send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                body("productId", catalog.productId(), "quantity", 1), 200);
        String orderId = read(send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders", buyer.token(),
                body("shippingAddress", addressJson()), 201), "$.orderId");
        assertThat(status(HttpMethod.POST, "/api/orders/" + orderId + "/dispatch", operator,
                body("warehouseId", catalog.warehouseId()))).isBetween(400, 499);
        assertThat(status(HttpMethod.POST, "/api/orders/" + orderId + "/deliver", operator, "{}"))
                .isBetween(400, 499);
    }
}
