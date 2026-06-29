package com.manga.ai.payment.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.manga.ai.payment.entity.PaymentOrder;
import com.manga.ai.payment.entity.UserMembership;
import com.manga.ai.payment.enums.MembershipTier;
import com.manga.ai.payment.mapper.UserMembershipMapper;
import com.manga.ai.payment.service.CreditAccountService;
import com.manga.ai.payment.service.MembershipService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MembershipServiceImpl implements MembershipService {

    private final UserMembershipMapper userMembershipMapper;
    private final CreditAccountService creditAccountService;

    @Override
    public UserMembership getActiveMembership(Long userId) {
        LambdaQueryWrapper<UserMembership> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserMembership::getUserId, userId)
                .eq(UserMembership::getStatus, "ACTIVE")
                .gt(UserMembership::getExpiresAt, LocalDateTime.now());
        List<UserMembership> memberships = userMembershipMapper.selectList(wrapper);
        return memberships.stream()
                .max(Comparator
                        .comparingInt((UserMembership membership) -> tierRank(membership.getTier()))
                        .thenComparing(UserMembership::getExpiresAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
    }

    @Override
    @Transactional
    public void activateMembership(PaymentOrder order) {
        LocalDateTime now = order.getPaidAt() == null ? LocalDateTime.now() : order.getPaidAt();
        UserMembership current = getActiveMembership(order.getUserId());
        LocalDateTime start = current != null && current.getExpiresAt() != null && current.getTier().equals(order.getMembershipTier())
                ? current.getExpiresAt()
                : now;

        UserMembership membership = new UserMembership();
        membership.setUserId(order.getUserId());
        membership.setTier(order.getMembershipTier());
        membership.setProductCode(order.getProductCode());
        membership.setPeriod(order.getMembershipPeriod());
        membership.setMonths(order.getMembershipMonths());
        membership.setStatus("ACTIVE");
        membership.setSourceOrderNo(order.getOrderNo());
        membership.setStartedAt(now);
        membership.setExpiresAt(start.plusMonths(order.getMembershipMonths() == null ? 1 : order.getMembershipMonths()));
        membership.setCreatedAt(now);
        membership.setUpdatedAt(now);
        userMembershipMapper.insert(membership);

        creditAccountService.grantMembershipCredits(order);
    }

    private int tierRank(String tier) {
        try {
            return MembershipTier.valueOf(tier).getRank();
        } catch (Exception e) {
            return 0;
        }
    }
}
