package nexus.market.adapters.out.persistence.mysql;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import nexus.market.domain.enums.InventoryMovementType;
import nexus.market.domain.models.Inventory;
import nexus.market.domain.valueobjects.Address;
import nexus.market.domain.valueobjects.CartItem;
import nexus.market.domain.valueobjects.DeliveryInfo;
import nexus.market.domain.valueobjects.InventoryMovement;
import nexus.market.domain.valueobjects.Money;
import nexus.market.domain.valueobjects.OrderItem;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.ProductName;
import nexus.market.domain.valueobjects.ProductType;
import nexus.market.domain.valueobjects.Quantity;
import nexus.market.domain.valueobjects.ReturnItem;
import nexus.market.domain.valueobjects.ReturnReason;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * Tipos {@code @Embeddable} del adaptador de persistencia JPA (MySQL).
 *
 * <p>Estas clases viven EXCLUSIVAMENTE en el adaptador (nunca en el paquete
 * {@code domain}): su única responsabilidad es serializar/deserializar los
 * Value Objects de negocio hacia columnas de base de datos y viceversa
 * (restricción 4 de *Software Architecture.md*).</p>
 */
final class JpaValueTypes {

    /** Serialización de Maps (metadatos de movimientos y especificaciones de producto) a JSON. */
    static final class JsonCodec {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        private JsonCodec() {
        }

        static String toJson(Map<String, Object> metadata) {
            if (metadata == null) {
                return "{}";
            }
            try {
                return MAPPER.writeValueAsString(metadata);
            } catch (Exception ex) {
                return "{}";
            }
        }

        static Map<String, Object> fromJson(String json) {
            if (json == null || json.isBlank()) {
                return new HashMap<>();
            }
            try {
                return new HashMap<>(MAPPER.readValue(json,
                        new TypeReference<Map<String, Object>>() {
                        }));
            } catch (Exception ex) {
                return new HashMap<>();
            }
        }
    }

    private JpaValueTypes() {
    }

    // ------------------------------------------------------------------
    // Dirección postal (Buyer, Warehouse, Order)
    // ------------------------------------------------------------------

    @Embeddable
    static class AddressValue {

        @Column(name = "street", length = 120)
        String street;

        @Column(name = "number", length = 30)
        String number;

        @Column(name = "complement", length = 120)
        String complement;

        @Column(name = "neighborhood", length = 120)
        String neighborhood;

        @Column(name = "city", length = 80)
        String city;

        @Column(name = "state", length = 80)
        String state;

        @Column(name = "postal_code", length = 10)
        String postalCode;

        @Column(name = "country", length = 80)
        String country;

        protected AddressValue() {
        }

        static AddressValue from(Address address) {
            AddressValue value = new AddressValue();
            value.street = address.getStreet();
            value.number = address.getNumber();
            value.complement = address.getComplement();
            value.neighborhood = address.getNeighborhood();
            value.city = address.getCity();
            value.state = address.getState();
            value.postalCode = address.getPostalCode();
            value.country = address.getCountry();
            return value;
        }

        Address toDomain() {
            return Address.of(street, number, complement, neighborhood,
                    city, state, postalCode, country);
        }
    }

    // ------------------------------------------------------------------
    // Referencia a una bodega (colección de WarehouseId en Seller)
    // ------------------------------------------------------------------

    @Embeddable
    static class WarehouseRefValue {

        @Column(name = "warehouse_id", length = 36)
        String warehouseId;

        protected WarehouseRefValue() {
        }

        static WarehouseRefValue from(WarehouseId id) {
            WarehouseRefValue value = new WarehouseRefValue();
            value.warehouseId = id.value();
            return value;
        }

        WarehouseId toDomain() {
            return WarehouseId.of(warehouseId);
        }
    }
// ------------------------------------------------------------------
    // Ítem de carrito / pedido (CartItem y OrderItem comparten estructura)
    // ------------------------------------------------------------------

    @Embeddable
    static class ItemValue {

        @Column(name = "product_id", length = 36)
        String productId;

        @Column(name = "product_name", length = 120)
        String productName;

        @Column(name = "quantity")
        int quantity;

        @Column(name = "unit_amount", precision = 15, scale = 2)
        BigDecimal unitAmount;

        @Column(name = "unit_currency", length = 3)
        String unitCurrency;

        @Column(name = "total_amount", precision = 15, scale = 2)
        BigDecimal totalAmount;

        @Column(name = "total_currency", length = 3)
        String totalCurrency;

        @Column(name = "product_type", length = 30)
        String productType;

        protected ItemValue() {
        }

        static ItemValue fromCartItem(CartItem item) {
            ItemValue value = new ItemValue();
            fill(item.getProductId(), item.getProductName(), item.getQuantity(),
                    item.getUnitPrice(), item.getTotal(), item.getProductType(), value);
            return value;
        }

