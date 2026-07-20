package com.manga.ai.payment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.payment.client.AlipayPaymentClient;
import com.manga.ai.payment.config.AlipayProperties;
import com.manga.ai.payment.dto.AlipayPagePayRequest;
import com.manga.ai.payment.dto.AlipayPagePayResult;
import com.manga.ai.payment.dto.AlipayCancelResult;
import com.manga.ai.payment.dto.AlipayPrecreateRequest;
import com.manga.ai.payment.dto.AlipayPrecreateResult;
import com.manga.ai.payment.dto.AlipayQueryResult;
import com.manga.ai.payment.dto.PaymentOrderCreateResponse;
import com.manga.ai.payment.dto.PaymentOrderStatusResponse;
import com.manga.ai.payment.dto.PaymentProductsResponse;
import com.manga.ai.payment.dto.PaymentSettlement;
import com.manga.ai.payment.dto.PendingPaymentOrderResponse;
import com.manga.ai.payment.entity.PaymentOrder;
import com.manga.ai.payment.entity.UserMembership;
import com.manga.ai.payment.enums.MembershipPeriod;
import com.manga.ai.payment.enums.MembershipTier;
import com.manga.ai.payment.enums.PaymentOrderStatus;
import com.manga.ai.payment.enums.PaymentOrderType;
import com.manga.ai.payment.enums.PaymentMethod;
import com.manga.ai.payment.exception.RetryablePaymentGatewayException;
import com.manga.ai.payment.mapper.PaymentOrderMapper;
import com.manga.ai.payment.model.MembershipProduct;
import com.manga.ai.payment.model.RechargePackage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentOrderServiceImpl implements PaymentOrderService {

    private static final DateTimeFormatter ORDER_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int QR_PRECREATE_MAX_ATTEMPTS = 3;
    private static final int ORDER_OPERATION_LOCK_STRIPES = 64;
    private static final String QR_TIMEOUT_EXPRESS = "2h";
    private static final String QR_UNAVAILABLE_MESSAGE =
            "支付宝扫码支付服务暂时不可用，请稍后重试或改用支付宝收银台";
    private static final String PAYMENT_STATE_UNCERTAIN_MESSAGE =
            "支付状态暂未确认，请稍后查询，请勿重复支付";

    private final PaymentOrderMapper paymentOrderMapper;
    private final CreditAccountService creditAccountService;
    private final MembershipService membershipService;
    private final PaymentProductCatalog productCatalog;
    private final AlipayProperties alipayProperties;
    private final AlipayPaymentClient alipayPaymentClient;
    private final PaymentRetrySleeper paymentRetrySleeper;
    private final TransactionTemplate transactionTemplate;
    private final ReentrantLock[] orderOperationLocks = createOrderOperationLocks();

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
    public PaymentOrderCreateResponse createRechargeOrder(Long userId, int amountCents, PaymentMethod paymentMethod) {
        ensureLoggedIn(userId);
        RechargePackage rechargePackage = productCatalog.rechargePackage(amountCents, activeTier(userId));
        PaymentOrder order = baseOrder(userId, PaymentOrderType.RECHARGE, "recharge_" + amountCents,
                "积分充值¥" + amountCents / 100, amountCents, paymentMethod);
        order.setBaseCredits(rechargePackage.baseCredits());
        order.setBonusCredits(rechargePackage.bonusCredits());
        order.setTotalCredits(rechargePackage.totalCredits());

        return preparePayAndInsert(order);
    }

    @Override
    public PaymentOrderCreateResponse createMembershipOrder(Long userId, String productCode, PaymentMethod paymentMethod) {
        ensureLoggedIn(userId);
        MembershipProduct product = productCatalog.findMembershipProduct(productCode);
        if (!product.enabled()) {
            throw new BusinessException("该会员套餐暂未开放");
        }

        PaymentOrder order = baseOrder(userId, PaymentOrderType.MEMBERSHIP, product.code(),
                product.period().getLabel() + product.title(), product.priceCents(), paymentMethod);
        order.setBaseCredits(0);
        order.setBonusCredits(product.totalCredits());
        order.setTotalCredits(product.totalCredits());
        order.setMembershipTier(product.tier().name());
        order.setMembershipPeriod(product.period().name());
        order.setMembershipMonths(product.months());

        return preparePayAndInsert(order);
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
    public PaymentOrderStatusResponse syncOrder(Long userId, String orderNo) {
        return withOrderOperationLock(orderNo, () -> doSyncOrder(userId, orderNo));
    }

    private PaymentOrderStatusResponse doSyncOrder(Long userId, String orderNo) {
        PaymentOrder order = findOwnedOrder(userId, orderNo);
        if (PaymentOrderStatus.CREATED.name().equals(order.getStatus())) {
            AlipayQueryResult queryResult = alipayPaymentClient.query(orderNo);
            if (queryResult != null && queryResult.paid()) {
                settlePaidOrder(orderNo, queryResult.alipayTradeNo(), "query-sync", queryResult.totalAmount(),
                        queryResult.tradeStatus(), alipayProperties.getAppId(), alipayProperties.getSellerId());
                order = findOrder(orderNo);
            } else if (tradeClosed(queryResult)) {
                closeCreatedOrder(orderNo);
                order = findOrder(orderNo);
            } else if (isExpiredQrOrder(order, LocalDateTime.now())) {
                if (notFound(queryResult)) {
                    closeCreatedOrder(orderNo);
                } else if (waitingForBuyer(queryResult)) {
                    cancelAndClose(order);
                }
                order = findOrder(orderNo);
            }
        }
        return toStatusResponse(order);
    }

    @Override
    public List<PendingPaymentOrderResponse> pendingOrders(Long userId) {
        ensureLoggedIn(userId);
        LambdaQueryWrapper<PaymentOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PaymentOrder::getUserId, userId)
                .eq(PaymentOrder::getStatus, PaymentOrderStatus.CREATED.name())
                .gt(PaymentOrder::getExpiresAt, LocalDateTime.now())
                .orderByDesc(PaymentOrder::getCreatedAt)
                .last("LIMIT 20");
        List<PaymentOrder> orders = paymentOrderMapper.selectList(wrapper);
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }
        return orders.stream().map(this::toPendingResponse).toList();
    }

    @Override
    public PaymentOrderCreateResponse resumeOrder(Long userId, String orderNo) {
        return withOrderOperationLock(orderNo, () -> doResumeOrder(userId, orderNo));
    }

    private PaymentOrderCreateResponse doResumeOrder(Long userId, String orderNo) {
        PaymentOrder order = findOwnedOrder(userId, orderNo);
        if (!PaymentOrderStatus.CREATED.name().equals(order.getStatus())) {
            return toCreateResponseWithoutPaymentPayload(order);
        }

        if (isExpiredQrOrder(order, LocalDateTime.now())) {
            processExpiredQrOrder(order, queryForSafePaymentAction(orderNo));
            PaymentOrder current = findOrder(orderNo);
            if (PaymentOrderStatus.CREATED.name().equals(current.getStatus())) {
                return toCreateResponse(current, null, false, PAYMENT_STATE_UNCERTAIN_MESSAGE);
            }
            return toCreateResponseWithoutPaymentPayload(current);
        }

        AlipayQueryResult queryResult = queryForSafePaymentAction(orderNo);
        if (queryResult != null && queryResult.paid()) {
            settleFromQuery(order, queryResult, "resume-query");
            return toCreateResponseWithoutPaymentPayload(findOrder(orderNo));
        }
        if (tradeClosed(queryResult)) {
            closeCreatedOrder(orderNo);
            return toCreateResponseWithoutPaymentPayload(findOrder(orderNo));
        }
        if (!paymentCanBeRestored(queryResult)) {
            throw new BusinessException(PAYMENT_STATE_UNCERTAIN_MESSAGE);
        }

        if (PaymentMethod.PAGE.name().equals(order.getPaymentMethod())) {
            AlipayPagePayResult pagePayResult = alipayPaymentClient.pagePay(pagePayRequest(order));
            PaymentOrder current = findOrder(orderNo);
            if (!PaymentOrderStatus.CREATED.name().equals(current.getStatus())) {
                return toCreateResponseWithoutPaymentPayload(current);
            }
            return toCreateResponse(current, pagePayResult.paymentHtml(), true, null);
        }
        if (!PaymentMethod.QR.name().equals(order.getPaymentMethod())) {
            throw new BusinessException("该订单支付方式不支持恢复");
        }
        if (notBlank(order.getQrcode())) {
            PaymentOrder current = findOrder(orderNo);
            if (!PaymentOrderStatus.CREATED.name().equals(current.getStatus())) {
                return toCreateResponseWithoutPaymentPayload(current);
            }
            if (notBlank(current.getQrcode())) {
                return toCreateResponse(current, null, true, null);
            }
            order = current;
        }

        AlipayPrecreateResult precreateResult;
        try {
            precreateResult = precreateQrWithRetry(order);
        } catch (BusinessException exception) {
            return closeOrReturnTerminalOrder(orderNo, exception);
        }
        if (precreateResult != null && notBlank(precreateResult.qrCode())) {
            saveResumedQrIfCreated(orderNo, precreateResult.qrCode());
        }
        return reloadedQrCreateResponse(orderNo);
    }

    @Override
    public PaymentOrderCreateResponse switchToPage(Long userId, String orderNo) {
        return withOrderOperationLock(orderNo, () -> doSwitchToPage(userId, orderNo));
    }

    private PaymentOrderCreateResponse doSwitchToPage(Long userId, String orderNo) {
        PaymentOrder source = findOwnedOrder(userId, orderNo);
        if (!PaymentOrderStatus.CREATED.name().equals(source.getStatus())) {
            throw new BusinessException("该订单当前状态不支持切换支付方式");
        }
        if (!PaymentMethod.QR.name().equals(source.getPaymentMethod())) {
            throw new BusinessException("仅扫码支付订单可切换支付宝收银台");
        }

        AlipayQueryResult queryResult = queryForSafePaymentAction(orderNo);
        if (queryResult != null && queryResult.paid()) {
            settleFromQuery(source, queryResult, "switch-query");
            return toCreateResponseWithoutPaymentPayload(findOrder(orderNo));
        }

        if (waitingForBuyer(queryResult)) {
            AlipayCancelResult cancelResult = alipayPaymentClient.cancel(orderNo);
            if (!definitivelyCancelled(cancelResult)) {
                throw new BusinessException(PAYMENT_STATE_UNCERTAIN_MESSAGE);
            }
        } else if (!tradeClosed(queryResult) && !notFound(queryResult)) {
            throw new BusinessException(PAYMENT_STATE_UNCERTAIN_MESSAGE);
        }

        int closedRows = closeCreatedOrder(orderNo);
        PaymentOrder current = findOrder(orderNo);
        if (PaymentOrderStatus.PAID.name().equals(current.getStatus())) {
            return toCreateResponseWithoutPaymentPayload(current);
        }
        if (closedRows == 0 || !PaymentOrderStatus.CLOSED.name().equals(current.getStatus())) {
            throw new BusinessException(PAYMENT_STATE_UNCERTAIN_MESSAGE);
        }

        PaymentOrder pageOrder = cloneAsPageOrder(source);
        return preparePayAndInsert(pageOrder);
    }

    @Override
    public void processExpiredQrOrders() {
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<PaymentOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PaymentOrder::getStatus, PaymentOrderStatus.CREATED.name())
                .eq(PaymentOrder::getPaymentMethod, PaymentMethod.QR.name())
                .le(PaymentOrder::getExpiresAt, now)
                .orderByAsc(PaymentOrder::getExpiresAt)
                .last("LIMIT 100");
        List<PaymentOrder> orders = paymentOrderMapper.selectList(wrapper);
        if (orders == null || orders.isEmpty()) {
            return;
        }
        for (PaymentOrder order : orders) {
            try {
                withOrderOperationLock(order.getOrderNo(), () -> {
                    PaymentOrder current = findOrder(order.getOrderNo());
                    if (PaymentOrderStatus.CREATED.name().equals(current.getStatus())
                            && isExpiredQrOrder(current, LocalDateTime.now())) {
                        processExpiredQrOrder(current);
                    }
                    return null;
                });
            } catch (Exception e) {
                log.warn("支付宝订单码过期处理失败，保留待重试: orderNo={}, message={}",
                        order.getOrderNo(), e.getMessage());
            }
        }
    }

    @Override
    public PaymentSettlement settlePaidOrder(String orderNo, String alipayTradeNo, String notifyId, BigDecimal paidAmount,
                                             String tradeStatus, String appId, String sellerId) {
        return withOrderOperationLock(orderNo, () -> transactionTemplate.execute(status -> doSettlePaidOrder(
                orderNo, alipayTradeNo, notifyId, paidAmount, tradeStatus, appId, sellerId)));
    }

    private PaymentSettlement doSettlePaidOrder(String orderNo, String alipayTradeNo, String notifyId,
                                                BigDecimal paidAmount, String tradeStatus, String appId,
                                                String sellerId) {
        if (!paidStatus(tradeStatus)) {
            return new PaymentSettlement(false, orderNo, tradeStatus);
        }
        PaymentOrder order = findOrder(orderNo);
        if (!PaymentOrderStatus.CREATED.name().equals(order.getStatus())) {
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
                .eq(PaymentOrder::getStatus, PaymentOrderStatus.CREATED.name());
        int rows = paymentOrderMapper.update(update, wrapper);
        if (rows == 0) {
            PaymentOrder current = findOrder(orderNo);
            return new PaymentSettlement(false, orderNo, current.getStatus());
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

    private PaymentOrderCreateResponse preparePayAndInsert(PaymentOrder order) {
        LocalDateTime now = LocalDateTime.now();
        order.setExpiresAt(now.plusHours(2));
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        if (PaymentMethod.QR.name().equals(order.getPaymentMethod())) {
            return prepareQrPayAndInsert(order);
        }

        AlipayPagePayResult result = alipayPaymentClient.pagePay(pagePayRequest(order));
        paymentOrderMapper.insert(order);
        return toCreateResponse(order, result.paymentHtml(), true, null);
    }

    private PaymentOrderCreateResponse prepareQrPayAndInsert(PaymentOrder order) {
        paymentOrderMapper.insert(order);
        AlipayPrecreateResult result;
        try {
            result = precreateQrWithRetry(order);
        } catch (BusinessException exception) {
            return closeOrReturnTerminalOrder(order.getOrderNo(), exception);
        }
        if (result != null && notBlank(result.qrCode())) {
            saveQrIfCreated(order.getOrderNo(), result.qrCode());
        }
        return reloadedQrCreateResponse(order.getOrderNo());
    }

    private PaymentOrderCreateResponse reloadedQrCreateResponse(String orderNo) {
        PaymentOrder current = findOrder(orderNo);
        if (!PaymentOrderStatus.CREATED.name().equals(current.getStatus())) {
            return toCreateResponse(current, null, false, null);
        }
        if (!notBlank(current.getQrcode())) {
            return toCreateResponse(current, null, false, QR_UNAVAILABLE_MESSAGE);
        }
        return toCreateResponse(current, null, true, null);
    }

    private PaymentOrderCreateResponse closeOrReturnTerminalOrder(String orderNo, BusinessException exception) {
        if (closeCreatedOrder(orderNo) > 0) {
            throw exception;
        }
        PaymentOrder current = findOrder(orderNo);
        if (PaymentOrderStatus.CREATED.name().equals(current.getStatus())) {
            throw exception;
        }
        return toCreateResponse(current, null, false, null);
    }

    private AlipayPrecreateResult precreateQrWithRetry(PaymentOrder order) {
        AlipayPrecreateRequest request = new AlipayPrecreateRequest(
                order.getOrderNo(),
                order.getProductName(),
                amountAsYuan(order.getAmountCents()),
                alipayProperties.getNotifyUrl(),
                QR_TIMEOUT_EXPRESS
        );
        for (int attempt = 1; attempt <= QR_PRECREATE_MAX_ATTEMPTS; attempt++) {
            try {
                return alipayPaymentClient.precreate(request);
            } catch (RetryablePaymentGatewayException ignored) {
                log.warn("支付宝预下单暂时失败: orderNo={}, attempt={}/3", order.getOrderNo(), attempt);
                if (attempt == QR_PRECREATE_MAX_ATTEMPTS) {
                    return null;
                }
                try {
                    paymentRetrySleeper.pauseBeforeAttempt(attempt + 1);
                } catch (BusinessException ignoredSleepInterruption) {
                    return null;
                }
            }
        }
        return null;
    }

    private void saveQrIfCreated(String orderNo, String qrCode) {
        PaymentOrder update = new PaymentOrder();
        update.setQrcode(qrCode);
        update.setUpdatedAt(LocalDateTime.now());
        LambdaUpdateWrapper<PaymentOrder> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(PaymentOrder::getOrderNo, orderNo)
                .eq(PaymentOrder::getStatus, PaymentOrderStatus.CREATED.name());
        paymentOrderMapper.update(update, wrapper);
    }

    private void saveResumedQrIfCreated(String orderNo, String qrCode) {
        LocalDateTime now = LocalDateTime.now();
        PaymentOrder update = new PaymentOrder();
        update.setQrcode(qrCode);
        update.setExpiresAt(now.plusHours(2));
        update.setUpdatedAt(now);
        LambdaUpdateWrapper<PaymentOrder> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(PaymentOrder::getOrderNo, orderNo)
                .eq(PaymentOrder::getStatus, PaymentOrderStatus.CREATED.name());
        paymentOrderMapper.update(update, wrapper);
    }

    private PaymentOrder baseOrder(Long userId, PaymentOrderType type, String productCode, String productName,
                                   int amountCents, PaymentMethod paymentMethod) {
        PaymentOrder order = new PaymentOrder();
        order.setOrderNo(nextOrderNo());
        order.setUserId(userId);
        order.setOrderType(type.name());
        order.setProductCode(productCode);
        order.setProductName(productName);
        order.setAmountCents(amountCents);
        order.setStatus(PaymentOrderStatus.CREATED.name());
        order.setChannel("ALIPAY");
        order.setPaymentMethod((paymentMethod == null ? PaymentMethod.PAGE : paymentMethod).name());
        order.setAppId(alipayProperties.getAppId());
        order.setSellerId(alipayProperties.getSellerId());
        return order;
    }

    private void processExpiredQrOrder(PaymentOrder order) {
        processExpiredQrOrder(order, alipayPaymentClient.query(order.getOrderNo()));
    }

    private void processExpiredQrOrder(PaymentOrder order, AlipayQueryResult queryResult) {
        if (queryResult != null && queryResult.paid()) {
            settleFromQuery(order, queryResult, "expiry-query");
            return;
        }
        if (tradeClosed(queryResult) || notFound(queryResult)) {
            closeCreatedOrder(order.getOrderNo());
            return;
        }
        if (waitingForBuyer(queryResult)) {
            cancelAndClose(order);
        }
    }

    private void cancelAndClose(PaymentOrder order) {
        AlipayCancelResult cancelResult = alipayPaymentClient.cancel(order.getOrderNo());
        if (definitivelyCancelled(cancelResult)) {
            closeCreatedOrder(order.getOrderNo());
        }
    }

    private int closeCreatedOrder(String orderNo) {
        PaymentOrder update = new PaymentOrder();
        update.setStatus(PaymentOrderStatus.CLOSED.name());
        update.setUpdatedAt(LocalDateTime.now());
        LambdaUpdateWrapper<PaymentOrder> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(PaymentOrder::getOrderNo, orderNo)
                .eq(PaymentOrder::getStatus, PaymentOrderStatus.CREATED.name());
        return paymentOrderMapper.update(update, wrapper);
    }

    private boolean isExpiredQrOrder(PaymentOrder order, LocalDateTime now) {
        return PaymentMethod.QR.name().equals(order.getPaymentMethod())
                && order.getExpiresAt() != null
                && !order.getExpiresAt().isAfter(now);
    }

    private MembershipTier activeTier(Long userId) {
        if (userId == null) {
            return MembershipTier.NONE;
        }
        UserMembership membership = membershipService.getActiveMembership(userId);
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

    private PaymentOrder findOwnedOrder(Long userId, String orderNo) {
        ensureLoggedIn(userId);
        PaymentOrder order = findOrder(orderNo);
        if (!userId.equals(order.getUserId())) {
            throw new BusinessException("订单不存在");
        }
        return order;
    }

    private AlipayPagePayRequest pagePayRequest(PaymentOrder order) {
        return new AlipayPagePayRequest(
                order.getOrderNo(),
                order.getProductName(),
                amountAsYuan(order.getAmountCents()),
                alipayProperties.getNotifyUrl(),
                alipayProperties.getReturnUrl(),
                alipayProperties.getTimeoutExpress()
        );
    }

    private PaymentOrder cloneAsPageOrder(PaymentOrder source) {
        PaymentOrder pageOrder = baseOrder(
                source.getUserId(),
                PaymentOrderType.valueOf(source.getOrderType()),
                source.getProductCode(),
                source.getProductName(),
                safe(source.getAmountCents()),
                PaymentMethod.PAGE
        );
        pageOrder.setBaseCredits(safe(source.getBaseCredits()));
        pageOrder.setBonusCredits(safe(source.getBonusCredits()));
        pageOrder.setTotalCredits(safe(source.getTotalCredits()));
        pageOrder.setMembershipTier(source.getMembershipTier());
        pageOrder.setMembershipPeriod(source.getMembershipPeriod());
        pageOrder.setMembershipMonths(source.getMembershipMonths());
        return pageOrder;
    }

    private void settleFromQuery(PaymentOrder order, AlipayQueryResult queryResult, String notifyId) {
        settlePaidOrder(
                order.getOrderNo(),
                queryResult.alipayTradeNo(),
                notifyId,
                queryResult.totalAmount(),
                queryResult.tradeStatus(),
                alipayProperties.getAppId(),
                alipayProperties.getSellerId()
        );
    }

    private boolean paymentCanBeRestored(AlipayQueryResult queryResult) {
        return waitingForBuyer(queryResult) || notFound(queryResult);
    }

    private boolean waitingForBuyer(AlipayQueryResult queryResult) {
        return queryResult != null && "WAIT_BUYER_PAY".equals(queryResult.tradeStatus());
    }

    private boolean tradeClosed(AlipayQueryResult queryResult) {
        return queryResult != null && "TRADE_CLOSED".equals(queryResult.tradeStatus());
    }

    private boolean notFound(AlipayQueryResult queryResult) {
        return queryResult != null && queryResult.notFound();
    }

    private boolean definitivelyCancelled(AlipayCancelResult cancelResult) {
        if (cancelResult == null || !cancelResult.success() || cancelResult.retryRequired()) {
            return false;
        }
        return "close".equalsIgnoreCase(cancelResult.action())
                || "refund".equalsIgnoreCase(cancelResult.action());
    }

    private AlipayQueryResult queryForSafePaymentAction(String orderNo) {
        try {
            return alipayPaymentClient.query(orderNo);
        } catch (BusinessException exception) {
            throw new BusinessException(PAYMENT_STATE_UNCERTAIN_MESSAGE);
        }
    }

    // Guarantees same-JVM order-operation serialization; the current deployment runs in one JVM.
    private <T> T withOrderOperationLock(String orderNo, Supplier<T> operation) {
        int stripe = Math.floorMod(orderNo == null ? 0 : orderNo.hashCode(), orderOperationLocks.length);
        ReentrantLock lock = orderOperationLocks[stripe];
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }

    private static ReentrantLock[] createOrderOperationLocks() {
        ReentrantLock[] locks = new ReentrantLock[ORDER_OPERATION_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
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

    private PaymentOrderCreateResponse toCreateResponse(PaymentOrder order, String paymentHtml,
                                                        boolean paymentReady, String paymentMessage) {
        return new PaymentOrderCreateResponse(
                order.getOrderNo(),
                order.getOrderType(),
                order.getProductName(),
                safe(order.getAmountCents()),
                safe(order.getBaseCredits()),
                safe(order.getBonusCredits()),
                safe(order.getTotalCredits()),
                order.getStatus(),
                order.getPaymentMethod(),
                paymentHtml,
                paymentReady ? order.getQrcode() : null,
                order.getExpiresAt(),
                paymentReady,
                paymentMessage
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
                order.getPaymentMethod(),
                order.getExpiresAt(),
                order.getPaidAt()
        );
    }

    private PendingPaymentOrderResponse toPendingResponse(PaymentOrder order) {
        boolean paymentReady = PaymentMethod.PAGE.name().equals(order.getPaymentMethod())
                || (PaymentMethod.QR.name().equals(order.getPaymentMethod()) && notBlank(order.getQrcode()));
        return new PendingPaymentOrderResponse(
                order.getOrderNo(),
                order.getOrderType(),
                order.getProductName(),
                safe(order.getAmountCents()),
                order.getPaymentMethod(),
                paymentReady,
                order.getStatus(),
                order.getExpiresAt(),
                order.getCreatedAt()
        );
    }

    private PaymentOrderCreateResponse toCreateResponseWithoutPaymentPayload(PaymentOrder order) {
        return toCreateResponse(order, null, false, null);
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
