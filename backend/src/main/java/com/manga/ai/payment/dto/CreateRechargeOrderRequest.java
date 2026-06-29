package com.manga.ai.payment.dto;

import jakarta.validation.constraints.Min;

public class CreateRechargeOrderRequest {

    @Min(value = 1, message = "充值金额不正确")
    private int amountCents;

    public int getAmountCents() {
        return amountCents;
    }

    public void setAmountCents(int amountCents) {
        this.amountCents = amountCents;
    }
}
