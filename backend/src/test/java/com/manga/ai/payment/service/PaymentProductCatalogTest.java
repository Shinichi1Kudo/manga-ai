package com.manga.ai.payment.service;

import com.manga.ai.payment.enums.MembershipPeriod;
import com.manga.ai.payment.enums.MembershipTier;
import com.manga.ai.payment.model.MembershipProduct;
import com.manga.ai.payment.model.RechargePackage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentProductCatalogTest {

    private final PaymentProductCatalog catalog = new PaymentProductCatalog();

    @Test
    void membershipProductsFollowJimengStyleSeedData() {
        List<MembershipProduct> monthlyProducts = catalog.membershipProducts(MembershipPeriod.MONTH);

        assertThat(monthlyProducts)
                .extracting(MembershipProduct::tier)
                .containsExactly(
                        MembershipTier.BASIC,
                        MembershipTier.STANDARD,
                        MembershipTier.ADVANCED,
                        MembershipTier.SUPER
                );
        assertThat(product(monthlyProducts, MembershipTier.BASIC))
                .satisfies(product -> {
                    assertThat(product.priceCents()).isEqualTo(6900);
                    assertThat(product.monthlyCredits()).isEqualTo(725);
                    assertThat(product.enabled()).isTrue();
                });
        assertThat(product(monthlyProducts, MembershipTier.STANDARD))
                .satisfies(product -> {
                    assertThat(product.priceCents()).isEqualTo(19900);
                    assertThat(product.monthlyCredits()).isEqualTo(2210);
                    assertThat(product.enabled()).isTrue();
                });
        assertThat(product(monthlyProducts, MembershipTier.ADVANCED))
                .satisfies(product -> {
                    assertThat(product.priceCents()).isEqualTo(99800);
                    assertThat(product.monthlyCredits()).isEqualTo(12320);
                    assertThat(product.enabled()).isTrue();
                });
        assertThat(product(monthlyProducts, MembershipTier.SUPER))
                .satisfies(product -> {
                    assertThat(product.priceCents()).isEqualTo(429900);
                    assertThat(product.monthlyCredits()).isEqualTo(54600);
                    assertThat(product.enabled()).isTrue();
                });

        MembershipProduct superAnnual = product(catalog.membershipProducts(MembershipPeriod.YEAR), MembershipTier.SUPER);
        assertThat(superAnnual.enabled()).isFalse();
        assertThat(superAnnual.buttonText()).isEqualTo("敬请期待");
    }

    @Test
    void rechargePackagesCapSuperBonusAt1150PointsForFiftyYuan() {
        RechargePackage superPackage = catalog.rechargePackage(5000, MembershipTier.SUPER);

        assertThat(superPackage.baseCredits()).isEqualTo(1000);
        assertThat(superPackage.bonusCredits()).isEqualTo(150);
        assertThat(superPackage.totalCredits()).isEqualTo(1150);
        assertThat(superPackage.bonusPercent()).isEqualTo(15);

        assertThat(catalog.rechargePackage(5000, MembershipTier.NONE).totalCredits()).isEqualTo(1000);
        assertThat(catalog.rechargePackage(5000, MembershipTier.BASIC).totalCredits()).isEqualTo(1050);
        assertThat(catalog.rechargePackage(5000, MembershipTier.STANDARD).totalCredits()).isEqualTo(1080);
        assertThat(catalog.rechargePackage(5000, MembershipTier.ADVANCED).totalCredits()).isEqualTo(1120);
    }

    private MembershipProduct product(List<MembershipProduct> products, MembershipTier tier) {
        return products.stream()
                .filter(product -> product.tier() == tier)
                .findFirst()
                .orElseThrow();
    }
}
