package nexus.market.domain.valueobjects;

import java.math.BigDecimal;
import java.util.Currency;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueba de los invariantes del Value Object {@code Money}.
 * Validación: monto nunca negativo, escala 2 (HALF_UP) y operaciones en la
 * misma moneda.
 */
class MoneyTest {

    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void ofCreaMontoConEscalaDos() {
        Money money = Money.of(new BigDecimal("10.126"), USD);
        assertEquals(0, money.getAmount().compareTo(new BigDecimal("10.13")));
        assertEquals(2, money.getAmount().scale());
    }

    @Test
    void ofRechazaMontosNegativos() {
        assertThrows(IllegalArgumentException.class,
                () -> Money.of(new BigDecimal("-5"), USD));
    }

    @Test
    void addSumaEnLaMismaMoneda() {
        Money total = Money.of(10.5, "USD").add(Money.of(4.2, "USD"));
        assertEquals(0, total.getAmount().compareTo(new BigDecimal("14.70")));
    }

    @Test
    void addRechazaMonedasDistintas() {
        assertThrows(IllegalArgumentException.class,
                () -> Money.of(1, "USD").add(Money.of(1, "COP")));
    }

    @Test
    void multiplyPorCantidadCalculaTotal() {
        Money total = Money.of(2.5, "USD").multiply(Quantity.of(3));
        assertEquals(0, total.getAmount().compareTo(new BigDecimal("7.50")));
    }

    @Test
    void subtractNoPermiteResultadoNegativo() {
        assertThrows(IllegalArgumentException.class,
                () -> Money.of(3, "USD").subtract(Money.of(5, "USD")));
    }

    @Test
    void igualdadComparaValorYMoneda() {
        assertEquals(Money.of(10, "USD"), Money.of(new BigDecimal("10.000"), USD));
        assertNotEquals(Money.of(10, "USD"), Money.of(10, "COP"));
    }

    @Test
    void zeroEsEquivalenteAUnValorCero() {
        assertTrue(Money.zero(USD).compareTo(Money.of(0, "USD")) == 0);
    }
}