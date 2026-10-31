package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.ports.out.OrderRepositoryPort;
import nexus.market.domain.ports.out.PaymentServicePort;
import nexus.market.domain.valueobjects.Money;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.SystemRole;

/**
 * El identificador de transacción del cobro se guarda en el pedido y los reembolsos lo usan como referencia del cobro
 * original; cancelar un pedido ya pagado reembolsa y repone el stock, y si la pasarela falla no se cancela nada.
 */
class PaymentTraceabilityIntegrationTest extends RestTestSupport {

    @MockitoSpyBean
    private PaymentServicePort payments;

    @MockitoSpyBean
    private OrderRepositoryPort orders;

    @Autowired
    private JdbcTemplate jdbc;

    private String admin;
    private SellerCatalog catalog;
    private Account buyer;

    @AfterEach
    void resetSpy() {
        reset(payments);
        reset(orders);
    }

    private String pendingOrder(int quantity) throws Exception {
        admin = staffToken(SystemRole.ADMIN);
        catalog = seedSellerCatalog(admin, new BigDecimal("100.00"));
        buyer = registerBuyer();
        send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                body("productId", catalog.productId(), "quantity", quantity), 200);
        return read(send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders", buyer.token(),
                body("shippingAddress", addressJson()), 201), "$.orderId");
    }

    private String pay(String orderId) throws Exception {
        return JsonPath.read(send(HttpMethod.POST, "/api/orders/" + orderId + "/payment", buyer.token(),
                body("paymentMethod", "CARD"), 200), "$.transactionId");
    }

    private int stock() throws Exception {
        return JsonPath.read(send(HttpMethod.GET, "/api/inventory/" + catalog.inventoryId(), catalog.seller().token(),
                null, 200), "$.quantity");
    }

    @Test
    void theChargeTransactionIsStoredInTheOrder() throws Exception {
        String orderId = pendingOrder(1);
        assertThat(orders.findById(OrderId.of(orderId)).orElseThrow().getPaymentTransactionId()).isNull();

        String transactionId = pay(orderId);

        assertThat(orders.findById(OrderId.of(orderId)).orElseThrow().getPaymentTransactionId())
                .isEqualTo(transactionId);
    }

    @Test
    void returnRefund_referencesTheRealChargeTransaction() throws Exception {
        String orderId = pendingOrder(1);
        String transactionId = pay(orderId);
        String operator = staffToken(SystemRole.LOGISTICS_OPERATOR);
        send(HttpMethod.POST, "/api/orders/" + orderId + "/dispatch", operator,
                body("warehouseId", catalog.warehouseId()), 200);
        send(HttpMethod.POST, "/api/orders/" + orderId + "/deliver", operator, "{}", 200);
        String returnId = read(send(HttpMethod.POST, "/api/orders/" + orderId + "/returns", buyer.token(),
                "{\"items\":[{\"productId\":\"" + catalog.productId() + "\",\"quantity\":1,\"reason\":\"DAMAGED\"}]}",
                201), "$.returnId");
        String refundId = read(send(HttpMethod.POST, "/api/returns/" + returnId + "/approve", admin, "{}", 200),
                "$.refundId");
        reset(payments);

        send(HttpMethod.POST, "/api/refunds/" + refundId + "/process", admin, "{}", 200);

        ArgumentCaptor<String> original = ArgumentCaptor.forClass(String.class);
        verify(payments).refund(eq(OrderId.of(orderId)), any(Money.class), original.capture());
        assertThat(original.getValue()).isEqualTo(transactionId);
    }

    @Test
    void cancellingAPaidOrder_refundsTheFullAmountWithTheOriginalTransaction() throws Exception {
        String orderId = pendingOrder(2);
        String transactionId = pay(orderId);
        reset(payments);

        send(HttpMethod.POST, "/api/orders/" + orderId + "/cancel", buyer.token(), "{}", 200);

        ArgumentCaptor<Money> amount = ArgumentCaptor.forClass(Money.class);
        verify(payments).refund(eq(OrderId.of(orderId)), amount.capture(), eq(transactionId));
        assertThat(amount.getValue().getAmount()).isEqualByComparingTo("200.00");
        assertThat(stock()).isEqualTo(10);
    }

    @Test
    void legacyPaidOrder_withoutStoredTransaction_isRefundedWithTheDerivedReference() throws Exception {
        String orderId = pendingOrder(1);
        pay(orderId);
        jdbc.update("UPDATE orders SET payment_transaction_id = NULL WHERE order_id = ?", orderId);
        assertThat(orders.findById(OrderId.of(orderId)).orElseThrow().hasLegacyChargeReference()).isTrue();
        reset(payments);

        send(HttpMethod.POST, "/api/orders/" + orderId + "/cancel", buyer.token(), "{}", 200);

        verify(payments).refund(eq(OrderId.of(orderId)), any(Money.class), eq("CHARGE-" + OrderId.of(orderId)));
    }

    @Test
    void ifThePaymentCannotBeCompleted_theChargeIsReversed_andTheOrderStaysPending() throws Exception {
        String orderId = pendingOrder(1);
        reset(payments);
        doThrow(new BusinessException("SAVE_FAILED", "fallo simulado")).when(orders).save(any());

        int status = status(HttpMethod.POST, "/api/orders/" + orderId + "/payment", buyer.token(),
                body("paymentMethod", "CARD"));
        reset(orders);

        assertThat(status).isBetween(400, 499);
        verify(payments).refund(eq(OrderId.of(orderId)), any(Money.class), startsWith("TXN-"));
        String order = send(HttpMethod.GET, "/api/orders/" + orderId, buyer.token(), null, 200);
        assertThat((String) JsonPath.read(order, "$.status")).isEqualTo("PENDING_PAYMENT");
        assertThat(orders.findById(OrderId.of(orderId)).orElseThrow().getPaymentTransactionId()).isNull();
    }

    @Test
    void cancellingAPaidOrderTwice_refundsAndRestocksOnlyOnce() throws Exception {
        String orderId = pendingOrder(2);
        pay(orderId);
        reset(payments);

        send(HttpMethod.POST, "/api/orders/" + orderId + "/cancel", buyer.token(), "{}", 200);
        int status = status(HttpMethod.POST, "/api/orders/" + orderId + "/cancel", buyer.token(), "{}");

        assertThat(status).isBetween(400, 499);
        verify(payments, org.mockito.Mockito.times(1)).refund(any(), any(Money.class), any());
        assertThat(stock()).isEqualTo(10); // 10 iniciales: ni 12 (doble reposición) ni 8
    }

    @Test
    void aDeliveredOrder_cannotBeCancelled_soItIsNeverRefundedTwiceThroughCancellation() throws Exception {
        String orderId = pendingOrder(1);
        pay(orderId);
        String operator = staffToken(SystemRole.LOGISTICS_OPERATOR);
        send(HttpMethod.POST, "/api/orders/" + orderId + "/dispatch", operator,
                body("warehouseId", catalog.warehouseId()), 200);
        send(HttpMethod.POST, "/api/orders/" + orderId + "/deliver", operator, "{}", 200);
        reset(payments);
        int stockBefore = stock();

        int status = status(HttpMethod.POST, "/api/orders/" + orderId + "/cancel", buyer.token(), "{}");

        assertThat(status).isBetween(400, 499);
        verify(payments, never()).refund(any(), any(), any());
        assertThat(stock()).isEqualTo(stockBefore);
    }

    @Test
    void cancellingAnUnpaidOrder_doesNotTouchThePaymentGateway() throws Exception {
        String orderId = pendingOrder(2);
        assertThat(stock()).isEqualTo(10); // reservado, aún no vendido
        reset(payments);

        send(HttpMethod.POST, "/api/orders/" + orderId + "/cancel", buyer.token(), "{}", 200);

        verify(payments, never()).refund(any(), any(), any());
        assertThat(stock()).isEqualTo(10);
    }

    @Test
    void ifTheGatewayFailsOnCancel_theOrderStaysPaid_andNothingIsRestocked() throws Exception {
        String orderId = pendingOrder(2);
        pay(orderId);
        int stockWhilePaid = stock();
        doThrow(new IllegalStateException("pasarela caída")).when(payments).refund(any(), any(), any());

        String failure = send(HttpMethod.POST, "/api/orders/" + orderId + "/cancel", buyer.token(), "{}", 400);

        assertThat((String) JsonPath.read(failure, "$.code")).isEqualTo("REFUND_FAILED");
        assertThat(failure).doesNotContain("pasarela caída");
        String order = send(HttpMethod.GET, "/api/orders/" + orderId, buyer.token(), null, 200);
        assertThat((String) JsonPath.read(order, "$.status")).isEqualTo("PAID");
        assertThat(stock()).isEqualTo(stockWhilePaid);
    }
}
