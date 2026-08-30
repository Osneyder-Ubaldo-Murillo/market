package nexus.market.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import nexus.market.valueObject.RolUSer;
import nexus.market.valueObject.UserStatus;

@Getter
@Setter
@NoArgsConstructor
public class User extends Person {
    private String username;
    private String Password;
    private RolUSer Rol;
    private long userId;
    private UserStatus status;
    private Customer customer;

    
}
