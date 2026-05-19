package com.manga.ai.commerce.service;

import com.manga.ai.commerce.dto.CommerceModelCreateRequest;
import com.manga.ai.commerce.dto.CommerceModelVO;
import com.manga.ai.commerce.dto.CommerceProductCreateRequest;
import com.manga.ai.commerce.dto.CommerceProductVO;
import com.manga.ai.commerce.dto.CommerceProjectCreateRequest;
import com.manga.ai.commerce.dto.CommerceProjectVO;
import com.manga.ai.commerce.dto.CommerceSeriesCreateRequest;
import com.manga.ai.commerce.dto.CommerceSeriesVO;
import com.manga.ai.commerce.service.dto.CommerceModelProfile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface CommerceService {
    CommerceSeriesVO createSeries(CommerceSeriesCreateRequest request);

    List<CommerceSeriesVO> listSeries();

    CommerceSeriesVO getSeries(Long seriesId);

    CommerceModelProfile generateModelPrompt(String preferredName, String gender, String modelType, String ethnicity, String bodyProfile);

    CommerceModelVO createModel(Long seriesId, CommerceModelCreateRequest request);

    CommerceModelVO startModelImageGeneration(Long modelId);

    CommerceModelVO getModel(Long modelId);

    List<CommerceModelVO> listModels(Long seriesId);

    CommerceModelVO regenerateModel(Long modelId);

    CommerceModelVO confirmModel(Long modelId);

    CommerceModelVO unlockModel(Long modelId);

    CommerceProductVO createProduct(Long modelId, CommerceProductCreateRequest request);

    List<CommerceProductVO> listProducts(Long modelId);

    String uploadProductImage(MultipartFile file);

    CommerceProjectVO createProject(Long productId, CommerceProjectCreateRequest request);

    CommerceProjectVO getProject(Long projectId);

    CommerceProjectVO generateProjectVideo(Long projectId);
}
