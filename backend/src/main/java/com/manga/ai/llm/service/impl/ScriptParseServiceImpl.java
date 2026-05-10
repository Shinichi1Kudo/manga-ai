package com.manga.ai.llm.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.manga.ai.llm.dto.LLMResponse;
import com.manga.ai.llm.dto.ScriptParseResult;
import com.manga.ai.llm.service.DoubaoLLMService;
import com.manga.ai.llm.service.ScriptParseService;
import com.manga.ai.role.entity.Role;
import com.manga.ai.role.mapper.RoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 剧本解析服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptParseServiceImpl implements ScriptParseService {

    private final DoubaoLLMService llmService;
    private final RoleMapper roleMapper;

    @Override
    public ScriptParseResult parseScript(String scriptText, Long seriesId) {
        log.info("开始解析剧本: seriesId={}, scriptLength={}", seriesId, scriptText.length());

        ScriptParseResult result = new ScriptParseResult();

        try {
            // 获取已知角色列表
            List<String> knownCharacters = getKnownCharacters(seriesId);
            log.info("已知角色列表: {}", knownCharacters);

            // 构建系统提示词
            String systemPrompt = buildSystemPrompt(knownCharacters);

            // 构建用户提示词
            String userPrompt = buildUserPrompt(scriptText);

            // 调用LLM
            LLMResponse response = llmService.chat(systemPrompt, userPrompt);

            if ("success".equals(response.getStatus())) {
                // 解析LLM返回的JSON
                result = parseLLMResponse(response.getContent());
                result.setStatus("success");
                log.info("剧本解析完成: shots={}, scenes={}, props={}",
                        result.getShots() != null ? result.getShots().size() : 0,
                        result.getScenes() != null ? result.getScenes().size() : 0,
                        result.getProps() != null ? result.getProps().size() : 0);
            } else {
                result.setStatus("failed");
                result.setErrorMessage(response.getErrorMessage());
                log.error("LLM调用失败: {}", response.getErrorMessage());
            }
        } catch (Exception e) {
            log.error("剧本解析异常", e);
            result.setStatus("failed");
            result.setErrorMessage(e.getMessage());
        }

        return result;
    }

    @Override
    public List<String> getKnownCharacters(Long seriesId) {
        LambdaQueryWrapper<Role> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Role::getSeriesId, seriesId)
                .select(Role::getRoleName);
        List<Role> roles = roleMapper.selectList(wrapper);
        return roles.stream()
                .map(Role::getRoleName)
                .collect(Collectors.toList());
    }

    @Override
    public ScriptParseResult parseAssetsOnly(String scriptText, Long seriesId) {
        log.info("开始解析资产（仅场景和道具）: seriesId={}, scriptLength={}", seriesId, scriptText.length());

        int maxRetries = 3;
        ScriptParseResult result = new ScriptParseResult();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            log.info("资产解析尝试 {}/{}", attempt, maxRetries);

            try {
                List<String> knownCharacters = getKnownCharacters(seriesId);
                String systemPrompt = buildAssetsOnlyPrompt(knownCharacters);
                String userPrompt = buildUserPrompt(scriptText);

                // 资产解析使用 glm-5-turbo 模型，避免与分镜解析抢占同一模型的并发限制
                LLMResponse response = llmService.chat(systemPrompt, userPrompt, "glm-5-turbo");

                if ("success".equals(response.getStatus())) {
                    result = parseLLMResponseAssetsOnly(response.getContent());

                    // 检查是否成功解析到资产
                    boolean hasScenes = result.getScenes() != null && !result.getScenes().isEmpty();
                    boolean hasProps = result.getProps() != null && !result.getProps().isEmpty();

                    if (hasScenes || hasProps) {
                        result.setStatus("success");
                        log.info("资产解析完成(glm-5-turbo): scenes={}, props={}, 尝试次数={}",
                                result.getScenes() != null ? result.getScenes().size() : 0,
                                result.getProps() != null ? result.getProps().size() : 0,
                                attempt);
                        return result;
                    } else {
                        log.warn("资产解析结果为空，准备重试 (尝试 {}/{})", attempt, maxRetries);
                    }
                } else {
                    log.warn("LLM调用失败: {}, 准备重试 (尝试 {}/{})", response.getErrorMessage(), attempt, maxRetries);
                    result.setErrorMessage(response.getErrorMessage());
                }
            } catch (Exception e) {
                log.error("资产解析异常 (尝试 {}/{}): {}", attempt, maxRetries, e.getMessage());
                result.setErrorMessage(e.getMessage());
            }

            // 如果不是最后一次尝试，等待一段时间再重试
            if (attempt < maxRetries) {
                try {
                    // 429限速时使用更长等待时间
                    long waitTime = result.getErrorMessage() != null
                            && result.getErrorMessage().contains("429") ? 5000 * attempt : 2000 * attempt;
                    log.info("等待 {}ms 后重试...", waitTime);
                    Thread.sleep(waitTime);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // 所有尝试都失败
        result.setStatus("failed");
        log.error("资产解析失败，已重试{}次", maxRetries);
        return result;
    }

    @Override
    public ScriptParseResult parseShots(String scriptText, Long seriesId, Map<String, Long> sceneCodeToIdMap) {
        log.info("开始解析分镜: seriesId={}, scriptLength={}", seriesId, scriptText.length());

        int maxRetries = 3;
        ScriptParseResult result = new ScriptParseResult();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            log.info("分镜解析尝试 {}/{}", attempt, maxRetries);

            try {
                List<String> knownCharacters = getKnownCharacters(seriesId);
                String systemPrompt = buildShotsOnlyPrompt(knownCharacters, sceneCodeToIdMap);
                String userPrompt = buildUserPrompt(scriptText);

                LLMResponse response = llmService.chat(systemPrompt, userPrompt);

                if ("success".equals(response.getStatus())) {
                    result = parseLLMResponseShotsOnly(response.getContent());

                    // 检查是否成功解析到分镜
                    if (result.getShots() != null && !result.getShots().isEmpty()) {
                        result.setStatus("success");
                        log.info("分镜解析完成: shots={}, 尝试次数={}", result.getShots().size(), attempt);
                        return result;
                    } else {
                        if (isJsonParseFailure(result.getErrorMessage())) {
                            log.warn("分镜JSON解析失败，立即重试请求模型 (尝试 {}/{}): {}", attempt, maxRetries, result.getErrorMessage());
                        } else {
                            log.warn("分镜解析结果为空，准备重试 (尝试 {}/{})", attempt, maxRetries);
                        }
                    }
                } else {
                    log.warn("LLM调用失败: {}, 准备重试 (尝试 {}/{})", response.getErrorMessage(), attempt, maxRetries);
                    result.setErrorMessage(response.getErrorMessage());
                }
            } catch (Exception e) {
                log.error("分镜解析异常 (尝试 {}/{}): {}", attempt, maxRetries, e.getMessage());
                result.setErrorMessage(e.getMessage());
            }

            // 如果不是最后一次尝试，等待一段时间再重试
            if (attempt < maxRetries) {
                try {
                    if (isJsonParseFailure(result.getErrorMessage())) {
                        continue;
                    }

                    // 429限速时使用更长等待时间
                    long waitTime = result.getErrorMessage() != null
                            && result.getErrorMessage().contains("429") ? 5000 * attempt : 2000 * attempt;
                    log.info("等待 {}ms 后重试...", waitTime);
                    Thread.sleep(waitTime);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // 所有尝试都失败
        result.setStatus("failed");
        log.error("分镜解析失败，已重试{}次", maxRetries);
        return result;
    }

    @Override
    public ScriptParseResult parseShots(String scriptText, Long seriesId, Map<String, Long> sceneCodeToIdMap, String parseMode) {
        log.info("开始解析分镜: seriesId={}, parseMode={}", seriesId, parseMode);

        int maxRetries = 3;
        ScriptParseResult result = new ScriptParseResult();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            log.info("分镜解析尝试 {}/{}", attempt, maxRetries);

            try {
                List<String> knownCharacters = getKnownCharacters(seriesId);

                // 根据模式选择不同的提示词
                String systemPrompt;
                if ("detailed".equals(parseMode)) {
                    systemPrompt = buildDetailedShotsPrompt(knownCharacters, sceneCodeToIdMap);
                } else {
                    systemPrompt = buildShotsOnlyPrompt(knownCharacters, sceneCodeToIdMap);
                }

                String userPrompt = buildUserPrompt(scriptText);

                LLMResponse response = llmService.chat(systemPrompt, userPrompt);

                if ("success".equals(response.getStatus())) {
                    result = parseLLMResponseShotsOnly(response.getContent());

                    if (result.getShots() != null && !result.getShots().isEmpty()) {
                        result.setStatus("success");
                        log.info("分镜解析完成: shots={}, parseMode={}, 尝试次数={}",
                                result.getShots().size(), parseMode, attempt);
                        return result;
                    } else {
                        if (isJsonParseFailure(result.getErrorMessage())) {
                            log.warn("分镜JSON解析失败，立即重试请求模型 (尝试 {}/{}): {}", attempt, maxRetries, result.getErrorMessage());
                        } else {
                            log.warn("分镜解析结果为空，准备重试 (尝试 {}/{})", attempt, maxRetries);
                        }
                    }
                } else {
                    log.warn("LLM调用失败: {}, 准备重试 (尝试 {}/{})", response.getErrorMessage(), attempt, maxRetries);
                    result.setErrorMessage(response.getErrorMessage());
                }
            } catch (Exception e) {
                log.error("分镜解析异常 (尝试 {}/{}): {}", attempt, maxRetries, e.getMessage());
                result.setErrorMessage(e.getMessage());
            }

            if (attempt < maxRetries) {
                try {
                    if (isJsonParseFailure(result.getErrorMessage())) {
                        continue;
                    }

                    // 429限速时使用更长等待时间
                    long waitTime = result.getErrorMessage() != null
                            && result.getErrorMessage().contains("429") ? 5000 * attempt : 2000 * attempt;
                    log.info("等待 {}ms 后重试...", waitTime);
                    Thread.sleep(waitTime);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        result.setStatus("failed");
        log.error("分镜解析失败，已重试{}次", maxRetries);
        return result;
    }

    /**
     * 构建详细版分镜解析提示词（适配 Seedance 2.0）
     * 遵循三段式结构、八大核心要素、运镜限制等规范
     */
    private String buildDetailedShotsPrompt(List<String> knownCharacters, Map<String, Long> sceneCodeToIdMap) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一位专业的电影导演和编剧，擅长将剧本转化为精细的分镜脚本。\n");
        prompt.append("你的任务是将剧本拆分为高质量的分镜，每个分镜都需要有电影感的视觉描述。\n\n");

        prompt.append("## 核心原则\n");
        prompt.append("### 每个分镜必须包含\n");
        prompt.append("1. **镜头标题**：格式为「镜头X｜镜头类型描述」，如「镜头1｜车内主观视角」\n");
        prompt.append("2. **详细视觉描述**：包含场景环境、人物动作、表情细节、光影氛围\n");
        prompt.append("3. **角色状态**：眼神、表情、肢体语言的精确描写\n");
        prompt.append("4. **环境氛围**：光线、色调、动态元素（雨、烟、光斑等）\n");
        prompt.append("5. **声音元素**：环境音、角色台词、特殊音效\n\n");

        prompt.append("### 分镜拆分原则（非常重要）\n");
        prompt.append("- **按场景拆分**：剧本中的每个场景（如【场景一】【场景二】）必须拆分为独立的分镜\n");
        prompt.append("- **按动作拆分**：同一场景内，角色的不同动作或情绪变化应拆分为不同分镜\n");
        prompt.append("- **按对白拆分**：重要的对白和台词应单独成镜，不要合并\n");
        prompt.append("- **时长范围**：单个镜头 5-12 秒，重要对白场景可延长至 15 秒\n");
        prompt.append("- **保留完整性**：每个分镜应承载完整的叙事单元\n\n");

        prompt.append("### 剧情描述要求（非常重要）\n");
        prompt.append("剧情描述必须**完整保留原始剧本内容**，包含：\n");
        prompt.append("- **角色的完整台词**：不要简化或省略对白，用引号标注\n");
        prompt.append("- **内心独白**：保留角色的内心想法（如：沈清棠（内心独白）：\"...\"）\n");
        prompt.append("- **角色的动作和表情**：详细描述动作细节\n");
        prompt.append("- **环境变化、光线、氛围**：场景的视觉元素\n");
        prompt.append("- **场景转换和过渡**：明确标注场景变化\n\n");
        prompt.append("**示例格式**：\n");
        prompt.append("萧凛冷笑一声，上前一步，用剑鞘挑起红盖头。他声音低沉危险地说：\"丞相府好大的手笔，竟舍得把嫡女送来我这修罗场。\" 他的眼神冰冷，剑尖直指床上那抹红色身影。\n\n");
        prompt.append("**错误示例**（太简略）：\n");
        prompt.append("萧凛挑起盖头质问沈清棠。\n\n");

        prompt.append("## 镜头类型与运镜\n");
        prompt.append("### 景别\n");
        prompt.append("- 远景：展示环境全貌，人物渺小，适合开场/环境交代\n");
        prompt.append("- 全景：展示人物全身及周边环境，适合动作场景\n");
        prompt.append("- 中景：展示人物膝盖以上，适合对话/互动\n");
        prompt.append("- 近景：展示人物胸部以上，适合情绪表达\n");
        prompt.append("- 特写：展示人物面部或物体细节，适合强调\n");
        prompt.append("- 大特写：极近距离展示细节，适合强烈情绪冲击\n\n");

        prompt.append("### 特殊镜头技巧\n");
        prompt.append("- 主观视角(POV)：从角色眼睛看到的画面\n");
        prompt.append("- 低角度：从低处向上拍摄，突出威严或压迫感\n");
        prompt.append("- 交叉剪辑：在不同场景间快速切换，营造紧张感\n");
        prompt.append("- 手持感：模拟颠簸中的拍摄效果，增加临场感\n");
        prompt.append("- 晃动镜头：表现紧张、混乱或冲击感\n\n");

        prompt.append("### 镜头运动（每个分镜只能选1种）\n");
        prompt.append("- 固定：镜头不动，适合对话/静止场景\n");
        prompt.append("- 推镜头：向前推进，靠近主体，突出重点\n");
        prompt.append("- 拉镜头：向后拉远，远离主体，展示环境\n");
        prompt.append("- 摇镜头：原地转动，展示全景或跟随视线\n");
        prompt.append("- 跟镜头：跟随移动的主体移动\n\n");

        prompt.append("## 描述风格要求\n");
        prompt.append("### 必须包含的细节\n");
        prompt.append("- **眼神描写**：目光方向、焦点、情感传达\n");
        prompt.append("  例：\"目光坚定地注视前方\"、\"眼神躲闪不敢直视\"、\"眼眶微红含泪凝视\"\n");
        prompt.append("- **表情细节**：面部微表情变化，避免僵硬\n");
        prompt.append("  例：\"嘴角微微上扬\"、\"眉心紧锁\"、\"下颚紧绷\"、\"嘴唇颤抖\"\n");
        prompt.append("- **肢体语言**：动作力度、身体状态\n");
        prompt.append("  例：\"手臂肌肉绷紧\"、\"双手颤抖\"、\"身体微微前倾\"\n");
        prompt.append("- **动态元素**：环境中的运动物体\n");
        prompt.append("  例：\"雨刮器疯狂摆动\"、\"血迹与雨水混成一片\"、\"光斑在脸上跳跃\"\n\n");

        prompt.append("## 时长说明\n");
        prompt.append("- duration: 该分镜的时长（秒），范围 5-15 秒\n");
        prompt.append("- 建议：普通分镜 6-10 秒，重要对白场景 10-15 秒，转场或特写 5-6 秒\n\n");

        if (!knownCharacters.isEmpty()) {
            prompt.append("## 已知角色列表\n");
            prompt.append("以下角色已存在于本系列中，**必须使用这些角色名称**：\n");
            for (String name : knownCharacters) {
                prompt.append("- ").append(name).append("\n");
            }
            prompt.append("\n**重要**: 分镜中的角色必须从上述列表中选择，不要创建新角色。\n\n");
        }

        if (!sceneCodeToIdMap.isEmpty()) {
            prompt.append("## 可用场景\n");
            prompt.append("请使用以下场景编码：\n");
            for (String sceneCode : sceneCodeToIdMap.keySet()) {
                prompt.append("- ").append(sceneCode).append("\n");
            }
            prompt.append("\n");
        }

        prompt.append("## 输出格式\n");
        prompt.append("请严格按照以下JSON格式输出，不要添加任何其他文字说明：\n");
        prompt.append("JSON字符串值内部如需出现英文双引号，必须写成 \\\"，不能直接输出未转义的英文双引号；也可优先使用中文引号“”。\n");
        prompt.append("```json\n");
        prompt.append("{\n");
        prompt.append("  \"shots\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"shotNumber\": 1,\n");
        prompt.append("      \"sceneCode\": \"SC01\",\n");
        prompt.append("      \"sceneName\": \"场景名称\",\n");
        prompt.append("      \"duration\": 10,\n");
        prompt.append("      \"shotSize\": \"中景\",\n");
        prompt.append("      \"cameraAngle\": \"平视\",\n");
        prompt.append("      \"cameraMovement\": \"推镜头\",\n");
        prompt.append("      \"shotType\": \"中景·角色名动作描述\",\n");
        prompt.append("      \"description\": \"完整的剧情描述，包含角色台词、内心独白、动作、表情、环境变化等；台词建议使用中文引号“”，如使用英文双引号必须转义为\\\\\\\"\",\n");
        prompt.append("      \"soundEffect\": \"音效描述，如：引擎轰鸣声、雨声、角色喊叫等\",\n");
        prompt.append("      \"characters\": [\n");
        prompt.append("        {\n");
        prompt.append("          \"roleName\": \"角色名称\",\n");
        prompt.append("          \"action\": \"角色动作描述\",\n");
        prompt.append("          \"expression\": \"表情描述（含微表情细节）\",\n");
        prompt.append("          \"eyeExpression\": \"眼神描述（方向、焦点、情感）\",\n");
        prompt.append("          \"position\": \"画面位置\",\n");
        prompt.append("          \"clothingId\": 1\n");
        prompt.append("        }\n");
        prompt.append("      ],\n");
        prompt.append("      \"props\": [\n");
        prompt.append("        {\n");
        prompt.append("          \"propName\": \"道具名称\",\n");
        prompt.append("          \"position\": \"道具位置\",\n");
        prompt.append("          \"interaction\": \"与角色的交互方式\"\n");
        prompt.append("        }\n");
        prompt.append("      ]\n");
        prompt.append("    }\n");
        prompt.append("  ]\n");
        prompt.append("}\n");
        prompt.append("```\n\n");

        prompt.append("## 关键注意事项\n");
        prompt.append("1. **保留所有对白**：角色的台词必须完整保留，不要简化或省略\n");
        prompt.append("2. **保留内心独白**：角色的内心想法也要保留\n");
        prompt.append("3. **按场景拆分**：剧本中的每个【场景X】都应拆分为独立分镜\n");
        prompt.append("4. **描述要具体**：使用具体的视觉细节，避免模糊描述\n");
        prompt.append("5. **情感要到位**：每个角色必须有清晰的情感状态和表情描写\n");
        prompt.append("6. **动作要有力**：动作描述要有力度感和方向感\n");
        prompt.append("7. **环境要生动**：加入动态元素让画面活起来\n");
        prompt.append("8. **保持连贯性**：相邻分镜之间要有逻辑衔接\n");

        return prompt.toString();
    }

    /**
     * 构建只解析资产的系统提示词
     */
    private String buildAssetsOnlyPrompt(List<String> knownCharacters) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个专业的剧本分析助手。\n\n");
        prompt.append("## 任务说明\n");
        prompt.append("分析用户提供的剧本内容，提取场景和道具信息。\n");
        prompt.append("注意：只需要输出场景和道具，不需要生成分镜。\n\n");

        if (!knownCharacters.isEmpty()) {
            prompt.append("## 已知角色列表\n");
            prompt.append("以下角色已存在于本系列中：\n");
            for (String name : knownCharacters) {
                prompt.append("- ").append(name).append("\n");
            }
            prompt.append("\n");
        }

        prompt.append("## 输出格式\n");
        prompt.append("请严格按照以下JSON格式输出，不要添加任何其他文字说明：\n");
        prompt.append("JSON字符串值内部如需出现英文双引号，必须写成 \\\"，不能直接输出未转义的英文双引号；也可优先使用中文引号“”。\n");
        prompt.append("```json\n");
        prompt.append("{\n");
        prompt.append("  \"scenes\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"sceneName\": \"场景名称\",\n");
        prompt.append("      \"sceneCode\": \"SC01\",\n");
        prompt.append("      \"description\": \"场景描述\",\n");
        prompt.append("      \"locationType\": \"室内/室外\",\n");
        prompt.append("      \"timeOfDay\": \"白天/夜晚/黄昏\",\n");
        prompt.append("      \"weather\": \"晴天/雨天/阴天\"\n");
        prompt.append("    }\n");
        prompt.append("  ],\n");
        prompt.append("  \"props\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"propName\": \"道具名称\",\n");
        prompt.append("      \"propCode\": \"PROP01\",\n");
        prompt.append("      \"description\": \"道具描述\",\n");
        prompt.append("      \"propType\": \"道具类型\",\n");
        prompt.append("      \"color\": \"颜色\"\n");
        prompt.append("    }\n");
        prompt.append("  ]\n");
        prompt.append("}\n");
        prompt.append("```\n");

        return prompt.toString();
    }

    /**
     * 构建只解析分镜的系统提示词
     */
    private String buildShotsOnlyPrompt(List<String> knownCharacters, Map<String, Long> sceneCodeToIdMap) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个专业的剧本分析助手，擅长将剧本拆分为分镜脚本。\n\n");
        prompt.append("## 任务说明\n");
        prompt.append("1. 将剧本拆分为多个分镜，每个分镜时长1-15秒\n");
        prompt.append("2. 为每个分镜指定镜头类型（景别+运动方式）\n");
        prompt.append("3. 为每个分镜编写详细的剧情描述\n");
        prompt.append("4. 根据剧情需要，添加音效描述\n");
        prompt.append("5. 为每个分镜指定场景名称\n\n");

        prompt.append("## 镜头类型说明\n");
        prompt.append("- 景别：远景、全景、中景、近景、特写、大特写\n");
        prompt.append("- 运动：推镜头、拉镜头、摇镜头、移镜头、跟镜头\n");
        prompt.append("- 示例：中景、全景+推镜头、特写+拉镜头\n\n");

        prompt.append("## 时间说明\n");
        prompt.append("- duration: 该分镜的时长（秒），范围 4-15 秒\n");
        prompt.append("- 建议：普通分镜 8-10 秒，重要场景 10-15 秒，转场或特写 4-6 秒\n\n");

        prompt.append("## 剧情描述要求（非常重要）\n");
        prompt.append("剧情描述必须**完整保留原始剧本内容**，包含：\n");
        prompt.append("- 角色的完整台词（不要简化或省略）\n");
        prompt.append("- 角色的动作和表情\n");
        prompt.append("- 环境变化、光线、氛围等细节\n");
        prompt.append("- 场景转换和过渡\n\n");
        prompt.append("**示例格式**：\n");
        prompt.append("小明站在客厅里，兴奋地挥舞手臂，大声说：\"今天终于要开学了！\" 他快速拿起书包，冲向门口。\n\n");
        prompt.append("**错误示例**（太简略）：\n");
        prompt.append("小明说今天要开学了。\n\n");

        prompt.append("## 场景说明\n");
        prompt.append("- sceneName: 场景名称，描述该分镜发生的环境\n\n");

        if (!knownCharacters.isEmpty()) {
            prompt.append("## 已知角色列表\n");
            prompt.append("以下角色已存在于本系列中，请使用这些角色名称：\n");
            for (String name : knownCharacters) {
                prompt.append("- ").append(name).append("\n");
            }
            prompt.append("\n**重要**: 分镜中的角色必须从上述列表中选择。\n\n");
        }

        // 列出可用的场景
        if (!sceneCodeToIdMap.isEmpty()) {
            prompt.append("## 可用场景\n");
            prompt.append("请使用以下场景编码：\n");
            for (String sceneCode : sceneCodeToIdMap.keySet()) {
                prompt.append("- ").append(sceneCode).append("\n");
            }
            prompt.append("\n");
        }

        prompt.append("## 输出格式\n");
        prompt.append("请严格按照以下JSON格式输出，不要添加任何其他文字说明：\n");
        prompt.append("JSON字符串值内部如需出现英文双引号，必须写成 \\\"，不能直接输出未转义的英文双引号；也可优先使用中文引号“”。\n");
        prompt.append("```json\n");
        prompt.append("{\n");
        prompt.append("  \"shots\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"shotNumber\": 1,\n");
        prompt.append("      \"sceneCode\": \"SC01\",\n");
        prompt.append("      \"sceneName\": \"场景名称\",\n");
        prompt.append("      \"duration\": 10,\n");
        prompt.append("      \"shotType\": \"中景\",\n");
        prompt.append("      \"description\": \"完整的剧情描述，包含角色台词、动作、表情、环境变化等；台词建议使用中文引号“”，如使用英文双引号必须转义为\\\\\\\"\",\n");
        prompt.append("      \"soundEffect\": \"音效描述\",\n");
        prompt.append("      \"characters\": [\n");
        prompt.append("        {\n");
        prompt.append("          \"roleName\": \"角色名\",\n");
        prompt.append("          \"action\": \"动作描述\",\n");
        prompt.append("          \"expression\": \"表情\",\n");
        prompt.append("          \"clothingId\": 1\n");
        prompt.append("        }\n");
        prompt.append("      ],\n");
        prompt.append("      \"props\": [\n");
        prompt.append("        {\n");
        prompt.append("          \"propName\": \"道具名\",\n");
        prompt.append("          \"position\": \"位置描述\"\n");
        prompt.append("        }\n");
        prompt.append("      ]\n");
        prompt.append("    }\n");
        prompt.append("  ]\n");
        prompt.append("}\n");
        prompt.append("```\n");

        return prompt.toString();
    }

    /**
     * 只解析资产（场景和道具）的LLM响应
     */
    private ScriptParseResult parseLLMResponseAssetsOnly(String content) {
        ScriptParseResult result = new ScriptParseResult();

        try {
            String jsonStr = extractJson(content);
            JSONObject json = JSON.parseObject(jsonStr);

            // 解析场景
            JSONArray scenesArray = json.getJSONArray("scenes");
            if (scenesArray != null) {
                List<ScriptParseResult.SceneInfo> scenes = new ArrayList<>();
                for (int i = 0; i < scenesArray.size(); i++) {
                    JSONObject sceneObj = scenesArray.getJSONObject(i);
                    ScriptParseResult.SceneInfo scene = new ScriptParseResult.SceneInfo();
                    scene.setSceneName(sceneObj.getString("sceneName"));
                    scene.setSceneCode(sceneObj.getString("sceneCode"));
                    scene.setDescription(sceneObj.getString("description"));
                    scene.setLocationType(sceneObj.getString("locationType"));
                    scene.setTimeOfDay(sceneObj.getString("timeOfDay"));
                    scene.setWeather(sceneObj.getString("weather"));
                    scenes.add(scene);
                }
                result.setScenes(scenes);
            }

            // 解析道具
            JSONArray propsArray = json.getJSONArray("props");
            if (propsArray != null) {
                List<ScriptParseResult.PropInfo> props = new ArrayList<>();
                for (int i = 0; i < propsArray.size(); i++) {
                    JSONObject propObj = propsArray.getJSONObject(i);
                    ScriptParseResult.PropInfo prop = new ScriptParseResult.PropInfo();
                    prop.setPropName(propObj.getString("propName"));
                    prop.setPropCode(propObj.getString("propCode"));
                    prop.setDescription(propObj.getString("description"));
                    prop.setPropType(propObj.getString("propType"));
                    prop.setColor(propObj.getString("color"));
                    props.add(prop);
                }
                result.setProps(props);
            }

        } catch (Exception e) {
            log.error("解析资产响应失败", e);
        }

        return result;
    }

    /**
     * 只解析分镜的LLM响应
     */
    private ScriptParseResult parseLLMResponseShotsOnly(String content) {
        ScriptParseResult result = new ScriptParseResult();

        if (content == null || content.isEmpty()) {
            log.error("LLM响应内容为空");
            result.setStatus("failed");
            result.setErrorMessage("LLM响应内容为空");
            return result;
        }

        try {
            log.info("原始响应长度: {}, 前100字符: {}", content.length(), content.substring(0, Math.min(100, content.length())));

            String jsonStr = extractJson(content);
            log.info("提取的JSON长度: {}, 前50字符: {}", jsonStr.length(),
                jsonStr.length() > 50 ? jsonStr.substring(0, 50) : jsonStr);

            if (jsonStr.isEmpty()) {
                log.error("提取的JSON为空，原始内容: {}", content.substring(0, Math.min(500, content.length())));
                result.setStatus("failed");
                result.setErrorMessage("无法从LLM响应中提取JSON");
                return result;
            }

            // 清理无效的转义字符
            jsonStr = sanitizeJsonString(jsonStr);
            log.info("清理后JSON长度: {}", jsonStr.length());

            // 记录前200字符用于调试
            log.info("JSON内容(前200字符): {}", jsonStr.substring(0, Math.min(200, jsonStr.length())));

            JSONObject json = JSON.parseObject(jsonStr);

            // 解析分镜
            JSONArray shotsArray = json.getJSONArray("shots");
            if (shotsArray != null) {
                List<ScriptParseResult.ShotInfo> shots = new ArrayList<>();
                for (int i = 0; i < shotsArray.size(); i++) {
                    JSONObject shotObj = shotsArray.getJSONObject(i);
                    ScriptParseResult.ShotInfo shot = parseShotInfo(shotObj);
                    shots.add(shot);
                }
                result.setShots(shots);
                log.info("成功解析{}个分镜", shots.size());
            } else {
                log.warn("JSON中没有shots数组，JSON keys: {}", json.keySet());
            }

        } catch (Exception e) {
            log.error("解析分镜响应失败", e);
            // 尝试记录原始内容以便调试
            log.error("原始LLM响应内容(前500字符): {}", content.substring(0, Math.min(500, content.length())));
            result.setStatus("failed");
            result.setErrorMessage("JSON解析失败: " + e.getMessage());
        }

        return result;
    }

    private boolean isJsonParseFailure(String errorMessage) {
        if (errorMessage == null) {
            return false;
        }
        return errorMessage.contains("JSON解析失败")
                || errorMessage.contains("无法从LLM响应中提取JSON")
                || errorMessage.contains("提取的JSON为空")
                || errorMessage.contains("syntax error")
                || errorMessage.contains("illegal input");
    }

    /**
     * 从截断的JSON中提取已完成的shots数组
     */
    private String extractPartialShots(String content) {
        try {
            String jsonStr = extractJson(content);
            jsonStr = sanitizeJsonString(jsonStr);

            // 找到shots数组
            int shotsStart = jsonStr.indexOf("\"shots\"");
            if (shotsStart < 0) return "";

            int arrayStart = jsonStr.indexOf("[", shotsStart);
            if (arrayStart < 0) return "";

            // 找到最后一个完整的shot对象
            int lastCompleteShot = -1;
            int braceCount = 0;
            boolean inString = false;
            boolean escaped = false;

            for (int i = arrayStart; i < jsonStr.length(); i++) {
                char c = jsonStr.charAt(i);

                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    inString = !inString;
                    continue;
                }
                if (inString) continue;

                if (c == '{') {
                    braceCount++;
                } else if (c == '}') {
                    braceCount--;
                    if (braceCount == 0) {
                        lastCompleteShot = i;
                    }
                }
            }

            if (lastCompleteShot > arrayStart) {
                String partial = jsonStr.substring(0, lastCompleteShot + 1) + "]}";
                return partial;
            }
        } catch (Exception e) {
            log.warn("提取部分JSON失败: {}", e.getMessage());
        }
        return "";
    }

    /**
     * 清理JSON字符串中的无效转义字符
     */
    private String sanitizeJsonString(String jsonStr) {
        // 移除控制字符（除了\n, \r, \t）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < jsonStr.length(); i++) {
            char c = jsonStr.charAt(i);
            if (c < 32 && c != '\n' && c != '\r' && c != '\t') {
                continue; // 跳过控制字符
            }
            sb.append(c);
        }
        jsonStr = sb.toString();

        // 处理字符串末尾不完整的转义序列（如单独的反斜杠）
        if (jsonStr.endsWith("\\")) {
            jsonStr = jsonStr.substring(0, jsonStr.length() - 1);
            log.debug("移除末尾不完整的转义序列");
        }

        // 修复未转义的反斜杠（在字符串值内的反斜杠需要转义）
        // 先简单处理：替换 \" 为临时占位符，然后修复 \，再恢复
        jsonStr = jsonStr.replace("\\\"", "\u0000ESCQUOTE\u0000");
        // 处理其他已转义的字符
        jsonStr = jsonStr.replace("\\n", "\u0000ESCN\u0000");
        jsonStr = jsonStr.replace("\\r", "\u0000ESCR\u0000");
        jsonStr = jsonStr.replace("\\t", "\u0000ESCT\u0000");
        jsonStr = jsonStr.replace("\\\\", "\u0000ESCBSLASH\u0000");

        // 现在任何剩余的 \ 都需要被移除或转义
        // 移除不完整的转义序列
        jsonStr = jsonStr.replaceAll("\\\\([^\"nrtu\\\\])", "$1");
        // 处理末尾的单独反斜杠
        if (jsonStr.endsWith("\\")) {
            jsonStr = jsonStr.substring(0, jsonStr.length() - 1);
        }

        // 恢复已转义的字符
        jsonStr = jsonStr.replace("\u0000ESCQUOTE\u0000", "\\\"");
        jsonStr = jsonStr.replace("\u0000ESCN\u0000", "\\n");
        jsonStr = jsonStr.replace("\u0000ESCR\u0000", "\\r");
        jsonStr = jsonStr.replace("\u0000ESCT\u0000", "\\t");
        jsonStr = jsonStr.replace("\u0000ESCBSLASH\u0000", "\\\\");

        return jsonStr;
    }

    /**
     * 提取JSON字符串
     */
    private String extractJson(String content) {
        if (content == null || content.isEmpty()) {
            log.warn("extractJson: 内容为空");
            return "";
        }

        log.debug("extractJson: 原始内容长度={}, 前50字符={}", content.length(),
            content.substring(0, Math.min(50, content.length())));

        String jsonStr = content.trim();

        // 尝试多种提取方式
        // 方式1: 查找 ```json ... ```
        if (jsonStr.contains("```json")) {
            int startIdx = jsonStr.indexOf("```json") + 7;
            jsonStr = jsonStr.substring(startIdx);
            int endIdx = jsonStr.indexOf("```");
            if (endIdx > 0) {
                jsonStr = jsonStr.substring(0, endIdx);
            }
            jsonStr = jsonStr.trim();
            log.debug("extractJson: 使用```json提取，结果长度={}", jsonStr.length());
        }
        // 方式2: 查找 ``` ... ``` (不含json标记)
        else if (jsonStr.contains("```")) {
            int startIdx = jsonStr.indexOf("```") + 3;
            jsonStr = jsonStr.substring(startIdx);
            // 跳过可能的换行符
            if (jsonStr.startsWith("\n") || jsonStr.startsWith("\r\n")) {
                jsonStr = jsonStr.replaceFirst("^[\\r\\n]+", "");
            }
            int endIdx = jsonStr.indexOf("```");
            if (endIdx > 0) {
                jsonStr = jsonStr.substring(0, endIdx);
            }
            jsonStr = jsonStr.trim();
            log.debug("extractJson: 使用```提取，结果长度={}", jsonStr.length());
        }

        // 方式3: 尝试直接查找JSON对象 {...}
        if (jsonStr.isEmpty() || !jsonStr.startsWith("{")) {
            int braceStart = content.indexOf('{');
            if (braceStart >= 0) {
                jsonStr = content.substring(braceStart);
                // 找到最后一个}
                int braceEnd = jsonStr.lastIndexOf('}');
                if (braceEnd > 0) {
                    jsonStr = jsonStr.substring(0, braceEnd + 1);
                }
                log.debug("extractJson: 使用{}定位提取，结果长度={}", jsonStr.length());
            }
        }

        // 最终检查：确保以 { 开头
        jsonStr = jsonStr.trim();
        if (!jsonStr.startsWith("{") && jsonStr.contains("{")) {
            int idx = jsonStr.indexOf("{");
            jsonStr = jsonStr.substring(idx);
        }

        log.debug("extractJson: 最终结果长度={}, 前100字符={}", jsonStr.length(),
            jsonStr.length() > 100 ? jsonStr.substring(0, 100) : jsonStr);

        return jsonStr;
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt(List<String> knownCharacters) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个专业的剧本分析助手，擅长将剧本拆分为分镜脚本。\n\n");
        prompt.append("## 任务说明\n");
        prompt.append("1. 分析用户提供的剧本内容\n");
        prompt.append("2. 将剧本拆分为多个分镜（每个分镜时长不超过15秒）\n");
        prompt.append("3. 提取每个分镜中的场景、角色、道具信息\n");
        prompt.append("4. 指定镜头类型（景别+运动）\n");
        prompt.append("5. 根据剧情需要，可选地添加音效描述\n\n");
        prompt.append("## 镜头类型说明\n");
        prompt.append("- 景别：远景、全景、中景、近景、特写、大特写\n");
        prompt.append("- 运动：推镜头、拉镜头、摇镜头、移镜头、跟镜头\n");
        prompt.append("- 示例：中景、全景+推镜头、特写+拉镜头\n\n");

        if (!knownCharacters.isEmpty()) {
            prompt.append("## 已知角色列表\n");
            prompt.append("以下角色已存在于本系列中，请使用这些角色名称：\n");
            for (String name : knownCharacters) {
                prompt.append("- ").append(name).append("\n");
            }
            prompt.append("\n**重要**: 分镜中的角色必须从上述列表中选择，不要创建新角色。\n\n");
        }

        prompt.append("## 输出格式\n");
        prompt.append("请严格按照以下JSON格式输出，不要添加任何其他文字说明：\n");
        prompt.append("```json\n");
        prompt.append("{\n");
        prompt.append("  \"scenes\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"sceneName\": \"场景名称\",\n");
        prompt.append("      \"sceneCode\": \"SC01\",\n");
        prompt.append("      \"description\": \"场景描述\",\n");
        prompt.append("      \"locationType\": \"室内/室外\",\n");
        prompt.append("      \"timeOfDay\": \"白天/夜晚/黄昏\",\n");
        prompt.append("      \"weather\": \"晴天/雨天/阴天\"\n");
        prompt.append("    }\n");
        prompt.append("  ],\n");
        prompt.append("  \"props\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"propName\": \"道具名称\",\n");
        prompt.append("      \"propCode\": \"PROP01\",\n");
        prompt.append("      \"description\": \"道具描述\",\n");
        prompt.append("      \"propType\": \"道具类型\",\n");
        prompt.append("      \"color\": \"颜色\"\n");
        prompt.append("    }\n");
        prompt.append("  ],\n");
        prompt.append("  \"shots\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"shotNumber\": 1,\n");
        prompt.append("      \"sceneCode\": \"SC01\",\n");
        prompt.append("      \"duration\": 10,\n");
        prompt.append("      \"shotType\": \"中景\",\n");
        prompt.append("      \"description\": \"分镜剧情描述\",\n");
        prompt.append("      \"soundEffect\": \"音效描述（可选，根据剧情需要添加）\",\n");
        prompt.append("      \"characters\": [\n");
        prompt.append("        {\n");
        prompt.append("          \"roleName\": \"角色名\",\n");
        prompt.append("          \"action\": \"动作描述\",\n");
        prompt.append("          \"expression\": \"表情\",\n");
        prompt.append("          \"clothingId\": 1\n");
        prompt.append("        }\n");
        prompt.append("      ],\n");
        prompt.append("      \"props\": [\n");
        prompt.append("        {\n");
        prompt.append("          \"propName\": \"道具名\",\n");
        prompt.append("          \"position\": \"位置描述\"\n");
        prompt.append("        }\n");
        prompt.append("      ]\n");
        prompt.append("    }\n");
        prompt.append("  ]\n");
        prompt.append("}\n");
        prompt.append("```\n");

        return prompt.toString();
    }

    /**
     * 构建用户提示词
     */
    private String buildUserPrompt(String scriptText) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请分析以下剧本内容，将其拆分为分镜脚本：\n\n");
        prompt.append("---\n");
        prompt.append(scriptText);
        prompt.append("\n---\n\n");
        prompt.append("请按JSON格式输出分析结果：");
        return prompt.toString();
    }

    /**
     * 解析LLM返回的JSON
     */
    private ScriptParseResult parseLLMResponse(String content) {
        ScriptParseResult result = new ScriptParseResult();

        try {
            // 提取JSON部分（去除可能的markdown代码块标记）
            String jsonStr = content;

            if (content.contains("```json")) {
                int startIdx = content.indexOf("```json") + 7;
                jsonStr = content.substring(startIdx);
                // 查找结束标记
                int endIdx = jsonStr.indexOf("```");
                if (endIdx > 0) {
                    jsonStr = jsonStr.substring(0, endIdx);
                } else {
                    // 没有结束标记，尝试找到JSON对象的结束
                    log.warn("LLM响应没有找到JSON结束标记，尝试直接解析");
                }
            } else if (content.contains("```")) {
                int startIdx = content.indexOf("```") + 3;
                jsonStr = content.substring(startIdx);
                int endIdx = jsonStr.indexOf("```");
                if (endIdx > 0) {
                    jsonStr = jsonStr.substring(0, endIdx);
                }
            }
            jsonStr = jsonStr.trim();

            log.debug("准备解析的JSON内容长度: {}, 前200字符: {}", jsonStr.length(),
                    jsonStr.length() > 200 ? jsonStr.substring(0, 200) : jsonStr);

            JSONObject json = JSON.parseObject(jsonStr);

            // 解析场景
            JSONArray scenesArray = json.getJSONArray("scenes");
            if (scenesArray != null) {
                List<ScriptParseResult.SceneInfo> scenes = new ArrayList<>();
                for (int i = 0; i < scenesArray.size(); i++) {
                    JSONObject sceneObj = scenesArray.getJSONObject(i);
                    ScriptParseResult.SceneInfo scene = new ScriptParseResult.SceneInfo();
                    scene.setSceneName(sceneObj.getString("sceneName"));
                    scene.setSceneCode(sceneObj.getString("sceneCode"));
                    scene.setDescription(sceneObj.getString("description"));
                    scene.setLocationType(sceneObj.getString("locationType"));
                    scene.setTimeOfDay(sceneObj.getString("timeOfDay"));
                    scene.setWeather(sceneObj.getString("weather"));
                    scenes.add(scene);
                }
                result.setScenes(scenes);
            }

            // 解析道具
            JSONArray propsArray = json.getJSONArray("props");
            if (propsArray != null) {
                List<ScriptParseResult.PropInfo> props = new ArrayList<>();
                for (int i = 0; i < propsArray.size(); i++) {
                    JSONObject propObj = propsArray.getJSONObject(i);
                    ScriptParseResult.PropInfo prop = new ScriptParseResult.PropInfo();
                    prop.setPropName(propObj.getString("propName"));
                    prop.setPropCode(propObj.getString("propCode"));
                    prop.setDescription(propObj.getString("description"));
                    prop.setPropType(propObj.getString("propType"));
                    prop.setColor(propObj.getString("color"));
                    props.add(prop);
                }
                result.setProps(props);
            }

            // 解析分镜
            JSONArray shotsArray = json.getJSONArray("shots");
            if (shotsArray != null) {
                List<ScriptParseResult.ShotInfo> shots = new ArrayList<>();
                for (int i = 0; i < shotsArray.size(); i++) {
                    JSONObject shotObj = shotsArray.getJSONObject(i);
                    ScriptParseResult.ShotInfo shot = parseShotInfo(shotObj);
                    shots.add(shot);
                }
                result.setShots(shots);
            }

        } catch (Exception e) {
            log.error("解析LLM响应失败: content长度={}, 前500字符={}", content.length(),
                    content.length() > 500 ? content.substring(0, 500) : content, e);
            // 尝试直接解析原始内容
            try {
                JSONObject json = JSON.parseObject(content.trim());
                // 再次尝试解析...
                log.info("直接解析原始内容成功");
            } catch (Exception ex) {
                log.error("JSON解析完全失败", ex);
            }
        }

        return result;
    }

    /**
     * 解析单个分镜信息
     */
    private ScriptParseResult.ShotInfo parseShotInfo(JSONObject shotObj) {
        ScriptParseResult.ShotInfo shot = new ScriptParseResult.ShotInfo();
        shot.setShotNumber(shotObj.getInteger("shotNumber"));
        shot.setSceneCode(shotObj.getString("sceneCode"));
        shot.setSceneName(shotObj.getString("sceneName"));
        shot.setDescription(shotObj.getString("description"));
        shot.setCameraAngle(shotObj.getString("cameraAngle"));
        shot.setCameraMovement(shotObj.getString("cameraMovement"));
        shot.setShotType(shotObj.getString("shotType"));
        shot.setSoundEffect(shotObj.getString("soundEffect"));

        // 处理时长：优先使用 duration，兼容旧的 startTime/endTime 格式
        Integer duration = shotObj.getInteger("duration");
        if (duration != null) {
            shot.setDuration(duration);
            shot.setStartTime(0);
            shot.setEndTime(duration);
        } else {
            // 兼容旧格式
            Integer startTime = shotObj.getInteger("startTime");
            Integer endTime = shotObj.getInteger("endTime");
            shot.setStartTime(startTime != null ? startTime : 0);
            shot.setEndTime(endTime != null ? endTime : 5);
            shot.setDuration(shot.getEndTime() - shot.getStartTime());
            if (shot.getDuration() == null || shot.getDuration() <= 0) {
                shot.setDuration(5);
            }
        }

        // 解析角色
        JSONArray charactersArray = shotObj.getJSONArray("characters");
        if (charactersArray != null) {
            List<ScriptParseResult.CharacterInShot> characters = new ArrayList<>();
            for (int j = 0; j < charactersArray.size(); j++) {
                JSONObject charObj = charactersArray.getJSONObject(j);
                ScriptParseResult.CharacterInShot character = new ScriptParseResult.CharacterInShot();
                character.setRoleName(charObj.getString("roleName"));
                character.setAction(charObj.getString("action"));
                character.setExpression(charObj.getString("expression"));
                character.setClothingId(charObj.getInteger("clothingId"));
                characters.add(character);
            }
            shot.setCharacters(characters);
        }

        // 解析道具
        JSONArray propsArray = shotObj.getJSONArray("props");
        if (propsArray != null) {
            List<ScriptParseResult.PropInShot> props = new ArrayList<>();
            for (int j = 0; j < propsArray.size(); j++) {
                JSONObject propObj = propsArray.getJSONObject(j);
                ScriptParseResult.PropInShot prop = new ScriptParseResult.PropInShot();
                prop.setPropName(propObj.getString("propName"));
                prop.setPosition(propObj.getString("position"));
                props.add(prop);
            }
            shot.setProps(props);
        }

        return shot;
    }
}
