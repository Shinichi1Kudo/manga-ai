package com.manga.ai.commerce.service;

import com.manga.ai.commerce.dto.CommerceModelCreateRequest;
import com.manga.ai.commerce.dto.CommerceModelVO;
import com.manga.ai.commerce.dto.CommerceProductCreateRequest;
import com.manga.ai.commerce.dto.CommerceProductVO;
import com.manga.ai.commerce.dto.CommerceProjectCreateRequest;
import com.manga.ai.commerce.dto.CommerceProjectVO;
import com.manga.ai.commerce.dto.CommerceSceneBatchCreateRequest;
import com.manga.ai.commerce.dto.CommerceSceneGenerationStartRequest;
import com.manga.ai.commerce.dto.CommerceSceneVO;
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

    List<CommerceModelVO> listModelLibrary(Long targetSeriesId);

    CommerceModelVO reuseModelIntoSeries(Long seriesId, Long sourceModelId);

    CommerceModelVO regenerateModel(Long modelId);

    CommerceModelVO confirmModel(Long modelId);

    CommerceModelVO unlockModel(Long modelId);

    CommerceProductVO createProduct(Long modelId, CommerceProductCreateRequest request);

    CommerceProductVO updateProductImages(Long productId, CommerceProductCreateRequest request);

    List<CommerceProductVO> listProducts(Long modelId);

    String uploadProductImage(MultipartFile file);

    List<CommerceSceneVO> listScenes(Long seriesId);

    List<CommerceSceneVO> prepareSceneBatch(Long seriesId, CommerceSceneBatchCreateRequest request);

    List<CommerceSceneVO> startSceneBatch(Long seriesId, CommerceSceneGenerationStartRequest request);

    CommerceProjectVO createProject(Long productId, CommerceProjectCreateRequest request);

    List<CommerceProjectVO> listProductProjects(Long productId, String contentType);

    CommerceProjectVO getProject(Long projectId);

    CommerceProjectVO startProjectVideoGeneration(Long projectId);

    CommerceProjectVO generateProjectVideo(Long projectId);
}
