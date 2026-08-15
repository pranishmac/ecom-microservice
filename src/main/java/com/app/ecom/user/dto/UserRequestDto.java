package com.app.ecom.user.dto;

import com.app.ecom.user.UserRole;
import lombok.Data;

@Data
public class UserRequestDto {
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private UserRole role;
    private AddressDto address;
}
