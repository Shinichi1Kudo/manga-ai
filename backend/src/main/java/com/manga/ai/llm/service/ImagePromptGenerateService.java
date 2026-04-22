package com.manga.ai.llm.service;

/**
 * 图片提示词生成服务
 * 使用 LLM 分析剧本和系列信息，生成最适合的图片提示词
 */
public interface ImagePromptGenerateService {

    /**
     * 为场景生成图片提示词
     *
     * @param seriesId 系列ID
     * @param sceneName 场景名称
     * @param episodeId 剧集ID（用于获取剧本内容）
     * @return 生成的图片提示词
     */
    String generateScenePrompt(Long seriesId, String sceneName, Long episodeId);

    /**
     * 为道具生成图片提示词
     *
     * @param seriesId 系列ID
     * @param propName 道具名称
     * @param episodeId 剧集ID（用于获取剧本内容）
     * @return 生成的图片提示词
     */
    String generatePropPrompt(Long seriesId, String propName, Long episodeId);
}
