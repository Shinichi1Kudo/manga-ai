package com.manga.ai.commerce.service;

import com.manga.ai.commerce.dto.CommerceModelCreateRequest;
import com.manga.ai.commerce.dto.CommerceModelVO;
import com.manga.ai.commerce.dto.CommerceProductCreateRequest;
import com.manga.ai.commerce.dto.CommerceProductVO;
import com.manga.ai.commerce.dto.CommerceProjectCreateRequest;
import com.manga.ai.commerce.dto.CommerceProjectVO;
import com.manga.ai.commerce.dto.CommerceSeriesCreateRequest;
import com.manga.ai.commerce.dto.CommerceSeriesVO;

import java.util.List;

public interface CommerceService {
    CommerceSeriesVO createSeries(CommerceSeriesCreateRequest request);

    List<CommerceSeriesVO> listSeries();

    CommerceSeriesVO getSeries(Long seriesId);

    CommerceModelVO createModel(Long seriesId, CommerceModelCreateRequest request);

    List<CommerceModelVO> listModels(Long seriesId);

    CommerceModelVO confirmModel(Long modelId);

    CommerceProductVO createProduct(Long modelId, CommerceProductCreateRequest request);

    List<CommerceProductVO> listProducts(Long modelId);

    CommerceProjectVO createProject(Long productId, CommerceProjectCreateRequest request);

    CommerceProjectVO getProject(Long projectId);

    CommerceProjectVO generateProjectVideo(Long projectId);
}
