package com.manga.ai.payment.model;

import com.manga.ai.payment.enums.MembershipPeriod;
import com.manga.ai.payment.enums.MembershipTier;

import java.util.List;

public record MembershipProduct(
        String code,
        MembershipPeriod period,
        MembershipTier tier,
        String title,
        int priceCents,
        int months,
        int monthlyCredits,
        int totalCredits,
        boolean enabled,
        String buttonText,
        List<String> features
) {
}
