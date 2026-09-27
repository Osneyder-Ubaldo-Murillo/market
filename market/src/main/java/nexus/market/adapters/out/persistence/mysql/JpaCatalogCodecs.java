package nexus.market.adapters.out.persistence.mysql;

import nexus.market.domain.valueobjects.CommercialStatus;
import nexus.market.domain.valueobjects.InventoryStatus;
import nexus.market.domain.valueobjects.InvoiceStatus;
import nexus.market.domain.valueobjects.OrderStatus;
import nexus.market.domain.valueobjects.ProductStatus;
import nexus.market.domain.valueobjects.ProductType;
import nexus.market.domain.valueobjects.RefundStatus;
import nexus.market.domain.valueobjects.ReturnReason;
import nexus.market.domain.valueobjects.ReturnStatus;
import nexus.market.domain.valueobjects.SellerStatus;
import nexus.market.domain.valueobjects.ShippingStatus;
import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.UserStatus;
import nexus.market.domain.valueobjects.WarehouseStatus;
import nexus.market.domain.valueobjects.WarehouseType;

/**
 * Conversor cadena {@code ->} catálogo de negocio para el adaptador JPA.
 *
 * <p>Los catálogos de negocio no son enums de Java (ver {@code DomainCatalog}),
 * por lo que las entidades persistidas almacenan su {@code code} como texto y
 * este código lo convierte de vuelta a la instancia canónica del dominio.</p>
 */
final class JpaCatalogCodecs {

    static final JpaCatalogCodecs INSTANCE = new JpaCatalogCodecs();

    private JpaCatalogCodecs() {
    }

    SystemRole systemRole(String code) {
        return switch (code) {
            case "BUYER" -> SystemRole.BUYER;
            case "SELLER" -> SystemRole.SELLER;
            case "LOGISTICS_OPERATOR" -> SystemRole.LOGISTICS_OPERATOR;
            case "ADMIN" -> SystemRole.ADMIN;
            case "SUPERVISOR" -> SystemRole.SUPERVISOR;
            default -> throw new IllegalArgumentException("Rol desconocido: " + code);
        };
    }

    UserStatus userStatus(String code) {
        return switch (code) {
            case "ACTIVE" -> UserStatus.ACTIVE;
            case "BLOCKED" -> UserStatus.BLOCKED;
            case "INACTIVE" -> UserStatus.INACTIVE;
            default -> throw new IllegalArgumentException("Estado de usuario desconocido: " + code);
        };
    }

    CommercialStatus commercialStatus(String code) {
        return switch (code) {
            case "ACTIVE" -> CommercialStatus.ACTIVE;
            case "BLOCKED" -> CommercialStatus.BLOCKED;
            case "INACTIVE" -> CommercialStatus.INACTIVE;
            default -> throw new IllegalArgumentException("Estado comercial desconocido: " + code);
        };
    }

    SellerStatus sellerStatus(String code) {
        return switch (code) {
            case "ACTIVE" -> SellerStatus.ACTIVE;
            case "BLOCKED" -> SellerStatus.BLOCKED;
            case "INACTIVE" -> SellerStatus.INACTIVE;
            case "PENDING_VERIFICATION" -> SellerStatus.PENDING_VERIFICATION;
            default -> throw new IllegalArgumentException("Estado de vendedor desconocido: " + code);
        };
    }

    WarehouseStatus warehouseStatus(String code) {
        return switch (code) {
            case "ACTIVE" -> WarehouseStatus.ACTIVE;
            case "INACTIVE" -> WarehouseStatus.INACTIVE;
            default -> throw new IllegalArgumentException("Estado de bodega desconocido: " + code);
        };
    }

    WarehouseType warehouseType(String code) {
        return switch (code) {
            case "MARKETPLACE" -> WarehouseType.MARKETPLACE;
            case "SELLER" -> WarehouseType.SELLER;
            default -> throw new IllegalArgumentException("Tipo de bodega desconocido: " + code);
        };
    }
ProductStatus productStatus(String code) {
        return switch (code) {
            case "ACTIVE" -> ProductStatus.ACTIVE;
            case "INACTIVE" -> ProductStatus.INACTIVE;
            case "OUT_OF_STOCK" -> ProductStatus.OUT_OF_STOCK;
            default -> throw new IllegalArgumentException("Estado de producto desconocido: " + code);
        };
    }

    ProductType productType(String code) {
        return switch (code) {
            case "PHYSICAL" -> ProductType.PHYSICAL;
            case "DIGITAL" -> ProductType.DIGITAL;
            default -> throw new IllegalArgumentException("Tipo de producto desconocido: " + code);
        };
    }

    InventoryStatus inventoryStatus(String code) {
        return switch (code) {
            case "ACTIVE" -> InventoryStatus.ACTIVE;
            case "OUT_OF_STOCK" -> InventoryStatus.OUT_OF_STOCK;
            case "DAMAGED" -> InventoryStatus.DAMAGED;
            default -> throw new IllegalArgumentException("Estado de inventario desconocido: " + code);
        };
    }

    OrderStatus orderStatus(String code) {
        return switch (code) {
            case "CART" -> OrderStatus.CART;
            case "PENDING_PAYMENT" -> OrderStatus.PENDING_PAYMENT;
            case "PAID" -> OrderStatus.PAID;
            case "DISPATCHED" -> OrderStatus.DISPATCHED;
            case "DELIVERED" -> OrderStatus.DELIVERED;
            case "CANCELLED" -> OrderStatus.CANCELLED;
            default -> throw new IllegalArgumentException("Estado de pedido desconocido: " + code);
        };
    }

    InvoiceStatus invoiceStatus(String code) {
        return switch (code) {
            case "ISSUED" -> InvoiceStatus.ISSUED;
            case "CANCELLED" -> InvoiceStatus.CANCELLED;
            default -> throw new IllegalArgumentException("Estado de factura desconocido: " + code);
        };
    }

    ShippingStatus shippingStatus(String code) {
        return switch (code) {
            case "PREPARING" -> ShippingStatus.PREPARING;
            case "DISPATCHED" -> ShippingStatus.DISPATCHED;
            case "IN_TRANSIT" -> ShippingStatus.IN_TRANSIT;
            case "DELIVERED" -> ShippingStatus.DELIVERED;
            default -> throw new IllegalArgumentException("Estado de envío desconocido: " + code);
        };
    }

    ReturnStatus returnStatus(String code) {
        return switch (code) {
            case "REQUESTED" -> ReturnStatus.REQUESTED;
            case "APPROVED" -> ReturnStatus.APPROVED;
            case "REJECTED" -> ReturnStatus.REJECTED;
            case "PROCESSED" -> ReturnStatus.PROCESSED;
            default -> throw new IllegalArgumentException("Estado de devolución desconocido: " + code);
        };
    }

    RefundStatus refundStatus(String code) {
        return switch (code) {
            case "PENDING" -> RefundStatus.PENDING;
            case "PROCESSED" -> RefundStatus.PROCESSED;
            case "FAILED" -> RefundStatus.FAILED;
            default -> throw new IllegalArgumentException("Estado de reembolso desconocido: " + code);
        };
    }

    ReturnReason returnReason(String code) {
        return switch (code) {
            case "DAMAGED" -> ReturnReason.DAMAGED;
            case "WRONG_ITEM" -> ReturnReason.WRONG_ITEM;
            case "NOT_AS_DESCRIBED" -> ReturnReason.NOT_AS_DESCRIBED;
            case "OTHER" -> ReturnReason.OTHER;
            default -> throw new IllegalArgumentException("Motivo de devolución desconocido: " + code);
        };
    }
}