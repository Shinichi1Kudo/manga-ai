package com.manga.ai.commerce.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class CommerceSceneGenerationStartRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private List<Long> sceneIds = new ArrayList<>();
}
