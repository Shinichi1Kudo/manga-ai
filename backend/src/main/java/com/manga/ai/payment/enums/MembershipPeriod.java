package com.manga.ai.payment.enums;

public enum MembershipPeriod {

    YEAR("包年", 12),
    MONTH("包月", 1),
    QUARTER("包季", 3),
    SINGLE_MONTH("单月购买", 1);

    private final String label;
    private final int months;

    MembershipPeriod(String label, int months) {
        this.label = label;
        this.months = months;
    }

    public String getLabel() {
        return label;
    }

    public int getMonths() {
        return months;
    }
}
