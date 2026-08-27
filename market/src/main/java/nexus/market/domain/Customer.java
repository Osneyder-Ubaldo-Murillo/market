package nexus.market.domain;

import lombok.Getter;
import lombok.Setter;
import nexus.market.valueObject.CustomerStatus;

@Getter
@Setter 
public abstract class Customer extends Person{
    private CustomerStatus status;
    
}
