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

                LLMResponse response = llmService.chat(systemPrompt, userPrompt);

                if ("success".equals(response.getStatus())) {
                    result = parseLLMResponseAssetsOnly(response.getContent());

                    // 检查是否成功解析到资产
                    boolean hasScenes = result.getScenes() != null && !result.getScenes().isEmpty();
                    boolean hasProps = result.getProps() != null && !result.getProps().isEmpty();

                    if (hasScenes || hasProps) {
                        result.setStatus("success");
                        log.info("资产解析完成: scenes={}, props={}, 尝试次数={}",
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
                    long waitTime = 2000 * attempt;
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
                        log.warn("分镜解析结果为空，准备重试 (尝试 {}/{})", attempt, maxRetries);
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
                    long waitTime = 2000 * attempt; // 递增等待时间
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
        prompt.append("3. 为每个分镜编写详细的剧情描述（包含角色动作、表情、台词、环境变化等）\n");
        prompt.append("4. 根据剧情需要，可选地添加音效描述\n");
        prompt.append("5. 为每个分镜指定场景名称（描述该分镜发生的环境）\n\n");
        prompt.append("## 镜头类型说明\n");
        prompt.append("- 景别：远景、全景、中景、近景、特写、大特写\n");
        prompt.append("- 运动：推镜头、拉镜头、摇镜头、移镜头、跟镜头\n");
        prompt.append("- 示例：中景、全景+推镜头、特写+拉镜头\n\n");
        prompt.append("## 时间说明\n");
        prompt.append("- startTime: 镜头开始时间，固定为0\n");
        prompt.append("- endTime: 镜头结束时间，等于该镜头的时长（1-15秒）\n");
        prompt.append("- 例如：一个5秒的镜头，startTime=0, endTime=5\n\n");
        prompt.append("## 剧情描述要求\n");
        prompt.append("- 详细描述画面中发生的事情，包括角色动作、表情、台词\n");
        prompt.append("- 描述环境变化、光线、氛围等细节\n");
        prompt.append("- 示例: 小明站在客厅里，兴奋地挥舞手臂，大声说: \"今天终于要开学了!\" 他快速拿起书包，冲向门口。\n\n");
        prompt.append("## 场景说明\n");
        prompt.append("- sceneName: 场景名称，描述该分镜发生的环境，如：现代会议室、森林小径、城市街道\n");
        prompt.append("- 场景名称应简洁明了，便于识别\n\n");

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
        prompt.append("```json\n");
        prompt.append("{\n");
        prompt.append("  \"shots\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"shotNumber\": 1,\n");
        prompt.append("      \"sceneCode\": \"SC01\",\n");
        prompt.append("      \"sceneName\": \"场景名称\",\n");
        prompt.append("      \"startTime\": 0,\n");
        prompt.append("      \"endTime\": 8,\n");
        prompt.append("      \"shotType\": \"中景\",\n");
        prompt.append("      \"description\": \"详细的剧情描述，包含角色动作、表情、台词、环境变化等\",\n");
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

            // 尝试从截断的JSON中提取已完成的shots
            try {
                String partialJson = extractPartialShots(content);
                if (!partialJson.isEmpty()) {
                    log.info("尝试解析部分JSON...");
                    JSONObject json = JSON.parseObject(partialJson);
                    JSONArray shotsArray = json.getJSONArray("shots");
                    if (shotsArray != null && !shotsArray.isEmpty()) {
                        List<ScriptParseResult.ShotInfo> shots = new ArrayList<>();
                        for (int i = 0; i < shotsArray.size(); i++) {
                            try {
                                JSONObject shotObj = shotsArray.getJSONObject(i);
                                ScriptParseResult.ShotInfo shot = parseShotInfo(shotObj);
                                shots.add(shot);
                            } catch (Exception ex) {
                                log.warn("跳过不完整的shot[{}]: {}", i, ex.getMessage());
                            }
                        }
                        if (!shots.isEmpty()) {
                            result.setShots(shots);
                            result.setStatus("success");
                            log.info("从部分JSON中提取了{}个分镜", shots.size());
                        }
                    }
                }
            } catch (Exception ex) {
                log.warn("部分JSON解析也失败: {}", ex.getMessage());
            }
        }

        return result;
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
        prompt.append("      \"startTime\": 0,\n");
        prompt.append("      \"endTime\": 8,\n");
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
        shot.setDuration(shotObj.getInteger("duration"));
        shot.setCameraAngle(shotObj.getString("cameraAngle"));
        shot.setCameraMovement(shotObj.getString("cameraMovement"));
        // 解析新字段
        shot.setStartTime(shotObj.getInteger("startTime"));
        shot.setEndTime(shotObj.getInteger("endTime"));
        shot.setShotType(shotObj.getString("shotType"));
        shot.setSoundEffect(shotObj.getString("soundEffect"));

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
