package com.manga.ai.llm.service;

import com.manga.ai.llm.dto.ScriptParseResult;

import java.util.List;
import java.util.Map;

/**
 * 剧本解析服务接口
 */
public interface ScriptParseService {

    /**
     * 解析剧本文本，提取分镜信息（完整解析）
     * @param scriptText 剧本文本
     * @param seriesId 系列ID（用于获取角色列表）
     * @return 解析结果
     */
    ScriptParseResult parseScript(String scriptText, Long seriesId);

    /**
     * 只解析场景和道具（快速）
     * @param scriptText 剧本文本
     * @param seriesId 系列ID
     * @return 解析结果（只包含scenes和props）
     */
    ScriptParseResult parseAssetsOnly(String scriptText, Long seriesId);

    /**
     * 只解析分镜（需要已知的场景编码映射）
     * @param scriptText 剧本文本
     * @param seriesId 系列ID
     * @param sceneCodeToIdMap 场景编码到ID的映射
     * @return 解析结果（只包含shots）
     */
    ScriptParseResult parseShots(String scriptText, Long seriesId, Map<String, Long> sceneCodeToIdMap);

    /**
     * 只解析分镜（支持解析模式）
     * @param scriptText 剧本文本
     * @param seriesId 系列ID
     * @param sceneCodeToIdMap 场景编码到ID的映射
     * @param parseMode 解析模式：default/detailed
     * @return 解析结果（只包含shots）
     */
    ScriptParseResult parseShots(String scriptText, Long seriesId, Map<String, Long> sceneCodeToIdMap, String parseMode);

    /**
     * 获取已知角色列表（用于提示LLM）
     * @param seriesId 系列ID
     * @return 角色名称列表
     */
    List<String> getKnownCharacters(Long seriesId);
}
