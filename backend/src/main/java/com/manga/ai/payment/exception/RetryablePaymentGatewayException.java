package com.manga.ai.payment.exception;

import com.manga.ai.common.exception.BusinessException;

public final class RetryablePaymentGatewayException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public RetryablePaymentGatewayException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }
}
