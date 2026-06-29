package com.manga.ai.payment.service;

import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.payment.enums.MembershipPeriod;
import com.manga.ai.payment.enums.MembershipTier;
import com.manga.ai.payment.model.MembershipProduct;
import com.manga.ai.payment.model.RechargePackage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
public class PaymentProductCatalog {

    private static final List<Integer> RECHARGE_AMOUNTS_CENTS = List.of(5000, 10000, 20000, 50000);
    private static final int CREDITS_PER_YUAN = 20;
    private static final int BONUS_EXPIRE_DAYS = 30;

    public List<MembershipProduct> membershipProducts(MembershipPeriod period) {
        return switch (period) {
            case YEAR -> products(period,
                    product(period, MembershipTier.BASIC, 65900, true),
                    product(period, MembershipTier.STANDARD, 189900, true),
                    product(period, MembershipTier.ADVANCED, 519900, true),
                    product(period, MembershipTier.SUPER, 0, false));
            case MONTH -> products(period,
                    product(period, MembershipTier.BASIC, 6900, true),
                    product(period, MembershipTier.STANDARD, 19900, true),
                    product(period, MembershipTier.ADVANCED, 99800, true),
                    product(period, MembershipTier.SUPER, 429900, true));
            case QUARTER -> products(period,
                    product(period, MembershipTier.BASIC, 18800, true),
                    product(period, MembershipTier.STANDARD, 56800, true),
                    product(period, MembershipTier.ADVANCED, 195900, true),
                    product(period, MembershipTier.SUPER, 818900, true));
            case SINGLE_MONTH -> products(period,
                    product(period, MembershipTier.BASIC, 7900, true),
                    product(period, MembershipTier.STANDARD, 23900, true),
                    product(period, MembershipTier.ADVANCED, 64900, true),
                    product(period, MembershipTier.SUPER, 0, false));
        };
    }

    public List<MembershipProduct> allMembershipProducts() {
        List<MembershipProduct> products = new ArrayList<>();
        for (MembershipPeriod period : MembershipPeriod.values()) {
            products.addAll(membershipProducts(period));
        }
        return products;
    }

    public MembershipProduct findMembershipProduct(String productCode) {
        return allMembershipProducts().stream()
                .filter(product -> product.code().equals(productCode))
                .findFirst()
                .orElseThrow(() -> new BusinessException("会员套餐不存在"));
    }

    public List<RechargePackage> rechargePackages(MembershipTier membershipTier) {
        return RECHARGE_AMOUNTS_CENTS.stream()
                .map(amount -> rechargePackage(amount, membershipTier))
                .toList();
    }

    public RechargePackage rechargePackage(int amountCents, MembershipTier membershipTier) {
        if (!RECHARGE_AMOUNTS_CENTS.contains(amountCents)) {
            throw new BusinessException("充值档位不存在");
        }
        MembershipTier effectiveTier = membershipTier == null ? MembershipTier.NONE : membershipTier;
        int baseCredits = amountCents / 100 * CREDITS_PER_YUAN;
        int bonusCredits = baseCredits * effectiveTier.getRechargeBonusPercent() / 100;
        return new RechargePackage(
                amountCents,
                baseCredits,
                bonusCredits,
                baseCredits + bonusCredits,
                effectiveTier.getRechargeBonusPercent(),
                BONUS_EXPIRE_DAYS,
                effectiveTier
        );
    }

    private List<MembershipProduct> products(MembershipPeriod period, MembershipProduct... products) {
        return Arrays.asList(products);
    }

    private MembershipProduct product(MembershipPeriod period, MembershipTier tier, int priceCents, boolean enabled) {
        int monthlyCredits = monthlyCredits(tier);
        int totalCredits = monthlyCredits * period.getMonths();
        return new MembershipProduct(
                code(period, tier),
                period,
                tier,
                tier.getDisplayName(),
                priceCents,
                period.getMonths(),
                monthlyCredits,
                totalCredits,
                enabled,
                enabled ? buttonText(period, tier, priceCents) : "敬请期待",
                features(tier)
        );
    }

    private String code(MembershipPeriod period, MembershipTier tier) {
        return period.name().toLowerCase() + "_" + tier.name().toLowerCase();
    }

    private String buttonText(MembershipPeriod period, MembershipTier tier, int priceCents) {
        if (period == MembershipPeriod.SINGLE_MONTH) {
            return "购买";
        }
        return "订阅" + period.getLabel() + tier.getDisplayName();
    }

    private int monthlyCredits(MembershipTier tier) {
        return switch (tier) {
            case BASIC -> 725;
            case STANDARD -> 2210;
            case ADVANCED -> 12320;
            case SUPER -> 54600;
            case NONE -> 0;
        };
    }

    private List<String> features(MembershipTier tier) {
        List<String> common = new ArrayList<>();
        common.add("Seedance 2.0");
        common.add("Seedance 1.5 Pro");
        common.add("图片4.0");
        if (tier == MembershipTier.ADVANCED || tier == MembershipTier.SUPER) {
            common.add("图片4.0 4K限时免费");
            common.add("快速生成通道");
        } else {
            common.add("标准生成通道");
        }
        common.add("无忧退款");
        common.add("生成作品去除品牌水印");
        return common;
    }
}
