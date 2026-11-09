package nexus.market.application;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nexus.market.domain.models.Buyer;
import nexus.market.domain.models.Product;
import nexus.market.domain.models.Seller;
import nexus.market.domain.models.User;
import nexus.market.domain.ports.out.BuyerRepositoryPort;
import nexus.market.domain.ports.out.ProductRepositoryPort;
import nexus.market.domain.ports.out.SellerRepositoryPort;
import nexus.market.domain.ports.out.UserRepositoryPort;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.SellerId;
import nexus.market.domain.valueobjects.SystemRole;

/**
 * Datos descriptivos para reconocer registros sin depender del UUID: nombre comercial del vendedor, código del producto y
 * nombre del comprador. Es de solo lectura y no concede acceso: quién puede ver cada dato lo decide el controlador.
 */
@Service
@Profile("jpa")
@Transactional(readOnly = true)
public class IdentityDirectory {

    /** Vendedor y código comercial de un producto (el código es nulo en los productos anteriores a V4). */
    public record ProductLabel(String sellerId, String sellerName, String sellerSku) {
    }

    /** Nombre y correo de una persona. */
    public record PersonLabel(String fullName, String email) {
    }

    /** Perfiles asociados a una cuenta: identificadores de comprador o vendedor y nombre comercial. */
    public record ProfileRefs(String buyerId, String sellerId, String businessName) {
        public static final ProfileRefs NONE = new ProfileRefs(null, null, null);
    }

    private final ProductRepositoryPort products;
    private final SellerRepositoryPort sellers;
    private final BuyerRepositoryPort buyers;
    private final UserRepositoryPort users;

    public IdentityDirectory(ProductRepositoryPort products, SellerRepositoryPort sellers, BuyerRepositoryPort buyers,
                             UserRepositoryPort users) {
        this.products = Objects.requireNonNull(products, "products es obligatorio");
        this.sellers = Objects.requireNonNull(sellers, "sellers es obligatorio");
        this.buyers = Objects.requireNonNull(buyers, "buyers es obligatorio");
        this.users = Objects.requireNonNull(users, "users es obligatorio");
    }

    /** Nombre comercial de cada vendedor (clave: sellerId). Los que no existen no aparecen. */
    public Map<String, String> sellerNames(Collection<SellerId> sellerIds) {
        Map<String, String> names = new HashMap<>();
        for (SellerId id : sellerIds) {
            if (!names.containsKey(id.value())) {
                sellers.findById(id).ifPresent(seller -> names.put(id.value(), seller.getBusinessName().getValue()));
            }
        }
        return names;
    }

    /** Vendedor y código de cada producto (clave: productId). */
    public Map<String, ProductLabel> productLabels(Collection<ProductId> productIds) {
        Map<String, Product> loaded = new HashMap<>();
        for (ProductId id : productIds) {
            if (!loaded.containsKey(id.value())) {
                products.findById(id).ifPresent(product -> loaded.put(id.value(), product));
            }
        }
        Map<String, String> names = sellerNames(loaded.values().stream().map(Product::getSellerId).toList());
        Map<String, ProductLabel> labels = new HashMap<>();
        loaded.forEach((id, product) -> labels.put(id, new ProductLabel(product.getSellerId().value(),
                names.get(product.getSellerId().value()),
                product.getSellerSku() == null ? null : product.getSellerSku().getValue())));
        return labels;
    }

    /** Nombre completo de cada cuenta (clave: userId). Las que no existen no aparecen. */
    public Map<String, String> userNames(Collection<String> userIds) {
        Map<String, String> names = new HashMap<>();
        for (String id : userIds) {
            if (id != null && !names.containsKey(id)) {
                users.findById(nexus.market.domain.valueobjects.UserId.of(id))
                        .ifPresent(user -> names.put(id, user.getFullName().getValue()));
            }
        }
        return names;
    }

    /** Nombre y correo del comprador. */
    public Optional<PersonLabel> buyer(BuyerId buyerId) {
        return buyers.findById(buyerId)
                .flatMap(buyer -> users.findById(buyer.getUserId()))
                .map(user -> new PersonLabel(user.getFullName().getValue(), user.getEmail().getValue()));
    }

    /** Identificadores de perfil de una cuenta (para pasar de un correo o documento al buyerId o sellerId). */
    public ProfileRefs profile(User user) {
        if (user.getRole() == SystemRole.BUYER) {
            return buyers.findByUserId(user.getUserId())
                    .map((Buyer buyer) -> new ProfileRefs(buyer.getBuyerId().value(), null, null))
                    .orElse(ProfileRefs.NONE);
        }
        if (user.getRole() == SystemRole.SELLER) {
            return sellers.findByUserId(user.getUserId())
                    .map((Seller seller) -> new ProfileRefs(null, seller.getSellerId().value(),
                            seller.getBusinessName().getValue()))
                    .orElse(ProfileRefs.NONE);
        }
        return ProfileRefs.NONE;
    }
}
