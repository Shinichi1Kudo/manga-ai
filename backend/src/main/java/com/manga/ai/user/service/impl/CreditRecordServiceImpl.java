package com.manga.ai.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.manga.ai.common.enums.CreditTransactionType;
import com.manga.ai.common.enums.CreditUsageType;
import com.manga.ai.common.result.PageResult;
import com.manga.ai.user.dto.CreditRecordVO;
import com.manga.ai.user.entity.CreditRecord;
import com.manga.ai.user.entity.User;
import com.manga.ai.user.mapper.CreditRecordMapper;
import com.manga.ai.user.mapper.UserMapper;
import com.manga.ai.user.service.CreditRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 积分记录服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditRecordServiceImpl implements CreditRecordService {

    private final CreditRecordMapper creditRecordMapper;
    private final UserMapper userMapper;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    @Override
    public void recordDeduction(Long userId, int amount, String usageType, String description) {
        recordDeduction(userId, amount, usageType, description, null, null);
    }

    @Override
    public void recordDeduction(Long userId, int amount, String usageType, String description, Long referenceId, String referenceType) {
        CreditRecord record = new CreditRecord();
        record.setUserId(userId);
        record.setAmount(-amount); // 扣除为负数
        record.setType(CreditTransactionType.DEDUCT.getCode());
        record.setUsageType(usageType);
        record.setDescription(description);
        record.setReferenceId(referenceId);
        record.setReferenceType(referenceType);
        record.setCreatedAt(LocalDateTime.now());

        // 获取当前余额
        User user = userMapper.selectById(userId);
        if (user != null) {
            record.setBalanceAfter(user.getCredits());
        }

        creditRecordMapper.insert(record);
        log.info("记录积分扣除: userId={}, amount={}, description={}", userId, amount, description);
    }

    @Override
    public void recordRefund(Long userId, int amount, String description) {
        recordRefund(userId, amount, description, null, null);
    }

    @Override
    public void recordRefund(Long userId, int amount, String description, Long referenceId, String referenceType) {
        CreditRecord record = new CreditRecord();
        record.setUserId(userId);
        record.setAmount(amount); // 返还为正数
        record.setType(CreditTransactionType.REFUND.getCode());
        record.setDescription(description);
        record.setReferenceId(referenceId);
        record.setReferenceType(referenceType);
        record.setCreatedAt(LocalDateTime.now());

        // 获取当前余额
        User user = userMapper.selectById(userId);
        if (user != null) {
            record.setBalanceAfter(user.getCredits());
        }

        creditRecordMapper.insert(record);
        log.info("记录积分返还: userId={}, amount={}, description={}", userId, amount, description);
    }

    @Override
    public void recordReward(Long userId, int amount, String usageType, String description) {
        CreditRecord record = new CreditRecord();
        record.setUserId(userId);
        record.setAmount(amount); // 奖励为正数
        record.setType(CreditTransactionType.REWARD.getCode());
        record.setUsageType(usageType);
        record.setDescription(description);
        record.setCreatedAt(LocalDateTime.now());

        // 获取当前余额
        User user = userMapper.selectById(userId);
        if (user != null) {
            record.setBalanceAfter(user.getCredits());
        }

        creditRecordMapper.insert(record);
        log.info("记录积分奖励: userId={}, amount={}, description={}", userId, amount, description);
    }

    @Override
    public PageResult<CreditRecordVO> getCreditRecords(Long userId, Integer page, Integer pageSize, String type) {
        Page<CreditRecord> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<CreditRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CreditRecord::getUserId, userId);
        if (type != null && !type.isEmpty()) {
            wrapper.eq(CreditRecord::getType, type);
        }
        wrapper.orderByDesc(CreditRecord::getCreatedAt);

        Page<CreditRecord> result = creditRecordMapper.selectPage(pageParam, wrapper);

        List<CreditRecordVO> voList = result.getRecords().stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());

        return PageResult.of(voList, result.getTotal(), (long) pageSize, (long) page);
    }

    @Override
    public List<CreditRecordVO> getRecentRecords(Long userId, Integer limit) {
        List<CreditRecord> records = creditRecordMapper.selectRecentByUserId(userId, limit);
        return records.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
    }

    private CreditRecordVO convertToVO(CreditRecord record) {
        CreditRecordVO vo = new CreditRecordVO();
        BeanUtils.copyProperties(record, vo);

        // 设置类型描述
        if (CreditTransactionType.DEDUCT.getCode().equals(record.getType())) {
            vo.setTypeDesc(CreditTransactionType.DEDUCT.getDesc());
        } else if (CreditTransactionType.REFUND.getCode().equals(record.getType())) {
            vo.setTypeDesc(CreditTransactionType.REFUND.getDesc());
        } else if (CreditTransactionType.REWARD.getCode().equals(record.getType())) {
            vo.setTypeDesc(CreditTransactionType.REWARD.getDesc());
        }

        // 设置用途描述
        if (record.getUsageType() != null) {
            for (CreditUsageType usageType : CreditUsageType.values()) {
                if (usageType.getCode().equals(record.getUsageType())) {
                    vo.setUsageTypeDesc(usageType.getDesc());
                    break;
                }
            }
        }

        // 格式化时间
        if (record.getCreatedAt() != null) {
            vo.setCreatedAt(record.getCreatedAt().format(TIME_FORMATTER));
        }

        return vo;
    }
}
