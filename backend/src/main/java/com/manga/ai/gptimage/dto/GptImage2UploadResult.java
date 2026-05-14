package com.manga.ai.gptimage.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * GPT-Image2 参考图上传结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GptImage2UploadResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private String url;
}
