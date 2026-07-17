package com.manga.ai.payment.dto;

import com.manga.ai.payment.enums.PaymentMethod;
import jakarta.validation.constraints.Min;

public class CreateRechargeOrderRequest {

    @Min(value = 1, message = "充值金额不正确")
    private int amountCents;
    private PaymentMethod paymentMethod;

    public int getAmountCents() {
        return amountCents;
    }

    public void setAmountCents(int amountCents) {
        this.amountCents = amountCents;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod == null ? PaymentMethod.PAGE : paymentMethod;
    }

    public void setPaymentMethod(PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
    }
}
