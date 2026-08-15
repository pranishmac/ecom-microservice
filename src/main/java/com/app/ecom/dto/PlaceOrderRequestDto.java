package com.app.ecom.dto;

import jakarta.validation.Valid;
import lombok.Data;

@Data
public class PlaceOrderRequestDto {

    @Valid
    private ShippingAddressDto shippingAddress;
}
