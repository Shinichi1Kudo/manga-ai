package com.manga.ai.payment.dto;

import com.manga.ai.payment.entity.UserMembership;
import com.manga.ai.payment.model.MembershipProduct;
import com.manga.ai.payment.model.RechargePackage;

import java.util.List;
import java.util.Map;

public record PaymentProductsResponse(
        Map<String, List<MembershipProduct>> membershipProducts,
        List<RechargePackage> rechargePackages,
        UserMembership activeMembership
) {
}
