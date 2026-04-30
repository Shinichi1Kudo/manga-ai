package com.manga.ai.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 兑换请求DTO
 */
@Data
public class RedeemRequest {
    @NotBlank(message = "兑换码不能为空")
    private String code;
}
