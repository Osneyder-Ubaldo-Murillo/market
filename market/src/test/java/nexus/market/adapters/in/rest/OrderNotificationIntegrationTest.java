package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import nexus.market.domain.enums.NotificationChannel;
import nexus.market.domain.ports.out.NotificationPort;
import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.UserId;

/**
 * El flujo de pedido y envío avisa al comprador por {@link NotificationPort} (hoy, un adaptador que escribe en el log):
 * creado, pagado, despachado, en tránsito, entregado y cancelado. Solo se avisa de lo que se confirmó, y un fallo al
 * notificar no rompe la operación de negocio.
 */
class OrderNotificationIntegrationTest extends RestTestSupport {

    @MockitoSpyBean
    private NotificationPort notifications;

    @Autowired
    private nexus.market.domain.ports.out.UserRepositoryPort userRepository;

    private String emailOf(Account account) {
        return userRepository.findById(UserId.of(account.userId())).orElseThrow().getEmail().getValue();
    }

    private List<String> subjectsSentTo(String email) {
        ArgumentCaptor<String> recipient = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subjects = ArgumentCaptor.forClass(String.class);
        verify(notifications, atLeastOnce()).send(any(), recipient.capture(), subjects.capture(), anyString());
        List<String> result = new java.util.ArrayList<>();
        for (int i = 0; i < recipient.getAllValues().size(); i++) {
            if (recipient.getAllValues().get(i).equals(email)) {
                result.add(subjects.getAllValues().get(i));
            }
        }
        return result;
    }

    @Test
    void fullOrderAndShippingFlow_notifiesTheBuyerInOrder() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        String operator = staffToken(SystemRole.LOGISTICS_OPERATOR);
        SellerCatalog catalog = seedSellerCatalog(admin, new BigDecimal("100.00"));
        Account buyer = registerBuyer();
        String email = emailOf(buyer);
        clearInvocations(notifications);

        send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                body("productId", catalog.productId(), "quantity", 1), 200);
        String orderId = read(send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders", buyer.token(),
                body("shippingAddress", addressJson()), 201), "$.orderId");
        send(HttpMethod.POST, "/api/orders/" + orderId + "/payment", buyer.token(), body("paymentMethod", "CARD"), 200);
        send(HttpMethod.POST, "/api/orders/" + orderId + "/dispatch", operator,
                body("warehouseId", catalog.warehouseId()), 200);
        String shippingId = read(send(HttpMethod.GET, "/api/orders/" + orderId + "/shipping", operator, null, 200),
                "$.shippingId");
        send(HttpMethod.POST, "/api/shippings/" + shippingId + "/dispatch", operator,
                body("trackingNumber", "TRK-" + uniq(), "carrier", "TCC"), 200);
        send(HttpMethod.POST, "/api/shippings/" + shippingId + "/in-transit", operator, "{}", 200);
        send(HttpMethod.POST, "/api/shippings/" + shippingId + "/deliver", operator, "{}", 200);

        assertThat(subjectsSentTo(email)).containsExactly("Pedido creado", "Pago confirmado", "Pedido despachado",
                "Envío despachado", "Envío en tránsito", "Envío entregado");
        verify(notifications, atLeastOnce()).send(eq(NotificationChannel.EMAIL), eq(email), anyString(),
                org.mockito.ArgumentMatchers.contains(orderId));
    }

    @Test
    void cancelledOrder_notifiesTheBuyer() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog catalog = seedSellerCatalog(admin, new BigDecimal("100.00"));
        Account buyer = registerBuyer();
        String email = emailOf(buyer);
        send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                body("productId", catalog.productId(), "quantity", 1), 200);
        String orderId = read(send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders", buyer.token(),
                body("shippingAddress", addressJson()), 201), "$.orderId");
        clearInvocations(notifications);

        send(HttpMethod.POST, "/api/orders/" + orderId + "/cancel", buyer.token(), "{}", 200);

        assertThat(subjectsSentTo(email)).containsExactly("Pedido cancelado");
    }

    @Test
    void rejectedOperation_doesNotNotify() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog catalog = seedSellerCatalog(admin, new BigDecimal("100.00"));
        Account buyer = registerBuyer();
        send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                body("productId", catalog.productId(), "quantity", 1), 200);
        String orderId = read(send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders", buyer.token(),
                body("shippingAddress", addressJson()), 201), "$.orderId");
        send(HttpMethod.POST, "/api/orders/" + orderId + "/payment", buyer.token(), body("paymentMethod", "CARD"), 200);
        clearInvocations(notifications);

        // Pagar de nuevo falla: no hay nada nuevo que avisar.
        int status = status(HttpMethod.POST, "/api/orders/" + orderId + "/payment", buyer.token(),
                body("paymentMethod", "CARD"));
        assertThat(status).isGreaterThanOrEqualTo(400);
        verify(notifications, never()).send(any(), anyString(), eq("Pago confirmado"), anyString());
    }

    @Test
    void notificationFailure_neverBreaksTheBusinessOperation() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        SellerCatalog catalog = seedSellerCatalog(admin, new BigDecimal("100.00"));
        Account buyer = registerBuyer();
        send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                body("productId", catalog.productId(), "quantity", 1), 200);
        doThrow(new IllegalStateException("proveedor caído")).when(notifications).send(any(), anyString(), anyString(),
                anyString());
        try {
            String orderId = read(send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders",
                    buyer.token(), body("shippingAddress", addressJson()), 201), "$.orderId");
            send(HttpMethod.POST, "/api/orders/" + orderId + "/payment", buyer.token(),
                    body("paymentMethod", "CARD"), 200);
            String order = send(HttpMethod.GET, "/api/orders/" + orderId, buyer.token(), null, 200);
            assertThat((String) com.jayway.jsonpath.JsonPath.read(order, "$.status")).isEqualTo("PAID");
        } finally {
            org.mockito.Mockito.reset(notifications);
        }
    }
}
