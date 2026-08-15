package com.app.ecom.order;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Embeddable
public class ShippingAddress {

    @Column(name = "shipping_street", nullable = false)
    private String street;

    @Column(name = "shipping_city", nullable = false)
    private String city;

    @Column(name = "shipping_state", nullable = false)
    private String state;

    @Column(name = "shipping_zip_code", nullable = false)
    private String zipCode;

    @Column(name = "shipping_country", nullable = false)
    private String country;
}
