package com.manga.ai.llm.service;

import com.manga.ai.llm.dto.ScriptParseResult;

import java.util.List;

/**
 * 剧本解析服务接口
 */
public interface ScriptParseService {

    /**
     * 解析剧本文本，提取分镜信息
     * @param scriptText 剧本文本
     * @param seriesId 系列ID（用于获取角色列表）
     * @return 解析结果
     */
    ScriptParseResult parseScript(String scriptText, Long seriesId);

    /**
     * 获取已知角色列表（用于提示LLM）
     * @param seriesId 系列ID
     * @return 角色名称列表
     */
    List<String> getKnownCharacters(Long seriesId);
}
