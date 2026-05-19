package com.manga.ai.commerce.service;

import com.manga.ai.commerce.service.dto.CommerceModelProfile;

public interface CommerceModelProfileService {
    CommerceModelProfile generateProfile(String preferredName, String gender, String modelType, String ethnicity, String bodyProfile);
}
