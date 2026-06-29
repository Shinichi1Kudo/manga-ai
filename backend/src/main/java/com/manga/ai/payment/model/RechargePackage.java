package com.manga.ai.payment.model;

import com.manga.ai.payment.enums.MembershipTier;

public record RechargePackage(
        int amountCents,
        int baseCredits,
        int bonusCredits,
        int totalCredits,
        int bonusPercent,
        int bonusExpireDays,
        MembershipTier membershipTier
) {
}
