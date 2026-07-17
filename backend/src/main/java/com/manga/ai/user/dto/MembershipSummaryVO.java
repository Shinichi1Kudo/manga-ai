package com.manga.ai.user.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.manga.ai.payment.entity.UserMembership;
import com.manga.ai.payment.enums.MembershipTier;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MembershipSummaryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String tier;
    private String name;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime expiresAt;

    public static MembershipSummaryVO free() {
        return new MembershipSummaryVO(MembershipTier.NONE.name(), "免费会员", null);
    }

    public static MembershipSummaryVO from(UserMembership membership) {
        if (membership == null) {
            return free();
        }

        MembershipTier tier;
        try {
            tier = MembershipTier.valueOf(membership.getTier());
        } catch (RuntimeException exception) {
            return free();
        }

        if (tier == MembershipTier.NONE) {
            return free();
        }
        return new MembershipSummaryVO(tier.name(), tier.getDisplayName(), membership.getExpiresAt());
    }
}
