package nexus.market.domain.valueobjects;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Value Object inmutable que representa una cantidad monetaria.
 * El importe se almacena con escala 2 (redondeo HALF_UP) y nunca es negativo.
 */
public final class Money {

    private static final int SCALE = 2;

    private final BigDecimal amount;
    private final Currency currency;

    private Money(BigDecimal amount, Currency currency) {
        this.amount = amount.setScale(SCALE, RoundingMode.HALF_UP);
        this.currency = currency;
    }

    public static Money of(BigDecimal amount, Currency currency) {
        if (amount == null) {
            throw new IllegalArgumentException("amount es obligatorio");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("El monto no puede ser negativo");
        }
        if (currency == null) {
            throw new IllegalArgumentException("currency es obligatorio");
        }
        return new Money(amount, currency);
    }

    public static Money of(double amount, String currencyCode) {
        return of(BigDecimal.valueOf(amount), Currency.getInstance(currencyCode));
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        return of(amount, Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return of(BigDecimal.ZERO, currency);
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Currency getCurrency() {
        return currency;
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money multiply(BigDecimal factor) {
        if (factor == null) {
            throw new IllegalArgumentException("factor es obligatorio");
        }
        if (factor.signum() < 0) {
            throw new IllegalArgumentException("El factor de multiplicación no puede ser negativo");
        }
        return new Money(amount.multiply(factor), currency);
    }

    public Money multiply(Quantity quantity) {
        Objects.requireNonNull(quantity, "quantity es obligatorio");
        return multiply(BigDecimal.valueOf(quantity.value()));
    }

    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        if (other == null) {
            throw new IllegalArgumentException("other es obligatorio");
        }
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "No se pueden operar montos en monedas distintas: "
                            + currency + " vs " + other.currency);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Money money = (Money) o;
        return amount.compareTo(money.amount) == 0 && currency.equals(money.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }

    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + amount.toPlainString();
    }
}