package com.manga.ai.payment.dto;

import jakarta.validation.constraints.NotBlank;

public class CreateMembershipOrderRequest {

    @NotBlank(message = "请选择会员套餐")
    private String productCode;

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }
}
