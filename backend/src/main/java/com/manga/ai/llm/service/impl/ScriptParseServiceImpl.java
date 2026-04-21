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
        prompt.append("4. 指定镜头角度和运动方式\n\n");

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
        prompt.append("      \"description\": \"分镜描述\",\n");
        prompt.append("      \"duration\": 5,\n");
        prompt.append("      \"cameraAngle\": \"平视/仰视/俯视\",\n");
        prompt.append("      \"cameraMovement\": \"固定/推/拉/摇/移\",\n");
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
                jsonStr = content.substring(content.indexOf("```json") + 7);
                jsonStr = jsonStr.substring(0, jsonStr.indexOf("```"));
            } else if (content.contains("```")) {
                jsonStr = content.substring(content.indexOf("```") + 3);
                jsonStr = jsonStr.substring(0, jsonStr.indexOf("```"));
            }
            jsonStr = jsonStr.trim();

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
            log.error("解析LLM响应失败", e);
            // 尝试直接解析
            try {
                JSONObject json = JSON.parseObject(content);
                // 再次尝试解析...
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
        shot.setDescription(shotObj.getString("description"));
        shot.setDuration(shotObj.getInteger("duration"));
        shot.setCameraAngle(shotObj.getString("cameraAngle"));
        shot.setCameraMovement(shotObj.getString("cameraMovement"));

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
