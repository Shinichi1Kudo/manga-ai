package com.manga.ai.payment.dto;

public record AlipayCancelResult(boolean success, boolean retryRequired, String action) {
}
