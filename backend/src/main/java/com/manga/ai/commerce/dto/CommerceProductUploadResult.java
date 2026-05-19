package com.manga.ai.commerce.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommerceProductUploadResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private String url;
}
