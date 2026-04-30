package com.manga.ai.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.manga.ai.common.enums.CreditUsageType;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.user.dto.RedeemResultVO;
import com.manga.ai.user.entity.RedemptionCode;
import com.manga.ai.user.entity.User;
import com.manga.ai.user.mapper.RedemptionCodeMapper;
import com.manga.ai.user.mapper.UserMapper;
import com.manga.ai.user.service.CreditRecordService;
import com.manga.ai.user.service.RedemptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 兑换码服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedemptionServiceImpl implements RedemptionService {

    private final RedemptionCodeMapper redemptionCodeMapper;
    private final UserMapper userMapper;
    private final CreditRecordService creditRecordService;
    private final StringRedisTemplate redisTemplate;

    private static final String REDEEM_LOCK_PREFIX = "redeem:lock:";
    private static final long LOCK_WAIT_TIME = 3; // 等待锁的最大秒数
    private static final long LOCK_EXPIRE_TIME = 10; // 锁过期时间（秒）

    @Override
    public RedeemResultVO redeem(Long userId, String code) {
        String lockKey = REDEEM_LOCK_PREFIX + code;

        // 1. 尝试获取分布式锁
        boolean locked = false;
        try {
            locked = Boolean.TRUE.equals(redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, String.valueOf(userId), LOCK_EXPIRE_TIME, TimeUnit.SECONDS));

            if (!locked) {
                // 等待一小段时间重试（处理并发请求）
                Thread.sleep(100);
                locked = Boolean.TRUE.equals(redisTemplate.opsForValue()
                        .setIfAbsent(lockKey, String.valueOf(userId), LOCK_EXPIRE_TIME, TimeUnit.SECONDS));

                if (!locked) {
                    throw new BusinessException("兑换码正在被处理，请稍后重试");
                }
            }

            // 2. 获取锁成功，执行兑换逻辑
            return doRedeem(userId, code);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("系统繁忙，请稍后重试");
        } finally {
            // 3. 释放锁（只有锁的持有者才能释放）
            if (locked) {
                try {
                    String lockValue = redisTemplate.opsForValue().get(lockKey);
                    if (String.valueOf(userId).equals(lockValue)) {
                        redisTemplate.delete(lockKey);
                    }
                } catch (Exception e) {
                    log.warn("释放锁失败: lockKey={}", lockKey, e);
                }
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public RedeemResultVO doRedeem(Long userId, String code) {
        // 1. 查询兑换码（区分大小写）
        LambdaQueryWrapper<RedemptionCode> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(RedemptionCode::getCode, code);
        RedemptionCode redemptionCode = redemptionCodeMapper.selectOne(queryWrapper);

        // 2. 验证兑换码是否存在
        if (redemptionCode == null) {
            throw new BusinessException("兑换码不存在");
        }

        // 3. 验证兑换码是否已使用
        if (redemptionCode.getStatus() == 1) {
            throw new BusinessException("兑换码已被使用");
        }

        // 4. 更新兑换码状态（双重保险：数据库层面也做校验）
        LambdaUpdateWrapper<RedemptionCode> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(RedemptionCode::getId, redemptionCode.getId())
                .eq(RedemptionCode::getStatus, 0)
                .set(RedemptionCode::getStatus, 1)
                .set(RedemptionCode::getUsedBy, userId)
                .set(RedemptionCode::getUsedAt, LocalDateTime.now());

        int rows = redemptionCodeMapper.update(null, updateWrapper);
        if (rows == 0) {
            throw new BusinessException("兑换码已被使用");
        }

        // 5. 增加用户积分（原子操作）
        int credits = redemptionCode.getCredits();
        LambdaUpdateWrapper<User> userUpdateWrapper = new LambdaUpdateWrapper<>();
        userUpdateWrapper.eq(User::getId, userId)
                .setSql("credits = credits + " + credits)
                .set(User::getUpdatedAt, LocalDateTime.now());
        userMapper.update(null, userUpdateWrapper);

        // 6. 获取最新余额
        User user = userMapper.selectById(userId);
        int balanceAfter = user.getCredits();

        // 7. 记录积分变动
        creditRecordService.recordReward(userId, credits,
                CreditUsageType.REDEEM_CODE.getCode(),
                "兑换码兑换: " + code);

        log.info("兑换成功: userId={}, code={}, credits={}", userId, code, credits);

        // 8. 返回结果
        RedeemResultVO result = new RedeemResultVO();
        result.setCredits(credits);
        result.setBalanceAfter(balanceAfter);
        return result;
    }
}
