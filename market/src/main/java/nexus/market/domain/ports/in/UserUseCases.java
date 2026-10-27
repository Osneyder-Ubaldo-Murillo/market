package nexus.market.domain.ports.in;

import nexus.market.domain.models.Buyer;
import nexus.market.domain.models.Seller;
import nexus.market.domain.models.User;
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
 * Puerto de entrada (caso de uso) de administración de usuarios, compradores
 * y vendedores. Delega en {@code UserManagementService}.
 */
public interface UserUseCases {

    Buyer registerBuyer(FullName fullName, Email email, DocumentId documentId, Address mainAddress);

    Seller registerSeller(FullName fullName, Email email, DocumentId documentId,
                          BusinessName businessName, TaxId taxId);

    void approveSeller(SellerId sellerId);

    void blockUser(UserId userId);

    void activateUser(UserId userId);

    void deactivateUser(UserId userId);

    void addBuyerAddress(BuyerId buyerId, Address address);

    User findUser(UserId userId);
}
