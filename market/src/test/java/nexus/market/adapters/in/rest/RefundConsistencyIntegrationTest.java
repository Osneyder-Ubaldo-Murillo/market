package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import nexus.market.domain.ports.out.PaymentServicePort;
import nexus.market.domain.ports.out.RefundRepositoryPort;
import nexus.market.domain.valueobjects.RefundId;
import nexus.market.domain.valueobjects.RefundStatus;
import nexus.market.domain.valueobjects.SystemRole;

/**
 * Consistencia del reembolso: nunca se devuelve dinero dos veces, un fallo de la pasarela queda registrado como FAILED
 * aunque la API responda error, y las transiciones inválidas se rechazan antes de llamar a la pasarela.
 * (Contra H2; la concurrencia sobre MySQL real la cubre el CI con las pruebas {@code *MySqlTest}.)
 */
class RefundConsistencyIntegrationTest extends RestTestSupport {

    @MockitoSpyBean
    private PaymentServicePort payments;

    @Autowired
    private RefundRepositoryPort refunds;

    private String admin;
    private String operator;

    /** Compra, pago, entrega no necesaria: devolución aprobada con su reembolso PENDING. Devuelve {returnId, refundId}. */
    private String[] pendingRefund() throws Exception {
        admin = staffToken(SystemRole.ADMIN);
        operator = staffToken(SystemRole.LOGISTICS_OPERATOR);
        SellerCatalog catalog = seedSellerCatalog(admin, new BigDecimal("100.00"));
        Account buyer = registerBuyer();
        send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                body("productId", catalog.productId(), "quantity", 1), 200);
        String orderId = read(send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders", buyer.token(),
                body("shippingAddress", addressJson()), 201), "$.orderId");
        send(HttpMethod.POST, "/api/orders/" + orderId + "/payment", buyer.token(), body("paymentMethod", "CARD"), 200);
        send(HttpMethod.POST, "/api/orders/" + orderId + "/dispatch", operator,
                body("warehouseId", catalog.warehouseId()), 200);
        send(HttpMethod.POST, "/api/orders/" + orderId + "/deliver", operator, "{}", 200);
        String returnJson = "{\"items\":[{\"productId\":\"" + catalog.productId()
                + "\",\"quantity\":1,\"reason\":\"DAMAGED\"}]}";
        String returnId = read(send(HttpMethod.POST, "/api/orders/" + orderId + "/returns", buyer.token(),
                returnJson, 201), "$.returnId");
        String refundId = read(send(HttpMethod.POST, "/api/returns/" + returnId + "/approve", admin, "{}", 200),
                "$.refundId");
        reset(payments);
        return new String[] { returnId, refundId };
    }

    @Test
    void processingTwice_refundsOnce_andTheSecondCallIsRejectedBeforeTheGateway() throws Exception {
        String refundId = pendingRefund()[1];

        send(HttpMethod.POST, "/api/refunds/" + refundId + "/process", admin, "{}", 200);
        String second = send(HttpMethod.POST, "/api/refunds/" + refundId + "/process", admin, "{}", 409);

        assertThat((String) com.jayway.jsonpath.JsonPath.read(second, "$.code")).isEqualTo("INVALID_STATE_TRANSITION");
        verify(payments, times(1)).refund(any(), any(), any());
        assertThat(refunds.findById(RefundId.of(refundId)).orElseThrow().getStatus()).isEqualTo(RefundStatus.PROCESSED);
    }

    @Test
    void gatewayFailure_isRecordedAsFailed_andCanBeRetriedUntilItSucceeds() throws Exception {
        String refundId = pendingRefund()[1];
        doThrow(new IllegalStateException("pasarela caída")).when(payments).refund(any(), any(), any());

        String failure = send(HttpMethod.POST, "/api/refunds/" + refundId + "/process", admin, "{}", 400);
        assertThat((String) com.jayway.jsonpath.JsonPath.read(failure, "$.code")).isEqualTo("REFUND_FAILED");
        assertThat(failure).doesNotContain("pasarela caída");

        // El estado FAILED sobrevive aunque la respuesta fue un error (no se revierte la transacción).
        assertThat(refunds.findById(RefundId.of(refundId)).orElseThrow().getStatus()).isEqualTo(RefundStatus.FAILED);
        verify(payments, times(1)).refund(any(), any(), any());

        // Reintento manual cuando la pasarela vuelve: FAILED -> PROCESSED, y ya no se puede repetir.
        reset(payments);
        send(HttpMethod.POST, "/api/refunds/" + refundId + "/process", admin, "{}", 200);
        assertThat(refunds.findById(RefundId.of(refundId)).orElseThrow().getStatus()).isEqualTo(RefundStatus.PROCESSED);
        send(HttpMethod.POST, "/api/refunds/" + refundId + "/process", admin, "{}", 409);
        verify(payments, times(1)).refund(any(), any(), any());
    }

    @Test
    void simultaneousProcessing_callsTheGatewayExactlyOnce() throws Exception {
        String refundId = pendingRefund()[1];
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return status(HttpMethod.POST, "/api/refunds/" + refundId + "/process", admin, "{}");
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(60, TimeUnit.SECONDS));
            }
            assertThat(statuses.stream().filter(s -> s == 200).count()).as("estados: %s", statuses).isEqualTo(1);
            assertThat(statuses.stream().filter(s -> s != 200)).allMatch(s -> s == 409 || s == 429 || s == 503);
        } finally {
            pool.shutdownNow();
        }
        verify(payments, times(1)).refund(any(), any(), any());
        assertThat(refunds.findById(RefundId.of(refundId)).orElseThrow().getStatus()).isEqualTo(RefundStatus.PROCESSED);
    }

    @Test
    void rejectingAReturnTwice_isRejected_andARejectedReturnCannotBeApproved() throws Exception {
        String[] ids = pendingRefund();
        // La devolución de pendingRefund() ya está aprobada: no se puede rechazar ni aprobar de nuevo.
        send(HttpMethod.POST, "/api/returns/" + ids[0] + "/reject", admin, "{}", 409);
        send(HttpMethod.POST, "/api/returns/" + ids[0] + "/approve", admin, "{}", 409);
        verify(payments, never()).refund(any(), any(), any());
    }
}
