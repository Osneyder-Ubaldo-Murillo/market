package nexus.market.domain;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public abstract class Person {
    private String identificacion;
    private String name;
    private String email;
    private String phoneNumber;
    private String address;
}