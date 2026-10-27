package nexus.market.application;

import java.util.Objects;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import nexus.market.domain.ports.in.UserUseCases;
import nexus.market.domain.models.Buyer;
import nexus.market.domain.models.Seller;
import nexus.market.domain.models.User;
import nexus.market.domain.services.UserManagementService;
import nexus.market.domain.valueobjects.Address;
import nexus.market.domain.valueobjects.BusinessName;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.DocumentId;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.FullName;
import nexus.market.domain.valueobjects.SellerId;
import nexus.market.domain.valueobjects.TaxId;
import nexus.market.domain.valueobjects.UserId;

/**
 * Servicio de aplicación de administración de usuarios: delega en
 * {@code UserManagementService}. La autorización por rol se validará en la
 * capa de seguridad HTTP (tarea 5).
 */
@Service
@Profile("jpa")
public class UserApplicationService implements UserUseCases {

    private final UserManagementService delegate;

    public UserApplicationService(UserManagementService delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate es obligatorio");
    }

    @Override
    public Buyer registerBuyer(FullName fullName, Email email, DocumentId documentId, Address mainAddress) {
        return delegate.registerBuyer(fullName, email, documentId, mainAddress);
    }

    @Override
    public Seller registerSeller(FullName fullName, Email email, DocumentId documentId,
                                 BusinessName businessName, TaxId taxId) {
        return delegate.registerSeller(fullName, email, documentId, businessName, taxId);
    }

    @Override
    public void approveSeller(SellerId sellerId) {
        delegate.approveSeller(sellerId);
    }

    @Override
    public void blockUser(UserId userId) {
        delegate.blockUser(userId);
    }

    @Override
    public void activateUser(UserId userId) {
        delegate.activateUser(userId);
    }

    @Override
    public void deactivateUser(UserId userId) {
        delegate.deactivateUser(userId);
    }

    @Override
    public void addBuyerAddress(BuyerId buyerId, Address address) {
        delegate.addBuyerAddress(buyerId, address);
    }

    @Override
    public User findUser(UserId userId) {
        return delegate.findUser(userId);
    }
}
