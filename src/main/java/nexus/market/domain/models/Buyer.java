package nexus.market.domain.models;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.valueobjects.Address;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.CommercialStatus;
import nexus.market.domain.valueobjects.UserId;

/**
 * Agregado raíz que extiende la información de un {@link User} con rol
 * {@code BUYER}. El comprador nunca puede administrar información de otros
 * compradores; un comprador {@code ACTIVE} es el único que puede crear pedidos.
 */
public class Buyer {

    public static final int MAX_ADDITIONAL_ADDRESSES = 10;

    private final BuyerId buyerId;
    private final UserId userId;
    private Address mainAddress;
    private final List<Address> additionalAddresses = new ArrayList<>();
    private CommercialStatus commercialStatus;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Buyer(BuyerId buyerId, UserId userId, Address mainAddress, List<Address> additionalAddresses,
                 CommercialStatus commercialStatus, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.buyerId = Objects.requireNonNull(buyerId, "buyerId es obligatorio");
        this.userId = Objects.requireNonNull(userId, "userId es obligatorio");
        this.mainAddress = Objects.requireNonNull(mainAddress, "mainAddress es obligatorio");
        if (additionalAddresses != null) {
            if (additionalAddresses.size() > MAX_ADDITIONAL_ADDRESSES) {
                throw new BusinessException("ADDRESS_LIMIT_EXCEEDED",
                        "El comprador no puede tener más de 10 direcciones adicionales.");
            }
            this.additionalAddresses.addAll(additionalAddresses);
        }
        this.commercialStatus = Objects.requireNonNull(commercialStatus, "commercialStatus es obligatorio");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt es obligatorio");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt es obligatorio");
    }

    /**
     * Crea un comprador nuevo en estado comercial {@code ACTIVE}.
     */
    public static Buyer create(UserId userId, Address mainAddress) {
        LocalDateTime now = LocalDateTime.now();
        return new Buyer(BuyerId.generate(), userId, mainAddress, List.of(),
                CommercialStatus.ACTIVE, now, now);
    }

    /**
     * Agrega una dirección secundaria validando el límite de 10.
     */
    public void addAddress(Address address) {
        Objects.requireNonNull(address, "address es obligatorio");
        if (additionalAddresses.size() >= MAX_ADDITIONAL_ADDRESSES) {
            throw new BusinessException("ADDRESS_LIMIT_EXCEEDED",
                    "El comprador no puede tener más de 10 direcciones adicionales.");
        }
        additionalAddresses.add(address);
        touch();
    }

    public void changeMainAddress(Address newAddress) {
        this.mainAddress = Objects.requireNonNull(newAddress, "newAddress es obligatorio");
        touch();
    }

    public void block() {
        requireActive();
        this.commercialStatus = CommercialStatus.BLOCKED;
        touch();
    }

    public void activate() {
        if (commercialStatus != CommercialStatus.BLOCKED) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "Solo un comprador BLOCKED puede volver al estado ACTIVE.");
        }
        this.commercialStatus = CommercialStatus.ACTIVE;
        touch();
    }

    public void deactivate() {
        requireActive();
        this.commercialStatus = CommercialStatus.INACTIVE;
        touch();
    }

    private void requireActive() {
        if (commercialStatus != CommercialStatus.ACTIVE) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "La operación requiere un comprador con estado comercial ACTIVE.");
        }
    }

    private void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    public BuyerId getBuyerId() {
        return buyerId;
    }

    public UserId getUserId() {
        return userId;
    }

    public Address getMainAddress() {
        return mainAddress;
    }

    public List<Address> getAdditionalAddresses() {
        return Collections.unmodifiableList(additionalAddresses);
    }

    public CommercialStatus getCommercialStatus() {
        return commercialStatus;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}