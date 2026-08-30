package nexus.market.products; //si no ese es este package nexus.market.domain;

import lombok.Getter;
import lombok.Setter;
import nexus.market.valueObject.StatusProduct;
import nexus.market.valueObject.TypeProduct;

@Getter
@Setter
public abstract class product {
    private String name;
    private TypeProduct type;
    private StatusProduct status;    
}
