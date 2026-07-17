package com.manga.ai.payment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.payment.client.AlipayPaymentClient;
import com.manga.ai.payment.config.AlipayProperties;
import com.manga.ai.payment.dto.AlipayPagePayRequest;
import com.manga.ai.payment.dto.AlipayPagePayResult;
import com.manga.ai.payment.dto.AlipayQueryResult;
import com.manga.ai.payment.dto.PaymentOrderCreateResponse;
import com.manga.ai.payment.dto.PaymentOrderStatusResponse;
import com.manga.ai.payment.dto.PaymentProductsResponse;
import com.manga.ai.payment.dto.PaymentSettlement;
import com.manga.ai.payment.entity.PaymentOrder;
import com.manga.ai.payment.entity.UserMembership;
import com.manga.ai.payment.enums.MembershipPeriod;
import com.manga.ai.payment.enums.MembershipTier;
import com.manga.ai.payment.enums.PaymentOrderStatus;
import com.manga.ai.payment.enums.PaymentOrderType;
import com.manga.ai.payment.mapper.PaymentOrderMapper;
import com.manga.ai.payment.mapper.UserMembershipMapper;
import com.manga.ai.payment.model.MembershipProduct;
import com.manga.ai.payment.model.RechargePackage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PaymentOrderServiceImpl implements PaymentOrderService {

    private static final DateTimeFormatter ORDER_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PaymentOrderMapper paymentOrderMapper;
    private final UserMembershipMapper userMembershipMapper;
    private final CreditAccountService creditAccountService;
    private final MembershipService membershipService;
    private final PaymentProductCatalog productCatalog;
    private final AlipayProperties alipayProperties;
    private final AlipayPaymentClient alipayPaymentClient;

    @Override
    public PaymentProductsResponse products(Long userId) {
        MembershipTier tier = activeTier(userId);
        Map<String, List<MembershipProduct>> memberships = new LinkedHashMap<>();
        for (MembershipPeriod period : MembershipPeriod.values()) {
            memberships.put(period.name(), productCatalog.membershipProducts(period));
        }
        return new PaymentProductsResponse(
                memberships,
                productCatalog.rechargePackages(tier),
                userId == null ? null : membershipService.getActiveMembership(userId)
        );
    }

    @Override
    @Transactional
    public PaymentOrderCreateResponse createRechargeOrder(Long userId, int amountCents) {
        ensureLoggedIn(userId);
        RechargePackage rechargePackage = productCatalog.rechargePackage(amountCents, activeTier(userId));
        PaymentOrder order = baseOrder(userId, PaymentOrderType.RECHARGE, "recharge_" + amountCents,
                "积分充值¥" + amountCents / 100, amountCents);
        order.setBaseCredits(rechargePackage.baseCredits());
        order.setBonusCredits(rechargePackage.bonusCredits());
        order.setTotalCredits(rechargePackage.totalCredits());

        return preparePagePayAndInsert(order);
    }

    @Override
    @Transactional
    public PaymentOrderCreateResponse createMembershipOrder(Long userId, String productCode) {
        ensureLoggedIn(userId);
        MembershipProduct product = productCatalog.findMembershipProduct(productCode);
        if (!product.enabled()) {
            throw new BusinessException("该会员套餐暂未开放");
        }

        PaymentOrder order = baseOrder(userId, PaymentOrderType.MEMBERSHIP, product.code(),
                product.period().getLabel() + product.title(), product.priceCents());
        order.setBaseCredits(0);
        order.setBonusCredits(product.totalCredits());
        order.setTotalCredits(product.totalCredits());
        order.setMembershipTier(product.tier().name());
        order.setMembershipPeriod(product.period().name());
        order.setMembershipMonths(product.months());

        return preparePagePayAndInsert(order);
    }

    @Override
    public PaymentOrderStatusResponse getOrder(Long userId, String orderNo) {
        ensureLoggedIn(userId);
        PaymentOrder order = findOrder(orderNo);
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("订单不存在");
        }
        return toStatusResponse(order);
    }

    @Override
    @Transactional
    public PaymentOrderStatusResponse syncOrder(Long userId, String orderNo) {
        ensureLoggedIn(userId);
        PaymentOrder order = findOrder(orderNo);
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("订单不存在");
        }
        if (!PaymentOrderStatus.PAID.name().equals(order.getStatus())) {
            AlipayQueryResult queryResult = alipayPaymentClient.query(orderNo);
            if (queryResult != null && queryResult.paid()) {
                settlePaidOrder(orderNo, queryResult.alipayTradeNo(), "query-sync", queryResult.totalAmount(),
                        queryResult.tradeStatus(), alipayProperties.getAppId(), alipayProperties.getSellerId());
                order = findOrder(orderNo);
            }
        }
        return toStatusResponse(order);
    }

    @Override
    @Transactional
    public PaymentSettlement settlePaidOrder(String orderNo, String alipayTradeNo, String notifyId, BigDecimal paidAmount,
                                             String tradeStatus, String appId, String sellerId) {
        if (!paidStatus(tradeStatus)) {
            return new PaymentSettlement(false, orderNo, tradeStatus);
        }
        PaymentOrder order = findOrder(orderNo);
        if (PaymentOrderStatus.PAID.name().equals(order.getStatus())) {
            return new PaymentSettlement(false, orderNo, order.getStatus());
        }
        validateAlipayIdentity(order, appId, sellerId);
        validateAmount(order, paidAmount);

        LocalDateTime now = LocalDateTime.now();
        PaymentOrder update = new PaymentOrder();
        update.setStatus(PaymentOrderStatus.PAID.name());
        update.setAlipayTradeNo(alipayTradeNo);
        update.setNotifyId(notifyId);
        update.setTradeStatus(tradeStatus);
        update.setPaidAt(now);
        update.setCreditedAt(now);
        update.setUpdatedAt(now);

        LambdaUpdateWrapper<PaymentOrder> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(PaymentOrder::getOrderNo, orderNo)
                .ne(PaymentOrder::getStatus, PaymentOrderStatus.PAID.name());
        int rows = paymentOrderMapper.update(update, wrapper);
        if (rows == 0) {
            return new PaymentSettlement(false, orderNo, PaymentOrderStatus.PAID.name());
        }

        order.setStatus(PaymentOrderStatus.PAID.name());
        order.setAlipayTradeNo(alipayTradeNo);
        order.setNotifyId(notifyId);
        order.setTradeStatus(tradeStatus);
        order.setPaidAt(now);
        order.setCreditedAt(now);

        if (PaymentOrderType.RECHARGE.name().equals(order.getOrderType())) {
            creditAccountService.grantRechargeCredits(order);
        } else if (PaymentOrderType.MEMBERSHIP.name().equals(order.getOrderType())) {
            membershipService.activateMembership(order);
        }
        return new PaymentSettlement(true, orderNo, PaymentOrderStatus.PAID.name());
    }

    private PaymentOrderCreateResponse preparePagePayAndInsert(PaymentOrder order) {
        AlipayPagePayResult result = alipayPaymentClient.pagePay(new AlipayPagePayRequest(
                order.getOrderNo(),
                order.getProductName(),
                amountAsYuan(order.getAmountCents()),
                alipayProperties.getNotifyUrl(),
                alipayProperties.getReturnUrl(),
                alipayProperties.getTimeoutExpress()
        ));
        LocalDateTime now = LocalDateTime.now();
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        paymentOrderMapper.insert(order);
        return toCreateResponse(order, result.paymentHtml());
    }

    private PaymentOrder baseOrder(Long userId, PaymentOrderType type, String productCode, String productName, int amountCents) {
        PaymentOrder order = new PaymentOrder();
        order.setOrderNo(nextOrderNo());
        order.setUserId(userId);
        order.setOrderType(type.name());
        order.setProductCode(productCode);
        order.setProductName(productName);
        order.setAmountCents(amountCents);
        order.setStatus(PaymentOrderStatus.CREATED.name());
        order.setChannel("ALIPAY");
        order.setAppId(alipayProperties.getAppId());
        order.setSellerId(alipayProperties.getSellerId());
        return order;
    }

    private MembershipTier activeTier(Long userId) {
        if (userId == null) {
            return MembershipTier.NONE;
        }
        LambdaQueryWrapper<UserMembership> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserMembership::getUserId, userId)
                .eq(UserMembership::getStatus, "ACTIVE")
                .gt(UserMembership::getExpiresAt, LocalDateTime.now())
                .orderByDesc(UserMembership::getExpiresAt)
                .last("LIMIT 1");
        UserMembership membership = userMembershipMapper.selectOne(wrapper);
        if (membership == null || membership.getTier() == null) {
            return MembershipTier.NONE;
        }
        try {
            return MembershipTier.valueOf(membership.getTier());
        } catch (IllegalArgumentException e) {
            return MembershipTier.NONE;
        }
    }

    private PaymentOrder findOrder(String orderNo) {
        LambdaQueryWrapper<PaymentOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PaymentOrder::getOrderNo, orderNo);
        PaymentOrder order = paymentOrderMapper.selectOne(wrapper);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        return order;
    }

    private void validateAmount(PaymentOrder order, BigDecimal paidAmount) {
        if (paidAmount == null) {
            throw new BusinessException("支付金额缺失");
        }
        BigDecimal expected = amountAsYuan(order.getAmountCents());
        if (expected.compareTo(paidAmount.setScale(2, RoundingMode.HALF_UP)) != 0) {
            throw new BusinessException("支付金额不匹配");
        }
    }

    private void validateAlipayIdentity(PaymentOrder order, String appId, String sellerId) {
        String expectedAppId = firstNotBlank(order.getAppId(), alipayProperties.getAppId());
        String expectedSellerId = firstNotBlank(order.getSellerId(), alipayProperties.getSellerId());
        if (notBlank(expectedAppId) && !expectedAppId.equals(appId)) {
            throw new BusinessException("支付应用不匹配");
        }
        if (notBlank(expectedSellerId) && !expectedSellerId.equals(sellerId)) {
            throw new BusinessException("支付商户不匹配");
        }
    }

    private boolean paidStatus(String tradeStatus) {
        return "TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus);
    }

    private BigDecimal amountAsYuan(Integer amountCents) {
        return BigDecimal.valueOf(amountCents == null ? 0 : amountCents)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.UNNECESSARY);
    }

    private PaymentOrderCreateResponse toCreateResponse(PaymentOrder order, String paymentHtml) {
        return new PaymentOrderCreateResponse(
                order.getOrderNo(),
                order.getOrderType(),
                order.getProductName(),
                safe(order.getAmountCents()),
                safe(order.getBaseCredits()),
                safe(order.getBonusCredits()),
                safe(order.getTotalCredits()),
                order.getStatus(),
                paymentHtml
        );
    }

    private PaymentOrderStatusResponse toStatusResponse(PaymentOrder order) {
        return new PaymentOrderStatusResponse(
                order.getOrderNo(),
                order.getOrderType(),
                order.getProductName(),
                safe(order.getAmountCents()),
                safe(order.getTotalCredits()),
                order.getStatus(),
                order.getPaidAt()
        );
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private String firstNotBlank(String first, String second) {
        return notBlank(first) ? first : second;
    }

    private boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private void ensureLoggedIn(Long userId) {
        if (userId == null) {
            throw new BusinessException("请先登录");
        }
    }

    private String nextOrderNo() {
        int suffix = RANDOM.nextInt(9000) + 1000;
        return "PAY" + LocalDateTime.now().format(ORDER_TIME_FORMATTER) + suffix;
    }
}
