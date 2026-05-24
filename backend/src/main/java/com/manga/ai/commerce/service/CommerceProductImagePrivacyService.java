package com.manga.ai.commerce.service;

import com.manga.ai.commerce.dto.CommerceProductCreateRequest;

import java.util.List;

public interface CommerceProductImagePrivacyService {

    List<CommerceProductCreateRequest.ProductImageItem> sanitizeProductReferences(
            List<CommerceProductCreateRequest.ProductImageItem> references);
}
