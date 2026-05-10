package com.manga.ai.llm.service.impl;

import com.manga.ai.episode.entity.Episode;
import com.manga.ai.episode.mapper.EpisodeMapper;
import com.manga.ai.llm.dto.LLMResponse;
import com.manga.ai.llm.service.DoubaoLLMService;
import com.manga.ai.llm.service.ImagePromptGenerateService;
import com.manga.ai.series.entity.Series;
import com.manga.ai.series.mapper.SeriesMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 图片提示词生成服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImagePromptGenerateServiceImpl implements ImagePromptGenerateService {

    private final DoubaoLLMService llmService;
    private final SeriesMapper seriesMapper;
    private final EpisodeMapper episodeMapper;

    private static final String SCENE_SYSTEM_PROMPT = """
            你是一个专业的AI图像提示词生成专家。根据用户提供的剧本信息，生成适合场景背景图的英文提示词。
            提示词应该包含：场景环境描述、光线氛围、风格关键词、质量要求。

            重要规则：
            1. 场景图是纯背景图，绝对不能包含任何人物、角色、人像
            2. 只描述环境、建筑、家具、装饰等场景元素
            3. 必须在提示词末尾加上 "no people, no characters, no figures, empty scene, background only"
            4. 必须严格沿用用户提供的“系列风格”，不要把动漫/3D动漫/卡通风格改成真人、写实摄影或 live-action
            5. 如果系列风格包含动漫、动画、卡通、二次元、3D 等表达，提示词必须包含 "ultra-detailed 3D anime render, stylized 3D animation, not live action, not photorealistic"

            只输出英文提示词，不要包含任何解释或额外内容。
            """;

    private static final String PROP_SYSTEM_PROMPT = """
            你是一个专业的AI图像提示词生成专家。根据用户提供的剧本信息，生成适合道具产品图的英文提示词。
            提示词应该包含：道具外观描述、材质细节、风格关键词、质量要求、透明背景要求。

            重要规则：
            1. 道具图是独立单品，绝对不能包含手、人物、或其他物品
            2. 只描述道具本身的外观、材质、细节
            3. 必须使用透明背景抠图："transparent background, isolated clean cutout, alpha channel style"
            4. 不要使用白底、纯色背景、场景背景、桌面、房间、渐变背景或纹理背景
            5. 必须严格沿用用户提供的“系列风格”，不要把动漫/3D动漫/卡通风格改成真人、写实摄影或 live-action
            6. 如果系列风格包含动漫、动画、卡通、二次元、3D 等表达，提示词必须包含 "ultra-detailed 3D anime render, stylized 3D animation, not live action, not photorealistic"
            7. 必须在提示词末尾加上 "no hands, no hands holding, no people, standalone prop"

            只输出英文提示词，不要包含任何解释或额外内容。
            """;

    @Override
    public String generateScenePrompt(Long seriesId, String sceneName, Long episodeId) {
        log.info("生成场景提示词: seriesId={}, sceneName={}, episodeId={}", seriesId, sceneName, episodeId);

        Series series = seriesMapper.selectById(seriesId);
        if (series == null) {
            throw new RuntimeException("系列不存在: " + seriesId);
        }

        String scriptText = "";
        if (episodeId != null) {
            Episode episode = episodeMapper.selectById(episodeId);
            if (episode != null && episode.getScriptText() != null) {
                // 截取前2000字符，避免过长
                scriptText = episode.getScriptText();
                if (scriptText.length() > 2000) {
                    scriptText = scriptText.substring(0, 2000) + "...";
                }
            }
        }

        String userPrompt = buildSceneUserPrompt(series, sceneName, scriptText);

        try {
            LLMResponse response = llmService.chat(SCENE_SYSTEM_PROMPT, userPrompt);
            if (response != null && response.getContent() != null) {
                log.info("场景提示词生成成功: {}", response.getContent());
                return response.getContent().trim();
            }
        } catch (Exception e) {
            log.error("生成场景提示词失败", e);
        }

        // 降级：使用基础提示词
        return buildFallbackScenePrompt(sceneName, series.getStyleKeywords());
    }

    @Override
    public String generatePropPrompt(Long seriesId, String propName, Long episodeId) {
        log.info("生成道具提示词: seriesId={}, propName={}, episodeId={}", seriesId, propName, episodeId);

        Series series = seriesMapper.selectById(seriesId);
        if (series == null) {
            throw new RuntimeException("系列不存在: " + seriesId);
        }

        String scriptText = "";
        if (episodeId != null) {
            Episode episode = episodeMapper.selectById(episodeId);
            if (episode != null && episode.getScriptText() != null) {
                scriptText = episode.getScriptText();
                if (scriptText.length() > 2000) {
                    scriptText = scriptText.substring(0, 2000) + "...";
                }
            }
        }

        String userPrompt = buildPropUserPrompt(series, propName, scriptText);

        try {
            LLMResponse response = llmService.chat(PROP_SYSTEM_PROMPT, userPrompt);
            if (response != null && response.getContent() != null) {
                log.info("道具提示词生成成功: {}", response.getContent());
                return response.getContent().trim();
            }
        } catch (Exception e) {
            log.error("生成道具提示词失败", e);
        }

        // 降级：使用基础提示词
        return buildFallbackPropPrompt(propName, series.getStyleKeywords());
    }

    private String buildSceneUserPrompt(Series series, String sceneName, String scriptText) {
        StringBuilder sb = new StringBuilder();
        sb.append("系列名称：").append(series.getSeriesName()).append("\n");

        if (series.getOutline() != null && !series.getOutline().isEmpty()) {
            sb.append("剧本大纲：").append(series.getOutline()).append("\n");
        }
        if (series.getBackground() != null && !series.getBackground().isEmpty()) {
            sb.append("背景设定：").append(series.getBackground()).append("\n");
        }
        if (series.getStyleKeywords() != null && !series.getStyleKeywords().isEmpty()) {
            sb.append("系列风格：").append(series.getStyleKeywords()).append("\n");
        }
        if (scriptText != null && !scriptText.isEmpty()) {
            sb.append("当前剧集剧本：\n").append(scriptText).append("\n");
        }

        sb.append("\n请为以下场景生成图片提示词：\n");
        sb.append("场景名称：").append(sceneName).append("\n");
        sb.append("\n要求：\n");
        sb.append("1. 英文输出\n");
        sb.append("2. 只描述场景环境，包含建筑、家具、装饰等\n");
        sb.append("3. 包含光线和氛围描述\n");
        sb.append("4. 必须明确包含系列风格关键词，不允许改成真人、写实摄影或 live-action\n");
        sb.append("5. 强调高质量、细节丰富\n");
        sb.append("6. 绝对不能包含任何人物、角色、人像\n");
        sb.append("7. 提示词末尾必须包含：no people, no characters, empty scene, background only\n");

        return sb.toString();
    }

    private String buildPropUserPrompt(Series series, String propName, String scriptText) {
        StringBuilder sb = new StringBuilder();
        sb.append("系列名称：").append(series.getSeriesName()).append("\n");

        if (series.getOutline() != null && !series.getOutline().isEmpty()) {
            sb.append("剧本大纲：").append(series.getOutline()).append("\n");
        }
        if (series.getBackground() != null && !series.getBackground().isEmpty()) {
            sb.append("背景设定：").append(series.getBackground()).append("\n");
        }
        if (series.getStyleKeywords() != null && !series.getStyleKeywords().isEmpty()) {
            sb.append("系列风格：").append(series.getStyleKeywords()).append("\n");
        }
        if (scriptText != null && !scriptText.isEmpty()) {
            sb.append("当前剧集剧本：\n").append(scriptText).append("\n");
        }

        sb.append("\n请为以下道具生成图片提示词：\n");
        sb.append("道具名称：").append(propName).append("\n");
        sb.append("\n要求：\n");
        sb.append("1. 英文输出\n");
        sb.append("2. 产品图风格，独立单品居中展示\n");
        sb.append("3. 包含道具的细节描述\n");
        sb.append("4. 必须明确包含系列风格关键词，不允许改成真人、写实摄影或 live-action\n");
        sb.append("5. 强调高质量、细节丰富\n");
        sb.append("6. 正方形构图，居中展示\n");
        sb.append("7. 必须是独立单品，禁止出现手、人物、或其他物品\n");
        sb.append("8. 必须使用透明背景抠图：transparent background, isolated clean cutout, alpha channel style\n");
        sb.append("9. 不要使用白底、纯色背景、场景背景、桌面、房间、渐变背景或纹理背景\n");
        sb.append("10. 提示词末尾必须包含：no hands, no hands holding, no people, standalone prop\n");

        return sb.toString();
    }

    private String buildFallbackScenePrompt(String sceneName, String styleKeywords) {
        StringBuilder sb = new StringBuilder();
        sb.append("Scene background, ");
        sb.append(sceneName != null ? sceneName : "");
        sb.append(", detailed environment, cinematic lighting, ");
        if (styleKeywords != null && !styleKeywords.isEmpty()) {
            sb.append(styleKeywords).append(", ");
        }
        sb.append("high quality, professional, 4K, no people, no characters, no figures, empty scene, background only");
        return sb.toString();
    }

    private String buildFallbackPropPrompt(String propName, String styleKeywords) {
        StringBuilder sb = new StringBuilder();
        sb.append("Product photography, isolated object, ");
        sb.append(propName != null ? propName : "");
        sb.append(", detailed, ");
        if (styleKeywords != null && !styleKeywords.isEmpty()) {
            sb.append(styleKeywords).append(", ");
        }
        sb.append("transparent background, isolated clean cutout, alpha channel style, no background, no backdrop, no hands, no hands holding, no people, standalone prop, high quality, centered");
        return sb.toString();
    }
}
