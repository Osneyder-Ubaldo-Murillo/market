package nexus.market.products;


import lombok.Getter;
import lombok.Setter;
import nexus.market.valueObject.StatusInventory;
import nexus.market.valueObject.TypeProduct;

@Getter
@Setter
public class inventory {
    private String name;
    private TypeProduct type;
    private StatusInventory status;
}
