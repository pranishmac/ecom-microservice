package com.app.ecom.common.exception;

public class ShippingAddressRequiredException extends RuntimeException {
    public ShippingAddressRequiredException(String message) {
        super(message);
    }
}
