package nexus.market.domain.valueobjects;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Value Object inmutable que representa una dirección postal. Todos los
 * campos son obligatorios excepto {@code complement}.
 */
public final class Address {

    private static final Pattern POSTAL_CODE_PATTERN = Pattern.compile("^[A-Za-z0-9-]{3,10}$");

    private final String street;
    private final String number;
    private final String complement;
    private final String neighborhood;
    private final String city;
    private final String state;
    private final String postalCode;
    private final String country;

    private Address(String street, String number, String complement, String neighborhood,
                    String city, String state, String postalCode, String country) {
        this.street = street;
        this.number = number;
        this.complement = complement;
        this.neighborhood = neighborhood;
        this.city = city;
        this.state = state;
        this.postalCode = postalCode;
        this.country = country;
    }

    public static Address of(String street, String number, String complement, String neighborhood,
                             String city, String state, String postalCode, String country) {
        requireNotBlank(street, "street");
        requireNotBlank(number, "number");
        requireNotBlank(neighborhood, "neighborhood");
        requireNotBlank(city, "city");
        requireNotBlank(state, "state");
        requireNotBlank(country, "country");

        if (postalCode == null || postalCode.isBlank()) {
            throw new IllegalArgumentException("postalCode es obligatorio");
        }
        if (postalCode.trim().length() > 10 || !POSTAL_CODE_PATTERN.matcher(postalCode.trim()).matches()) {
            throw new IllegalArgumentException("Formato de código postal inválido: " + postalCode);
        }

        return new Address(
                street.trim(), number.trim(),
                (complement == null) ? "" : complement.trim(),
                neighborhood.trim(), city.trim(), state.trim(),
                postalCode.trim(), country.trim());
    }

    private static void requireNotBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio en la dirección");
        }
    }

    public String getStreet() {
        return street;
    }

    public String getNumber() {
        return number;
    }

    public String getComplement() {
        return complement;
    }

    public String getNeighborhood() {
        return neighborhood;
    }

    public String getCity() {
        return city;
    }

    public String getState() {
        return state;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getCountry() {
        return country;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Address address = (Address) o;
        return street.equals(address.street)
                && number.equals(address.number)
                && complement.equals(address.complement)
                && neighborhood.equals(address.neighborhood)
                && city.equals(address.city)
                && state.equals(address.state)
                && postalCode.equals(address.postalCode)
                && country.equals(address.country);
    }

    @Override
    public int hashCode() {
        return Objects.hash(street, number, complement, neighborhood, city, state, postalCode, country);
    }

    @Override
    public String toString() {
        return street + " " + number
                + (complement.isBlank() ? "" : " " + complement)
                + ", " + neighborhood + ", " + city + ", " + state
                + " " + postalCode + ", " + country;
    }
}