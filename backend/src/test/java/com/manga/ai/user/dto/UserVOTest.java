package com.manga.ai.user.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manga.ai.payment.entity.UserMembership;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UserVOTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void newUserResponseContainsFreeMembershipSummary() throws Exception {
        JsonNode membership = objectMapper.readTree(objectMapper.writeValueAsString(new UserVO()))
                .path("membership");

        assertThat(membership.isObject()).isTrue();
        assertThat(membership.path("tier").asText()).isEqualTo("NONE");
        assertThat(membership.path("name").asText()).isEqualTo("免费会员");
        assertThat(membership.path("expiresAt").isNull()).isTrue();
    }

    @Test
    void paidMembershipSummaryMapsEverySupportedTierName() {
        Map<String, String> expectedNames = Map.of(
                "BASIC", "基础会员",
                "STANDARD", "标准会员",
                "ADVANCED", "高级会员",
                "SUPER", "超级会员"
        );
        LocalDateTime expiresAt = LocalDateTime.of(2027, 6, 29, 12, 0);

        expectedNames.forEach((tier, name) -> {
            UserMembership membership = new UserMembership();
            membership.setTier(tier);
            membership.setExpiresAt(expiresAt);

            MembershipSummaryVO summary = MembershipSummaryVO.from(membership);

            assertThat(summary.getTier()).isEqualTo(tier);
            assertThat(summary.getName()).isEqualTo(name);
            assertThat(summary.getExpiresAt()).isEqualTo(expiresAt);
        });
    }
}
