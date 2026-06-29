package com.manga.ai.payment.service;

import com.manga.ai.payment.entity.PaymentOrder;
import com.manga.ai.payment.entity.UserMembership;

public interface MembershipService {

    UserMembership getActiveMembership(Long userId);

    void activateMembership(PaymentOrder order);
}
