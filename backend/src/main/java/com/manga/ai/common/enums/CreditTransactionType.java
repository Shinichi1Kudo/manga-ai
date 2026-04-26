package com.manga.ai.common.enums;

/**
 * 积分交易类型
 */
public enum CreditTransactionType {

    DEDUCT("deduct", "扣除"),
    REFUND("refund", "返还"),
    REWARD("reward", "奖励");

    private final String code;
    private final String desc;

    CreditTransactionType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
