package com.app.ecom.product.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class StockAdjustmentRequestDto {

    @NotNull(message = "quantityChange is required")
    private Integer quantityChange;
}
