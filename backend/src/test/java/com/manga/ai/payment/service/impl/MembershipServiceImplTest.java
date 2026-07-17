package com.manga.ai.payment.service.impl;

import com.manga.ai.payment.entity.UserMembership;
import com.manga.ai.payment.mapper.UserMembershipMapper;
import com.manga.ai.payment.service.CreditAccountService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MembershipServiceImplTest {

    @Test
    void activeMembershipUsesHighestTierWhenSeveralAreValid() {
        UserMembershipMapper mapper = mock(UserMembershipMapper.class);
        MembershipServiceImpl service = new MembershipServiceImpl(mapper, mock(CreditAccountService.class));
        LocalDateTime expiry = LocalDateTime.now().plusMonths(1);
        UserMembership basic = membership("BASIC", expiry.plusMonths(4));
        UserMembership advanced = membership("ADVANCED", expiry);
        UserMembership standard = membership("STANDARD", expiry.plusMonths(8));
        when(mapper.selectList(any())).thenReturn(List.of(basic, advanced, standard));

        UserMembership result = service.getActiveMembership(99L);

        assertThat(result).isSameAs(advanced);
    }

    private UserMembership membership(String tier, LocalDateTime expiresAt) {
        UserMembership membership = new UserMembership();
        membership.setTier(tier);
        membership.setExpiresAt(expiresAt);
        return membership;
    }
}
