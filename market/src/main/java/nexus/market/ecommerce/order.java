package nexus.market.ecommerce;

import lombok.Getter;
import lombok.Setter;
import nexus.market.valueObject.StatusOrder;

@Getter
@Setter
public class order {
    private String name;
    private StatusOrder Status;
}
