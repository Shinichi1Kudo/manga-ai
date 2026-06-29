package com.manga.ai.payment.enums;

public enum MembershipTier {

    NONE("无会员", 0, 0),
    BASIC("基础会员", 1, 5),
    STANDARD("标准会员", 2, 8),
    ADVANCED("高级会员", 3, 12),
    SUPER("超级会员", 4, 15);

    private final String displayName;
    private final int rank;
    private final int rechargeBonusPercent;

    MembershipTier(String displayName, int rank, int rechargeBonusPercent) {
        this.displayName = displayName;
        this.rank = rank;
        this.rechargeBonusPercent = rechargeBonusPercent;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getRank() {
        return rank;
    }

    public int getRechargeBonusPercent() {
        return rechargeBonusPercent;
    }
}
