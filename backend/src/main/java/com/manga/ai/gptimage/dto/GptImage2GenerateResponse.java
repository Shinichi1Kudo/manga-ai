package com.manga.ai.gptimage.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * GPT-Image2 图片生成响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GptImage2GenerateResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private String imageUrl;

    private String model;

    private String mode;
}