        static ItemValue fromOrderItem(OrderItem item) {
            ItemValue value = new ItemValue();
            fill(item.getProductId(), item.getProductName(), item.getQuantity(),
                    item.getUnitPrice(), item.getTotal(), item.getProductType(), value);
            return value;
        }

        private static void fill(ProductId productId, ProductName productName,
                                 Quantity quantity, Money unitPrice, Money total,
                                 ProductType productType, ItemValue value) {
            value.productId = productId.value();
            value.productName = productName.value();
            value.quantity = quantity.value();
            value.unitAmount = unitPrice.getAmount();
            value.unitCurrency = unitPrice.getCurrency().getCurrencyCode();
            value.totalAmount = total.getAmount();
            value.totalCurrency = total.getCurrency().getCurrencyCode();
            value.productType = productType.getCode();
        }

        CartItem toCartItem() {
            return CartItem.of(ProductId.of(productId), ProductName.of(productName),
                    Quantity.of(quantity), money(unitAmount, unitCurrency),
                    catalogs().productType(productType));
        }

        OrderItem toOrderItem() {
            return OrderItem.of(ProductId.of(productId), ProductName.of(productName),
                    Quantity.of(quantity), money(unitAmount, unitCurrency),
                    catalogs().productType(productType));
        }
    }

    // ------------------------------------------------------------------
    // Movimiento de inventario (Inventory.movements)
    // ------------------------------------------------------------------

    @Embeddable
    static class InventoryMovementValue {

        @Enumerated(EnumType.STRING)
        @Column(name = "type", length = 30)
        InventoryMovementType type;

        @Column(name = "quantity")
        int quantity;

        @Column(name = "occurred_at")
        LocalDateTime occurredAt;

        @Lob
        @Column(name = "metadata_json")
        String metadataJson;

        protected InventoryMovementValue() {
        }

        static InventoryMovementValue from(InventoryMovement movement) {
            InventoryMovementValue value = new InventoryMovementValue();
            value.type = movement.getType();
            value.quantity = movement.getQuantity().value();
            value.occurredAt = movement.getDate();
            value.metadataJson = JsonCodec.toJson(movement.getMetadata());
            return value;
        }

        InventoryMovement toDomain() {
            return InventoryMovement.of(type, Quantity.of(quantity), occurredAt,
                    JsonCodec.fromJson(metadataJson));
        }
    }
// ------------------------------------------------------------------
    // Ítem de devolución (Return.items)
    // ------------------------------------------------------------------

    @Embeddable
    static class ReturnItemValue {

        @Column(name = "product_id", length = 36)
        String productId;

        @Column(name = "product_name", length = 120)
        String productName;

        @Column(name = "quantity")
        int quantity;

        @Column(name = "reason", length = 40)
        String reason;

        protected ReturnItemValue() {
        }

        static ReturnItemValue from(ReturnItem item) {
            ReturnItemValue value = new ReturnItemValue();
            value.productId = item.getProductId().value();
            value.productName = item.getProductName().value();
            value.quantity = item.getQuantity().value();
            value.reason = item.getReason().getCode();
            return value;
        }

        ReturnItem toDomain() {
            return ReturnItem.of(ProductId.of(productId), ProductName.of(productName),
                    Quantity.of(quantity), catalogs().returnReason(reason));
        }
    }

    // ------------------------------------------------------------------
    // Información de seguimiento de la transportadora (Shipping)
    // ------------------------------------------------------------------

    @Embeddable
    static class DeliveryInfoValue {

        @Column(name = "tracking_number", length = 64)
        String trackingNumber;

        @Column(name = "carrier", length = 80)
        String carrier;

        @Column(name = "estimated_date")
        LocalDate estimatedDate;

        protected DeliveryInfoValue() {
        }

        static DeliveryInfoValue from(DeliveryInfo info) {
            DeliveryInfoValue value = new DeliveryInfoValue();
            value.trackingNumber = info.getTrackingNumber();
            value.carrier = info.getCarrier();
            value.estimatedDate = info.getEstimatedDate();
            return value;
        }

        DeliveryInfo toDomain() {
            if (carrier == null || carrier.isBlank()) {
                return null;
            }
            return DeliveryInfo.of(trackingNumber, carrier, estimatedDate);
        }
    }

    // ------------------------------------------------------------------
    // Utilidades compartidas
    // ------------------------------------------------------------------

    static Money money(BigDecimal amount, String currencyCode) {
        return Money.of(amount, Currency.getInstance(currencyCode));
    }

    static JpaCatalogCodecs catalogs() {
        return JpaCatalogCodecs.INSTANCE;
    }

    /** Método auxiliar para conservar el id de inventario en los metadatos. */
    @SuppressWarnings("unused")
    static Map<String, Object> metadataFromInventory(Inventory inventory) {
        Map<String, Object> result = new HashMap<>();
        result.put("inventoryId", inventory.getInventoryId().value());
        return result;
    }
}