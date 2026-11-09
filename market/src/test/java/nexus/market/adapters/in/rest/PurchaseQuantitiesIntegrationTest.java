package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import nexus.market.domain.valueobjects.SystemRole;

/**
 * Cantidades en las compras: agregar y modificar unidades, rechazo de cantidades inválidas o superiores al inventario,
 * cálculo de subtotales y total en el servidor, cantidades y precios congelados en el pedido y no sobreventa.
 */
class PurchaseQuantitiesIntegrationTest extends RestTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode get(String path, String token) throws Exception {
        return JSON.readTree(send(HttpMethod.GET, path, token, null, 200));
    }

    private String cartPath(Account buyer) {
        return "/api/buyers/" + buyer.profileId() + "/cart";
    }

    private void add(Account buyer, String productId, int quantity, int expected) throws Exception {
        send(HttpMethod.POST, cartPath(buyer) + "/items", buyer.token(),
                body("productId", productId, "quantity", quantity), expected);
    }

    private void setQuantity(Account buyer, String productId, int quantity, int expected) throws Exception {
        send(HttpMethod.PUT, cartPath(buyer) + "/items/" + productId, buyer.token(), body("quantity", quantity), expected);
    }

    private static BigDecimal total(JsonNode cart) {
        return cart.at("/total/amount").decimalValue();
    }

    private static JsonNode line(JsonNode cart, String productId) {
        for (JsonNode item : cart.get("items")) {
            if (productId.equals(item.get("productId").asText())) {
                return item;
            }
        }
        throw new AssertionError("el producto " + productId + " no está en el carrito");
    }

    @Test
    void oneUnit_andSeveralUnits_areAccepted_andTotalsAreComputedByTheServer() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog teclado = seedSellerCatalog(admin, new BigDecimal("50000"));
        SellerCatalog mouse = seedSellerCatalog(admin, new BigDecimal("30000"));
        SellerCatalog monitor = seedSellerCatalog(admin, new BigDecimal("400000"));
        Account buyer = registerBuyer();

        add(buyer, monitor.productId(), 1, 200);
        add(buyer, teclado.productId(), 2, 200);
        add(buyer, mouse.productId(), 3, 200);

        JsonNode cart = get(cartPath(buyer), buyer.token());
        assertThat(cart.get("items")).hasSize(3);
        assertThat(line(cart, teclado.productId()).get("quantity").asInt()).isEqualTo(2);
        assertThat(line(cart, teclado.productId()).at("/total/amount").decimalValue()).isEqualByComparingTo("100000");
        assertThat(line(cart, mouse.productId()).at("/total/amount").decimalValue()).isEqualByComparingTo("90000");
        assertThat(line(cart, monitor.productId()).at("/total/amount").decimalValue()).isEqualByComparingTo("400000");
        assertThat(total(cart)).isEqualByComparingTo("590000");
        // Cada línea conserva su vendedor y su precio unitario.
        assertThat(line(cart, mouse.productId()).at("/unitPrice/amount").decimalValue()).isEqualByComparingTo("30000");
        assertThat(line(cart, mouse.productId()).get("sellerId").asText()).isEqualTo(mouse.seller().profileId());
        assertThat(line(cart, mouse.productId()).get("sellerSku").asText()).isNotBlank();

        // Cambiar el mouse de 3 a 2 actualiza subtotal y total.
        setQuantity(buyer, mouse.productId(), 2, 200);
        cart = get(cartPath(buyer), buyer.token());
        assertThat(line(cart, mouse.productId()).at("/total/amount").decimalValue()).isEqualByComparingTo("60000");
        assertThat(total(cart)).isEqualByComparingTo("560000");

        // Agregar de nuevo suma a lo que ya había, y quitar un producto lo elimina del total.
        add(buyer, teclado.productId(), 1, 200);
        assertThat(line(get(cartPath(buyer), buyer.token()), teclado.productId()).get("quantity").asInt()).isEqualTo(3);
        send(HttpMethod.DELETE, cartPath(buyer) + "/items/" + monitor.productId(), buyer.token(), null, 200);
        cart = get(cartPath(buyer), buyer.token());
        assertThat(cart.get("items")).hasSize(2);
        assertThat(total(cart)).isEqualByComparingTo("210000"); // 3 x 50000 + 2 x 30000
    }

    @Test
    void zeroAndNegativeQuantities_areRejected_andTheCartIsUntouched() throws Exception {
        SellerCatalog item = seedSellerCatalog(staffToken(SystemRole.ADMIN), new BigDecimal("1000"));
        Account buyer = registerBuyer();
        add(buyer, item.productId(), 2, 200);

        for (int invalid : new int[] { 0, -1, -100 }) {
            add(buyer, item.productId(), invalid, 400);
            setQuantity(buyer, item.productId(), invalid, 400);
        }
        assertThat(line(get(cartPath(buyer), buyer.token()), item.productId()).get("quantity").asInt()).isEqualTo(2);
        // Un decimal o un texto no son enteros positivos.
        assertThat(status(HttpMethod.POST, cartPath(buyer) + "/items", buyer.token(),
                "{\"productId\":\"" + item.productId() + "\",\"quantity\":\"dos\"}")).isBetween(400, 499);
    }

    @Test
    void quantityAboveTheInventory_isRejected_whenAdding_whenUpdating_andCumulatively() throws Exception {
        SellerCatalog item = seedSellerCatalog(staffToken(SystemRole.ADMIN), new BigDecimal("1000")); // 10 unidades
        Account buyer = registerBuyer();

        assertThat(send(HttpMethod.POST, cartPath(buyer) + "/items", buyer.token(),
                body("productId", item.productId(), "quantity", 11), 400)).contains("INSUFFICIENT_INVENTORY");
        add(buyer, item.productId(), 6, 200);
        add(buyer, item.productId(), 5, 400); // 6 + 5 = 11 > 10
        setQuantity(buyer, item.productId(), 11, 400);
        assertThat(line(get(cartPath(buyer), buyer.token()), item.productId()).get("quantity").asInt()).isEqualTo(6);
        setQuantity(buyer, item.productId(), 10, 200); // exactamente lo disponible sí se acepta
        assertThat(line(get(cartPath(buyer), buyer.token()), item.productId()).get("quantity").asInt()).isEqualTo(10);
    }

    @Test
    void clientSentPricesAndTotals_areIgnored() throws Exception {
        SellerCatalog item = seedSellerCatalog(staffToken(SystemRole.ADMIN), new BigDecimal("5000"));
        Account buyer = registerBuyer();
        int status = status(HttpMethod.POST, cartPath(buyer) + "/items", buyer.token(),
                "{\"productId\":\"" + item.productId() + "\",\"quantity\":2,\"unitPrice\":{\"amount\":1,\"currency\":\"USD\"},"
                        + "\"total\":{\"amount\":1,\"currency\":\"USD\"}}");
        assertThat(status).isIn(200, 400);
        if (status == 200) {
            assertThat(total(get(cartPath(buyer), buyer.token()))).isEqualByComparingTo("10000");
        }
    }

    @Test
    void theOrder_keepsQuantitiesAndPrices_andUpdatesTheInventory() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog teclado = seedSellerCatalog(admin, new BigDecimal("50000"));
        SellerCatalog mouse = seedSellerCatalog(admin, new BigDecimal("30000"));
        Account buyer = registerBuyer();
        add(buyer, teclado.productId(), 2, 200);
        add(buyer, mouse.productId(), 3, 200);

        String orderJson = send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders", buyer.token(),
                body("shippingAddress", addressJson()), 201);
        String orderId = read(orderJson, "$.orderId");
        // Reservado, todavía no vendido.
        JsonNode reserved = get("/api/inventory/" + mouse.inventoryId(), mouse.seller().token());
        assertThat(reserved.get("reservedQuantity").asInt()).isEqualTo(3);
        assertThat(reserved.get("availableQuantity").asInt()).isEqualTo(7);

        // El vendedor cambia el precio después: el pedido conserva el precio y la cantidad del momento de la compra.
        send(HttpMethod.PUT, "/api/products/" + mouse.productId() + "/price", mouse.seller().token(),
                body("price", moneyJson(new BigDecimal("99999"))), 200);
        send(HttpMethod.POST, "/api/orders/" + orderId + "/payment", buyer.token(), body("paymentMethod", "CARD"), 200);

        JsonNode order = get("/api/orders/" + orderId, buyer.token());
        assertThat(order.at("/total/amount").decimalValue()).isEqualByComparingTo("190000"); // 2 x 50000 + 3 x 30000
        assertThat(line(order, mouse.productId()).get("quantity").asInt()).isEqualTo(3);
        assertThat(line(order, mouse.productId()).at("/unitPrice/amount").decimalValue()).isEqualByComparingTo("30000");
        assertThat(line(order, teclado.productId()).get("quantity").asInt()).isEqualTo(2);

        // Vendido: el stock baja y ya no queda nada reservado.
        JsonNode sold = get("/api/inventory/" + mouse.inventoryId(), mouse.seller().token());
        assertThat(sold.get("quantity").asInt()).isEqualTo(7);
        assertThat(sold.get("reservedQuantity").asInt()).isZero();
    }

    @Test
    void theLastUnits_cannotBeSoldTwice_inSequence() throws Exception {
        SellerCatalog item = seedSellerCatalog(staffToken(SystemRole.ADMIN), new BigDecimal("1000")); // 10 unidades
        Account first = registerBuyer();
        Account second = registerBuyer();
        add(first, item.productId(), 6, 200);
        add(second, item.productId(), 6, 200); // por separado, ambos caben en el stock

        send(HttpMethod.POST, "/api/buyers/" + first.profileId() + "/orders", first.token(),
                body("shippingAddress", addressJson()), 201);
        // El segundo pedido vuelve a validar la disponibilidad al confirmar la compra y no se crea.
        assertThat(send(HttpMethod.POST, "/api/buyers/" + second.profileId() + "/orders", second.token(),
                body("shippingAddress", addressJson()), 400)).contains("INSUFFICIENT_INVENTORY");
        assertThat(get("/api/inventory/" + item.inventoryId(), item.seller().token()).get("reservedQuantity").asInt())
                .isEqualTo(6);
    }

    @Test
    void twoBuyersOrderingTheLastUnitsAtTheSameTime_onlyOneSucceeds() throws Exception {
        SellerCatalog item = seedSellerCatalog(staffToken(SystemRole.ADMIN), new BigDecimal("1000")); // 10 unidades
        List<Account> buyers = List.of(registerBuyer(), registerBuyer());
        for (Account buyer : buyers) {
            add(buyer, item.productId(), 6, 200);
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new java.util.ArrayList<>();
            for (Account buyer : buyers) {
                Callable<Integer> order = () -> {
                    start.await();
                    return status(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders", buyer.token(),
                            body("shippingAddress", addressJson()));
                };
                results.add(pool.submit(order));
            }
            start.countDown();
            int created = 0;
            for (Future<Integer> result : results) {
                if (result.get() == 201) {
                    created++;
                }
            }
            assertThat(created).as("solo uno de los dos pedidos se crea").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        JsonNode inventory = get("/api/inventory/" + item.inventoryId(), item.seller().token());
        assertThat(inventory.get("reservedQuantity").asInt()).as("nunca se reserva más de lo que hay").isEqualTo(6);
        assertThat(inventory.get("availableQuantity").asInt()).isEqualTo(4);
    }

    @Test
    void anOrderThatReservesMoreThanHalfOfTheStock_orTheLastUnits_canBePaid() throws Exception {
        SellerCatalog item = seedSellerCatalog(staffToken(SystemRole.ADMIN), new BigDecimal("1000")); // 10 unidades
        Account buyer = registerBuyer();
        add(buyer, item.productId(), 7, 200); // 7 de 10: más de la mitad
        String first = read(send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders", buyer.token(),
                body("shippingAddress", addressJson()), 201), "$.orderId");
        send(HttpMethod.POST, "/api/orders/" + first + "/payment", buyer.token(), body("paymentMethod", "CARD"), 200);

        Account other = registerBuyer();
        add(other, item.productId(), 3, 200); // las últimas 3 unidades
        String last = read(send(HttpMethod.POST, "/api/buyers/" + other.profileId() + "/orders", other.token(),
                body("shippingAddress", addressJson()), 201), "$.orderId");
        send(HttpMethod.POST, "/api/orders/" + last + "/payment", other.token(), body("paymentMethod", "CARD"), 200);

        JsonNode inventory = get("/api/inventory/" + item.inventoryId(), item.seller().token());
        assertThat(inventory.get("quantity").asInt()).isZero();
        assertThat(inventory.get("reservedQuantity").asInt()).isZero();
    }

    @Test
    void aDecimalQuantity_isRejected_insteadOfBeingTruncated() throws Exception {
        SellerCatalog item = seedSellerCatalog(staffToken(SystemRole.ADMIN), new BigDecimal("1000"));
        Account buyer = registerBuyer();
        send(HttpMethod.POST, cartPath(buyer) + "/items", buyer.token(),
                "{\"productId\":\"" + item.productId() + "\",\"quantity\":1.5}", 400);
        add(buyer, item.productId(), 2, 200);
        send(HttpMethod.PUT, cartPath(buyer) + "/items/" + item.productId(), buyer.token(), "{\"quantity\":2.7}", 400);
        assertThat(line(get(cartPath(buyer), buyer.token()), item.productId()).get("quantity").asInt()).isEqualTo(2);
    }

    @Test
    void issuingTheInvoiceTwice_isAConflict_notAServerError() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog item = seedSellerCatalog(admin, new BigDecimal("1000"));
        Account buyer = registerBuyer();
        add(buyer, item.productId(), 1, 200);
        String orderId = read(send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders", buyer.token(),
                body("shippingAddress", addressJson()), 201), "$.orderId");
        send(HttpMethod.POST, "/api/orders/" + orderId + "/payment", buyer.token(), body("paymentMethod", "CARD"), 200);
        send(HttpMethod.GET, "/api/orders/" + orderId + "/invoice", buyer.token(), null, 200); // el pago ya la generó
        String error = send(HttpMethod.POST, "/api/orders/" + orderId + "/invoice", admin, "{}", 409);
        assertThat(read(error, "$.code")).isEqualTo("INVOICE_ALREADY_EXISTS");
    }
}
