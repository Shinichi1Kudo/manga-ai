package com.manga.ai.payment.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("payment_order")
public class PaymentOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;
    private Long userId;
    private String orderType;
    private String productCode;
    private String productName;
    private Integer amountCents;
    private String status;
    private String channel;
    private String paymentMethod;
    private String qrcode;
    private LocalDateTime expiresAt;
    private String alipayTradeNo;
    private String appId;
    private String sellerId;
    private String notifyId;
    private String tradeStatus;
    private Integer baseCredits;
    private Integer bonusCredits;
    private Integer totalCredits;
    private String membershipTier;
    private String membershipPeriod;
    private Integer membershipMonths;
    private LocalDateTime paidAt;
    private LocalDateTime creditedAt;
    private String rawNotify;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
