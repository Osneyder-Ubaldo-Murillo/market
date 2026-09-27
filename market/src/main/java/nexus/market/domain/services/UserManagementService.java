package nexus.market.domain.services;

import java.util.Objects;

import nexus.market.domain.enums.AuditSeverity;
import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.exceptions.UserAlreadyExistsException;
import nexus.market.domain.models.Buyer;
import nexus.market.domain.models.Seller;
import nexus.market.domain.models.User;
import nexus.market.domain.ports.out.BuyerRepositoryPort;
import nexus.market.domain.ports.out.SellerRepositoryPort;
import nexus.market.domain.ports.out.UserRepositoryPort;
import nexus.market.domain.specifications.UniqueUserSpecification;
import nexus.market.domain.valueobjects.Address;
import nexus.market.domain.valueobjects.BusinessName;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.DocumentId;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.FullName;
import nexus.market.domain.valueobjects.OperationType;
import nexus.market.domain.valueobjects.SellerId;
import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.TaxId;
import nexus.market.domain.valueobjects.UserId;

/**
 * Servicio de dominio de administración de usuarios, compradores y vendedores
 * (SDD - Domain Services: {@code UserManagementService}).
 *
 * <p>Reglas: unicidad global de {@code email}/{@code documentId}; solo un
 * Administrador puede registrar y aprobar vendedores (la autorización por rol
 * se valida en la capa de aplicación).</p>
 */
public class UserManagementService {

    private final UserRepositoryPort users;
    private final BuyerRepositoryPort buyers;
    private final SellerRepositoryPort sellers;
    private final UniqueUserSpecification uniqueUser;
    private final AuditService audit;

    public UserManagementService(UserRepositoryPort users, BuyerRepositoryPort buyers,
                                 SellerRepositoryPort sellers, UniqueUserSpecification uniqueUser,
                                 AuditService audit) {
        this.users = Objects.requireNonNull(users, "users es obligatorio");
        this.buyers = Objects.requireNonNull(buyers, "buyers es obligatorio");
        this.sellers = Objects.requireNonNull(sellers, "sellers es obligatorio");
        this.uniqueUser = Objects.requireNonNull(uniqueUser, "uniqueUser es obligatorio");
        this.audit = Objects.requireNonNull(audit, "audit es obligatorio");
    }

    /**
     * Registra un comprador: crea el {@code User} con rol {@code BUYER} y el
     * perfil {@code Buyer} en estado comercial {@code ACTIVE}.
     */
    public Buyer registerBuyer(FullName fullName, Email email, DocumentId documentId,
                               Address mainAddress) {
        requireUnique(email, documentId);

        User user = users.save(User.create(fullName, email, documentId, SystemRole.BUYER));
        Buyer buyer = buyers.save(Buyer.create(user.getUserId(), mainAddress));

        audit.record(OperationType.USER_REGISTRATION, AuditSeverity.INFO,
                user.getUserId().toString(), "Registro de comprador");
        audit.record(OperationType.BUYER_REGISTRATION, AuditSeverity.INFO,
                user.getUserId().toString(), "Perfil de comprador creado");
        return buyer;
    }

    /**
     * Registra un vendedor: crea el {@code User} con rol {@code SELLER} y el
     * perfil {@code Seller} en estado {@code PENDING_VERIFICATION} (solo el
     * Administrador, validado en la capa de aplicación).
     */
    public Seller registerSeller(FullName fullName, Email email, DocumentId documentId,
                                 BusinessName businessName, TaxId taxId) {
        requireUnique(email, documentId);

        User user = users.save(User.create(fullName, email, documentId, SystemRole.SELLER));
        Seller seller = sellers.save(Seller.create(user.getUserId(), businessName, taxId));

        audit.record(OperationType.SELLER_REGISTRATION, AuditSeverity.INFO,
                user.getUserId().toString(), "Registro de vendedor");
        return seller;
    }

    /** Aprueba la verificación de un vendedor ({@code PENDING_VERIFICATION → ACTIVE}). */
    public void approveSeller(SellerId sellerId) {
        Objects.requireNonNull(sellerId, "sellerId es obligatorio");
        Seller seller = loadSeller(sellerId);
        seller.approve();
        sellers.save(seller);
        audit.record(OperationType.SELLER_APPROVAL, AuditSeverity.INFO,
                seller.getUserId().toString(), "Vendedor aprobado: " + sellerId);
    }

    /** {@code ACTIVE → BLOCKED}. */
    public void blockUser(UserId userId) {
        User user = loadUser(userId);
        user.block();
        users.save(user);
        audit.record(OperationType.USER_BLOCK, AuditSeverity.WARNING,
                userId.toString(), "Usuario bloqueado: " + userId);
    }

    /** {@code BLOCKED → ACTIVE}. */
    public void activateUser(UserId userId) {
        User user = loadUser(userId);
        user.activate();
        users.save(user);
        audit.record(OperationType.USER_ACTIVATION, AuditSeverity.INFO,
                userId.toString(), "Usuario reactivado: " + userId);
    }

    /** {@code ACTIVE → INACTIVE}. */
    public void deactivateUser(UserId userId) {
        User user = loadUser(userId);
        user.deactivate();
        users.save(user);
        audit.record(OperationType.USER_DEACTIVATION, AuditSeverity.INFO,
                userId.toString(), "Usuario desactivado: " + userId);
    }

    /** Agrega una dirección secundaria (máx. 10, validado por {@code Buyer}). */
    public void addBuyerAddress(BuyerId buyerId, Address address) {
        Objects.requireNonNull(buyerId, "buyerId es obligatorio");
        Buyer buyer = buyers.findById(buyerId)
                .orElseThrow(() -> new BusinessException("BUYER_NOT_FOUND", "Comprador inexistente."));
        buyer.addAddress(address);
        buyers.save(buyer);
    }

    public User findUser(UserId userId) {
        return loadUser(userId);
    }

    private void requireUnique(Email email, DocumentId documentId) {
        Objects.requireNonNull(email, "email es obligatorio");
        Objects.requireNonNull(documentId, "documentId es obligatorio");
        if (!uniqueUser.isSatisfiedBy(email, documentId)) {
            throw new UserAlreadyExistsException(
                    "Ya existe un usuario con ese email o documento.");
        }
    }

    private User loadUser(UserId userId) {
        Objects.requireNonNull(userId, "userId es obligatorio");
        return users.findById(userId)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "Usuario inexistente."));
    }

    private Seller loadSeller(SellerId sellerId) {
        Objects.requireNonNull(sellerId, "sellerId es obligatorio");
        return sellers.findById(sellerId)
                .orElseThrow(() -> new BusinessException("SELLER_NOT_FOUND", "Vendedor inexistente."));
    }
}