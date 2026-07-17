package com.manga.ai.payment.dto;

import com.manga.ai.payment.enums.PaymentMethod;
import jakarta.validation.constraints.NotBlank;

public class CreateMembershipOrderRequest {

    @NotBlank(message = "请选择会员套餐")
    private String productCode;
    private PaymentMethod paymentMethod;

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod == null ? PaymentMethod.PAGE : paymentMethod;
    }

    public void setPaymentMethod(PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
    }
}
