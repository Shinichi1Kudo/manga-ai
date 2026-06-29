package com.manga.ai.payment.service;

import com.manga.ai.payment.entity.PaymentOrder;

public interface CreditAccountService {

    void grantRechargeCredits(PaymentOrder order);

    void grantMembershipCredits(PaymentOrder order);

    void consumeCredits(Long userId, int amount, String usageType, String description, Long referenceId, String referenceType);
}
