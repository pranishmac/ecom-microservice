package com.app.ecom.exception;

public class ShippingAddressRequiredException extends RuntimeException {
    public ShippingAddressRequiredException(String message) {
        super(message);
    }
}
