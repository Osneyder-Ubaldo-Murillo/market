package nexus.market.domain.models;

import java.time.LocalDateTime;
import java.util.Objects;

import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.ProductId;

/**
 * Unidades ya recogidas de una línea de un pedido. La cantidad pedida no se copia: sale siempre de la línea congelada del
 * pedido, así que no puede desincronizarse.
 */
public record OrderPickup(OrderId orderId, ProductId productId, int pickedQuantity, String updatedBy,
                          LocalDateTime updatedAt) {

    public OrderPickup {
        Objects.requireNonNull(orderId, "orderId es obligatorio");
        Objects.requireNonNull(productId, "productId es obligatorio");
        if (pickedQuantity < 0) {
            throw new IllegalArgumentException("La cantidad recogida no puede ser negativa");
        }
    }
}
