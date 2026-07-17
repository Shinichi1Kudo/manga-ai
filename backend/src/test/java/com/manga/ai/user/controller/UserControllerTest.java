package com.manga.ai.user.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manga.ai.common.dto.Result;
import com.manga.ai.payment.entity.UserMembership;
import com.manga.ai.payment.service.MembershipService;
import com.manga.ai.user.dto.UserVO;
import com.manga.ai.user.entity.User;
import com.manga.ai.user.service.TokenService;
import com.manga.ai.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserControllerTest {

    @Test
    void userInfoIncludesCurrentHighestMembershipSummary() throws Exception {
        UserService userService = mock(UserService.class);
        MembershipService membershipService = mock(MembershipService.class);
        UserController controller = new UserController(userService, mock(TokenService.class), membershipService);

        User user = new User();
        user.setId(21L);
        user.setNickname("超级用户");
        user.setCredits(1150);
        user.setStatus(1);
        when(userService.getCurrentUser()).thenReturn(user);

        UserMembership membership = new UserMembership();
        membership.setTier("SUPER");
        membership.setExpiresAt(LocalDateTime.of(2027, 6, 29, 12, 0));
        when(membershipService.getActiveMembership(21L)).thenReturn(membership);

        Result<UserVO> result = controller.getUserInfo();
        JsonNode summary = new ObjectMapper().findAndRegisterModules()
                .readTree(new ObjectMapper().findAndRegisterModules().writeValueAsString(result.getData()))
                .path("membership");

        assertThat(summary.path("tier").asText()).isEqualTo("SUPER");
        assertThat(summary.path("name").asText()).isEqualTo("超级会员");
        assertThat(summary.path("expiresAt").asText()).startsWith("2027-06-29T12:00");
    }
}
