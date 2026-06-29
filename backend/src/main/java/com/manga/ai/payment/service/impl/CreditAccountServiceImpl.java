package com.manga.ai.payment.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.manga.ai.common.enums.CreditUsageType;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.payment.entity.CreditBalanceBucket;
import com.manga.ai.payment.entity.PaymentOrder;
import com.manga.ai.payment.mapper.CreditBalanceBucketMapper;
import com.manga.ai.payment.service.CreditAccountService;
import com.manga.ai.user.entity.User;
import com.manga.ai.user.mapper.UserMapper;
import com.manga.ai.user.service.CreditRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreditAccountServiceImpl implements CreditAccountService {

    private static final int BONUS_EXPIRE_DAYS = 30;

    private final CreditBalanceBucketMapper creditBalanceBucketMapper;
    private final UserMapper userMapper;
    private final CreditRecordService creditRecordService;

    @Override
    @Transactional
    public void grantRechargeCredits(PaymentOrder order) {
        LocalDateTime baseTime = order.getPaidAt() == null ? LocalDateTime.now() : order.getPaidAt();
        if (safe(order.getBaseCredits()) > 0) {
            grantCredits(order.getUserId(), order.getBaseCredits(), "recharge_base", order.getOrderNo(), null);
        }
        if (safe(order.getBonusCredits()) > 0) {
            grantCredits(order.getUserId(), order.getBonusCredits(), "recharge_bonus", order.getOrderNo(),
                    baseTime.plusDays(BONUS_EXPIRE_DAYS));
        }
        creditRecordService.recordReward(
                order.getUserId(),
                safe(order.getTotalCredits()),
                CreditUsageType.RECHARGE.getCode(),
                "积分充值-" + order.getProductName() + "-" + order.getOrderNo()
        );
        log.info("充值积分入账完成: userId={}, orderNo={}, credits={}",
                order.getUserId(), order.getOrderNo(), order.getTotalCredits());
    }

    @Override
    @Transactional
    public void grantMembershipCredits(PaymentOrder order) {
        LocalDateTime baseTime = order.getPaidAt() == null ? LocalDateTime.now() : order.getPaidAt();
        int credits = safe(order.getTotalCredits());
        if (credits <= 0) {
            return;
        }
        grantCredits(order.getUserId(), credits, "membership_bonus", order.getOrderNo(), baseTime.plusDays(BONUS_EXPIRE_DAYS));
        creditRecordService.recordReward(
                order.getUserId(),
                credits,
                CreditUsageType.MEMBERSHIP.getCode(),
                "会员赠送积分-" + order.getProductName() + "-" + order.getOrderNo()
        );
        log.info("会员积分入账完成: userId={}, orderNo={}, credits={}",
                order.getUserId(), order.getOrderNo(), credits);
    }

    @Override
    @Transactional
    public void consumeCredits(Long userId, int amount, String usageType, String description, Long referenceId, String referenceType) {
        if (amount <= 0) {
            throw new BusinessException("扣除积分数量必须大于0");
        }

        ensureLegacyBucket(userId);

        LambdaUpdateWrapper<User> userWrapper = new LambdaUpdateWrapper<>();
        userWrapper.eq(User::getId, userId)
                .ge(User::getCredits, amount)
                .setSql("credits = credits - " + amount)
                .set(User::getUpdatedAt, LocalDateTime.now());
        int rows = userMapper.update(null, userWrapper);
        if (rows == 0) {
            User user = userMapper.selectById(userId);
            if (user == null) {
                throw new BusinessException("用户不存在");
            }
            throw new BusinessException("积分不足，需要" + amount + "积分，当前" + user.getCredits() + "积分");
        }

        int remaining = amount;
        for (CreditBalanceBucket bucket : activeBuckets(userId)) {
            if (remaining <= 0) {
                break;
            }
            int bucketRemaining = safe(bucket.getRemainingAmount());
            if (bucketRemaining <= 0) {
                continue;
            }
            int used = Math.min(bucketRemaining, remaining);
            bucket.setRemainingAmount(bucketRemaining - used);
            bucket.setUpdatedAt(LocalDateTime.now());
            creditBalanceBucketMapper.updateById(bucket);
            remaining -= used;
        }
        if (remaining > 0) {
            throw new BusinessException("积分批次余额异常，请稍后重试");
        }

        if (usageType != null && description != null) {
            creditRecordService.recordDeduction(userId, amount, usageType, description, referenceId, referenceType);
        }
    }

    private void grantCredits(Long userId, int amount, String sourceType, String sourceOrderNo, LocalDateTime expiresAt) {
        if (amount <= 0) {
            return;
        }
        LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(User::getId, userId)
                .setSql("credits = credits + " + amount)
                .set(User::getUpdatedAt, LocalDateTime.now());
        int rows = userMapper.update(null, wrapper);
        if (rows == 0) {
            throw new BusinessException("用户不存在");
        }

        CreditBalanceBucket bucket = new CreditBalanceBucket();
        bucket.setUserId(userId);
        bucket.setSourceType(sourceType);
        bucket.setSourceOrderNo(sourceOrderNo);
        bucket.setTotalAmount(amount);
        bucket.setRemainingAmount(amount);
        bucket.setExpiresAt(expiresAt);
        bucket.setCreatedAt(LocalDateTime.now());
        bucket.setUpdatedAt(LocalDateTime.now());
        creditBalanceBucketMapper.insert(bucket);
    }

    private void ensureLegacyBucket(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        int currentCredits = safe(user.getCredits());
        int bucketCredits = allBuckets(userId).stream()
                .mapToInt(bucket -> safe(bucket.getRemainingAmount()))
                .sum();
        int legacyCredits = currentCredits - bucketCredits;
        if (legacyCredits <= 0) {
            return;
        }
        CreditBalanceBucket bucket = new CreditBalanceBucket();
        bucket.setUserId(userId);
        bucket.setSourceType("legacy");
        bucket.setSourceOrderNo(null);
        bucket.setTotalAmount(legacyCredits);
        bucket.setRemainingAmount(legacyCredits);
        bucket.setExpiresAt(null);
        bucket.setCreatedAt(LocalDateTime.now());
        bucket.setUpdatedAt(LocalDateTime.now());
        creditBalanceBucketMapper.insert(bucket);
    }

    private List<CreditBalanceBucket> activeBuckets(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        return allBuckets(userId).stream()
                .filter(bucket -> safe(bucket.getRemainingAmount()) > 0)
                .filter(bucket -> bucket.getExpiresAt() == null || bucket.getExpiresAt().isAfter(now))
                .sorted(Comparator
                        .comparing((CreditBalanceBucket bucket) -> bucket.getExpiresAt() == null)
                        .thenComparing(bucket -> bucket.getExpiresAt() == null ? LocalDateTime.MAX : bucket.getExpiresAt())
                        .thenComparing(CreditBalanceBucket::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private List<CreditBalanceBucket> allBuckets(Long userId) {
        LambdaQueryWrapper<CreditBalanceBucket> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CreditBalanceBucket::getUserId, userId);
        return creditBalanceBucketMapper.selectList(wrapper);
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }
}
