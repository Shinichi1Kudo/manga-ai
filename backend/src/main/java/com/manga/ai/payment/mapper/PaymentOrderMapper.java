package com.manga.ai.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.manga.ai.payment.entity.PaymentOrder;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PaymentOrderMapper extends BaseMapper<PaymentOrder> {
}
