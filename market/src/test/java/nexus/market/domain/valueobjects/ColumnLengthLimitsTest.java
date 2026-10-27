package nexus.market.domain.valueobjects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * Los objetos de valor rechazan (IllegalArgumentException → 400) lo que no cabe en las columnas
 * del esquema, y aceptan exactamente el límite.
 */
class ColumnLengthLimitsTest {

    private static Address address(String street, String number, String complement, String neighborhood,
                                   String city, String state, String postal, String country) {
        return Address.of(street, number, complement, neighborhood, city, state, postal, country);
    }

    private static String s(int n) {
        return "a".repeat(n);
    }

    @Test
    void email_atLimitIsValid_overLimitIsRejected() {
        String atLimit = s(150) + "@mail.test"; // 160
        assertThatCode(() -> Email.of(atLimit)).doesNotThrowAnyException();
        assertThatThrownBy(() -> Email.of(s(151) + "@mail.test"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("160");
    }

    @Test
    void businessName_atLimitIsValid_overLimitIsRejected() {
        assertThatCode(() -> BusinessName.of(s(160))).doesNotThrowAnyException();
        assertThatThrownBy(() -> BusinessName.of(s(161)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("160");
    }

    @Test
    void address_everyField_atLimitIsValid_overLimitIsRejected() {
        assertThatCode(() -> address(s(120), s(30), s(120), s(120), s(80), s(80), "1".repeat(10), s(80)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> address(s(121), "1", "", "n", "c", "s", "11111", "CO")).hasMessageContaining("street");
        assertThatThrownBy(() -> address("s", s(31), "", "n", "c", "s", "11111", "CO")).hasMessageContaining("number");
        assertThatThrownBy(() -> address("s", "1", s(121), "n", "c", "s", "11111", "CO")).hasMessageContaining("complement");
        assertThatThrownBy(() -> address("s", "1", "", s(121), "c", "s", "11111", "CO")).hasMessageContaining("neighborhood");
        assertThatThrownBy(() -> address("s", "1", "", "n", s(81), "s", "11111", "CO")).hasMessageContaining("city");
        assertThatThrownBy(() -> address("s", "1", "", "n", "c", s(81), "11111", "CO")).hasMessageContaining("state");
        assertThatThrownBy(() -> address("s", "1", "", "n", "c", "s", "11111", s(81))).hasMessageContaining("country");
        assertThatThrownBy(() -> address("s", "1", "", "n", "c", "s", "1".repeat(11), "CO"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deliveryInfo_trackingAndCarrierLimits() {
        assertThatCode(() -> DeliveryInfo.of(s(64), s(80), null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> DeliveryInfo.of(s(65), "DHL", null)).hasMessageContaining("trackingNumber");
        assertThatThrownBy(() -> DeliveryInfo.of("T1", s(81), null)).hasMessageContaining("carrier");
    }

    @Test
    void money_overDecimal15_2_isRejected_includingComputedTotals() {
        assertThatCode(() -> Money.of(new BigDecimal("9999999999999.99"), "COP")).doesNotThrowAnyException();
        assertThatThrownBy(() -> Money.of(new BigDecimal("10000000000000.00"), "COP"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("máximo");
        Money big = Money.of(new BigDecimal("5000000000000.00"), "COP");
        assertThatThrownBy(() -> big.multiply(Quantity.of(3))).isInstanceOf(IllegalArgumentException.class);
        assertThat(big.multiply(Quantity.of(1)).getAmount()).isEqualByComparingTo("5000000000000.00");
    }
}
