package com.manga.ai.commerce.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class CommerceSceneBatchCreateRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private List<String> sceneTypes = new ArrayList<>();
    private List<String> customScenes = new ArrayList<>();
    private String brandSceneName;
    private String aspectRatio;
    private String resolution;
    private Integer countPerScene;
    private String description;
}
