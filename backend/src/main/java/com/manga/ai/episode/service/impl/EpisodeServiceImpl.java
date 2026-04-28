package com.manga.ai.episode.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.manga.ai.common.enums.CreditUsageType;
import com.manga.ai.common.enums.EpisodeStatus;
import com.manga.ai.common.enums.SceneStatus;
import com.manga.ai.common.enums.PropStatus;
import com.manga.ai.common.enums.ShotStatus;
import com.manga.ai.common.enums.ShotGenerationStatus;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.common.constants.CreditConstants;
import com.manga.ai.common.utils.NamingUtil;
import com.manga.ai.episode.dto.EpisodeCreateRequest;
import com.manga.ai.episode.dto.EpisodeDetailVO;
import com.manga.ai.episode.dto.EpisodeProgressVO;
import com.manga.ai.episode.dto.GenerateAssetsRequest;
import com.manga.ai.episode.dto.ParsedAssetsVO;
import com.manga.ai.episode.entity.Episode;
import com.manga.ai.episode.mapper.EpisodeMapper;
import com.manga.ai.episode.service.EpisodeService;
import com.manga.ai.llm.dto.ScriptParseResult;
import com.manga.ai.llm.service.ScriptParseService;
import com.manga.ai.prop.entity.Prop;
import com.manga.ai.prop.entity.PropAsset;
import com.manga.ai.prop.mapper.PropMapper;
import com.manga.ai.prop.mapper.PropAssetMapper;
import com.manga.ai.prop.service.PropService;
import com.manga.ai.scene.entity.Scene;
import com.manga.ai.scene.entity.SceneAsset;
import com.manga.ai.scene.mapper.SceneMapper;
import com.manga.ai.scene.mapper.SceneAssetMapper;
import com.manga.ai.scene.service.SceneService;
import com.manga.ai.series.entity.Series;
import com.manga.ai.series.mapper.SeriesMapper;
import com.manga.ai.common.enums.SeriesStatus;
import com.manga.ai.shot.entity.Shot;
import com.manga.ai.shot.entity.ShotCharacter;
import com.manga.ai.shot.entity.ShotProp;
import com.manga.ai.shot.mapper.ShotCharacterMapper;
import com.manga.ai.shot.mapper.ShotMapper;
import com.manga.ai.shot.mapper.ShotPropMapper;
import com.manga.ai.role.entity.Role;
import com.manga.ai.role.mapper.RoleMapper;
import com.manga.ai.role.service.RoleService;
import com.manga.ai.role.dto.RoleDetailVO;
import com.manga.ai.user.service.UserService;
import com.manga.ai.user.service.impl.UserServiceImpl.UserContextHolder;
import com.manga.ai.common.service.OssService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 剧集服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EpisodeServiceImpl implements EpisodeService {

    private final EpisodeMapper episodeMapper;
    private final SeriesMapper seriesMapper;
    private final SceneMapper sceneMapper;
    private final SceneAssetMapper sceneAssetMapper;
    private final PropMapper propMapper;
    private final PropAssetMapper propAssetMapper;
    private final ShotMapper shotMapper;
    private final ShotCharacterMapper shotCharacterMapper;
    private final ShotPropMapper shotPropMapper;
    private final RoleMapper roleMapper;
    private final RoleService roleService;
    private final ScriptParseService scriptParseService;
    private final SceneService sceneService;
    private final PropService propService;
    private final OssService ossService;
    private final UserService userService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createEpisode(Long seriesId, EpisodeCreateRequest request) {
        // 检查系列是否存在
        Series series = seriesMapper.selectById(seriesId);
        if (series == null) {
            throw new BusinessException("系列不存在");
        }

        // 检查系列是否已锁定
        if (!SeriesStatus.LOCKED.getCode().equals(series.getStatus())) {
            throw new BusinessException("系列未锁定，无法创建剧集");
        }

        // 检查集数编号是否已存在
        LambdaQueryWrapper<Episode> checkWrapper = new LambdaQueryWrapper<>();
        checkWrapper.eq(Episode::getSeriesId, seriesId)
                .eq(Episode::getEpisodeNumber, request.getEpisodeNumber());
        if (episodeMapper.selectCount(checkWrapper) > 0) {
            throw new BusinessException("该集数编号已存在");
        }

        // 创建剧集
        Episode episode = new Episode();
        episode.setSeriesId(seriesId);
        episode.setEpisodeNumber(request.getEpisodeNumber());
        episode.setEpisodeName(request.getEpisodeName());
        episode.setScriptText(request.getScriptText());
        episode.setStatus(EpisodeStatus.PENDING_PARSE.getCode());
        episode.setTotalShots(0);
        episode.setTotalDuration(0);
        episode.setCreatedAt(LocalDateTime.now());
        episode.setUpdatedAt(LocalDateTime.now());

        episodeMapper.insert(episode);
        log.info("创建剧集: episodeId={}, seriesId={}, episodeNumber={}", episode.getId(), seriesId, request.getEpisodeNumber());

        return episode.getId();
    }

    @Override
    @Async("llmExecutor")
    public void parseScript(Long episodeId, Long userId) {
        log.info("开始解析剧本资产（仅场景和道具）: episodeId={}, userId={}", episodeId, userId);

        Episode episode = episodeMapper.selectById(episodeId);
        if (episode == null) {
            log.error("剧集不存在: episodeId={}", episodeId);
            return;
        }

        int requiredCredits = CreditConstants.CREDITS_PER_SCRIPT_PARSE;

        try {
            // 更新状态为解析中
            episode.setStatus(EpisodeStatus.PARSING.getCode());
            episode.setUpdatedAt(LocalDateTime.now());
            episodeMapper.updateById(episode);

            // 删除已有的分镜数据（重新解析时清除旧分镜）
            deleteExistingShots(episodeId);

            // 只解析资产（场景和道具），不解析分镜
            ScriptParseResult result = scriptParseService.parseAssetsOnly(episode.getScriptText(), episode.getSeriesId());

            if ("success".equals(result.getStatus()) && result.getScenes() != null && result.getProps() != null) {
                // 不自动保存资产，等待用户选择后再创建
                // 只保存解析结果到 parsedScript
                episode.setParsedScript(JSON.toJSONString(result));
                episode.setUpdatedAt(LocalDateTime.now());
                episodeMapper.updateById(episode);

                log.info("资产解析完成: episodeId={}, scenes={}, props={}, 等待用户选择资产",
                        episodeId, result.getScenes().size(), result.getProps().size());
            } else {
                // 解析失败，返还积分
                if (userId != null) {
                    userService.refundCredits(userId, requiredCredits, "剧本解析失败返还-资产解析", episodeId, "EPISODE");
                    log.info("剧本解析失败，返还积分: userId={}, credits={}", userId, requiredCredits);
                }
                log.error("资产解析失败: episodeId={}, error={}", episodeId, result.getErrorMessage());
                episode.setStatus(EpisodeStatus.PENDING_PARSE.getCode());
                JSONObject errorJson = new JSONObject();
                errorJson.put("error", true);
                errorJson.put("errorMessage", result.getErrorMessage() != null ? result.getErrorMessage() : "资产解析失败");
                errorJson.put("timestamp", System.currentTimeMillis());
                episode.setParsedScript(errorJson.toJSONString());
                episode.setUpdatedAt(LocalDateTime.now());
                episodeMapper.updateById(episode);
            }
        } catch (Exception e) {
            // 异常时返还积分
            if (userId != null) {
                userService.refundCredits(userId, requiredCredits, "剧本解析异常返还-资产解析", episodeId, "EPISODE");
                log.info("剧本解析异常，返还积分: userId={}, credits={}", userId, requiredCredits);
            }
            log.error("资产解析异常: episodeId={}", episodeId, e);
            episode.setStatus(EpisodeStatus.PENDING_PARSE.getCode());
            JSONObject errorJson = new JSONObject();
            errorJson.put("error", true);
            errorJson.put("errorMessage", "资产解析异常: " + e.getMessage());
            errorJson.put("timestamp", System.currentTimeMillis());
            episode.setParsedScript(errorJson.toJSONString());
            episode.setUpdatedAt(LocalDateTime.now());
            episodeMapper.updateById(episode);
        }
    }

    @Override
    @Async("llmExecutor")
    public void parseShots(Long episodeId, Long userId) {
        log.info("开始解析分镜: episodeId={}, userId={}", episodeId, userId);

        Episode episode = episodeMapper.selectById(episodeId);
        if (episode == null) {
            log.error("剧集不存在: episodeId={}", episodeId);
            return;
        }

        int requiredCredits = CreditConstants.CREDITS_PER_SCRIPT_PARSE;

        try {
            // 从 parsedScript 获取解析模式
            String parseMode = "default";
            try {
                JSONObject parsedJson = JSON.parseObject(episode.getParsedScript());
                if (parsedJson != null) {
                    parseMode = parsedJson.getString("parseMode");
                    if (parseMode == null) parseMode = "default";
                }
            } catch (Exception e) {
                log.warn("获取解析模式失败，使用默认模式: episodeId={}", episodeId);
            }
            log.info("分镜解析模式: episodeId={}, parseMode={}", episodeId, parseMode);

            // 标记资产已确认，正在解析分镜
            try {
                JSONObject parsedJson = JSON.parseObject(episode.getParsedScript());
                if (parsedJson != null) {
                    parsedJson.put("assetsConfirmed", true);
                    episode.setParsedScript(parsedJson.toJSONString());
                    episode.setUpdatedAt(LocalDateTime.now());
                    episodeMapper.updateById(episode);
                    log.info("标记资产已确认: episodeId={}", episodeId);
                }
            } catch (Exception e) {
                log.error("更新资产确认状态失败: episodeId={}", episodeId, e);
            }

            // 删除已有的分镜数据
            deleteExistingShots(episodeId);

            // 获取场景编码到ID的映射
            Map<String, Long> sceneCodeToIdMap = new HashMap<>();
            LambdaQueryWrapper<Scene> sceneWrapper = new LambdaQueryWrapper<>();
            sceneWrapper.eq(Scene::getSeriesId, episode.getSeriesId())
                    .select(Scene::getId, Scene::getSceneCode);
            List<Scene> scenes = sceneMapper.selectList(sceneWrapper);
            for (Scene scene : scenes) {
                sceneCodeToIdMap.put(scene.getSceneCode(), scene.getId());
            }

            // 解析分镜（传递解析模式）
            ScriptParseResult result = scriptParseService.parseShots(episode.getScriptText(), episode.getSeriesId(), sceneCodeToIdMap, parseMode);

            if ("success".equals(result.getStatus()) && result.getShots() != null && !result.getShots().isEmpty()) {
                // 保存分镜结果（包含状态更新，在同一事务中）
                saveShotsResult(episode, result);
                log.info("分镜解析完成: episodeId={}, shots={}", episodeId, result.getShots().size());
            } else {
                // 解析失败，返还积分
                if (userId != null) {
                    userService.refundCredits(userId, requiredCredits, "剧本解析失败返还-分镜解析", episodeId, "EPISODE");
                    log.info("分镜解析失败，返还积分: userId={}, credits={}", userId, requiredCredits);
                }
                log.error("分镜解析失败: episodeId={}, error={}", episodeId, result.getErrorMessage());
                // 分镜解析失败，清除assetsConfirmed标记，保持解析中状态让用户可以重试
                try {
                    JSONObject parsedJson = JSON.parseObject(episode.getParsedScript());
                    if (parsedJson != null) {
                        parsedJson.remove("assetsConfirmed");
                        episode.setParsedScript(parsedJson.toJSONString());
                    }
                } catch (Exception e) {
                    log.warn("清除assetsConfirmed标记失败: episodeId={}", episodeId);
                }
                // 保持解析中状态，让用户可以重新选择资产
                episode.setUpdatedAt(LocalDateTime.now());
                episodeMapper.updateById(episode);
                log.info("分镜解析失败，保持解析中状态等待重试: episodeId={}", episodeId);
            }
        } catch (Exception e) {
            // 异常时返还积分
            if (userId != null) {
                userService.refundCredits(userId, requiredCredits, "剧本解析异常返还-分镜解析", episodeId, "EPISODE");
                log.info("分镜解析异常，返还积分: userId={}, credits={}", userId, requiredCredits);
            }
            log.error("分镜解析异常: episodeId={}", episodeId, e);
            // 分镜解析异常，清除assetsConfirmed标记，保持解析中状态
            try {
                JSONObject parsedJson = JSON.parseObject(episode.getParsedScript());
                if (parsedJson != null) {
                    parsedJson.remove("assetsConfirmed");
                    episode.setParsedScript(parsedJson.toJSONString());
                }
            } catch (Exception ex) {
                log.warn("清除assetsConfirmed标记失败: episodeId={}", episodeId);
            }
            episode.setUpdatedAt(LocalDateTime.now());
            episodeMapper.updateById(episode);
            log.info("分镜解析异常，保持解析中状态等待重试: episodeId={}", episodeId);
        }
    }

    /**
     * 保存资产结果（场景和道具）
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveAssetsResult(Episode episode, ScriptParseResult result) {
        Long seriesId = episode.getSeriesId();

        // 保存场景
        if (result.getScenes() != null) {
            for (ScriptParseResult.SceneInfo sceneInfo : result.getScenes()) {
                LambdaQueryWrapper<Scene> sceneWrapper = new LambdaQueryWrapper<>();
                sceneWrapper.eq(Scene::getSeriesId, seriesId)
                        .eq(Scene::getSceneName, sceneInfo.getSceneName());
                Scene existingScene = sceneMapper.selectOne(sceneWrapper);

                if (existingScene == null) {
                    Scene scene = new Scene();
                    scene.setSeriesId(seriesId);
                    scene.setSceneName(sceneInfo.getSceneName());
                    scene.setSceneCode(sceneInfo.getSceneCode());
                    scene.setDescription(sceneInfo.getDescription());
                    scene.setLocationType(sceneInfo.getLocationType());
                    scene.setTimeOfDay(sceneInfo.getTimeOfDay());
                    scene.setWeather(sceneInfo.getWeather());
                    scene.setStatus(SceneStatus.PENDING_REVIEW.getCode());
                    scene.setCreatedAt(LocalDateTime.now());
                    scene.setUpdatedAt(LocalDateTime.now());
                    sceneMapper.insert(scene);
                    log.info("创建新场景: sceneId={}, sceneName={}", scene.getId(), scene.getSceneName());
                } else {
                    if (!SceneStatus.LOCKED.getCode().equals(existingScene.getStatus())) {
                        existingScene.setDescription(sceneInfo.getDescription());
                        existingScene.setLocationType(sceneInfo.getLocationType());
                        existingScene.setTimeOfDay(sceneInfo.getTimeOfDay());
                        existingScene.setWeather(sceneInfo.getWeather());
                        existingScene.setUpdatedAt(LocalDateTime.now());
                        sceneMapper.updateById(existingScene);
                    }
                }
            }
        }

        // 保存道具
        if (result.getProps() != null) {
            for (ScriptParseResult.PropInfo propInfo : result.getProps()) {
                LambdaQueryWrapper<Prop> propWrapper = new LambdaQueryWrapper<>();
                propWrapper.eq(Prop::getSeriesId, seriesId)
                        .eq(Prop::getPropName, propInfo.getPropName());
                Prop existingProp = propMapper.selectOne(propWrapper);

                if (existingProp == null) {
                    Prop prop = new Prop();
                    prop.setSeriesId(seriesId);
                    prop.setPropName(propInfo.getPropName());
                    prop.setPropCode(propInfo.getPropCode());
                    prop.setDescription(propInfo.getDescription());
                    prop.setPropType(propInfo.getPropType());
                    prop.setColor(propInfo.getColor());
                    prop.setStatus(PropStatus.PENDING_REVIEW.getCode());
                    prop.setCreatedAt(LocalDateTime.now());
                    prop.setUpdatedAt(LocalDateTime.now());
                    propMapper.insert(prop);
                    log.info("创建新道具: propId={}, propName={}", prop.getId(), prop.getPropName());
                } else {
                    if (!PropStatus.LOCKED.getCode().equals(existingProp.getStatus())) {
                        existingProp.setDescription(propInfo.getDescription());
                        existingProp.setPropType(propInfo.getPropType());
                        existingProp.setColor(propInfo.getColor());
                        existingProp.setUpdatedAt(LocalDateTime.now());
                        propMapper.updateById(existingProp);
                    }
                }
            }
        }
    }

    /**
     * 保存分镜结果（包含剧集状态更新，确保原子性）
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveShotsResult(Episode episode, ScriptParseResult result) {
        Long seriesId = episode.getSeriesId();

        // 获取场景映射
        Map<String, Long> sceneCodeToIdMap = new HashMap<>();
        LambdaQueryWrapper<Scene> sceneWrapper = new LambdaQueryWrapper<>();
        sceneWrapper.eq(Scene::getSeriesId, seriesId)
                .select(Scene::getId, Scene::getSceneCode);
        List<Scene> scenes = sceneMapper.selectList(sceneWrapper);
        for (Scene scene : scenes) {
            sceneCodeToIdMap.put(scene.getSceneCode(), scene.getId());
        }

        // 获取道具映射
        Map<String, Long> propNameToIdMap = new HashMap<>();
        LambdaQueryWrapper<Prop> propWrapper = new LambdaQueryWrapper<>();
        propWrapper.eq(Prop::getSeriesId, seriesId)
                .select(Prop::getId, Prop::getPropName);
        List<Prop> props = propMapper.selectList(propWrapper);
        for (Prop prop : props) {
            propNameToIdMap.put(prop.getPropName(), prop.getId());
        }

        // 获取角色映射
        Map<String, Long> roleNameToIdMap = new HashMap<>();
        LambdaQueryWrapper<Role> roleWrapper = new LambdaQueryWrapper<>();
        roleWrapper.eq(Role::getSeriesId, seriesId)
                .select(Role::getId, Role::getRoleName);
        List<Role> roles = roleMapper.selectList(roleWrapper);
        for (Role role : roles) {
            roleNameToIdMap.put(role.getRoleName(), role.getId());
        }

        // 保存分镜
        if (result.getShots() != null) {
            for (ScriptParseResult.ShotInfo shotInfo : result.getShots()) {
                Shot shot = new Shot();
                shot.setEpisodeId(episode.getId());
                shot.setShotNumber(shotInfo.getShotNumber());
                shot.setShotType(shotInfo.getShotType());
                shot.setStartTime(shotInfo.getStartTime());
                shot.setEndTime(shotInfo.getEndTime());
                shot.setSoundEffect(shotInfo.getSoundEffect());
                shot.setSceneName(shotInfo.getSceneName());
                // 计算时长（如果没提供，根据开始和结束时间计算）
                if (shotInfo.getDuration() != null) {
                    shot.setDuration(shotInfo.getDuration());
                } else if (shotInfo.getStartTime() != null && shotInfo.getEndTime() != null) {
                    shot.setDuration(shotInfo.getEndTime() - shotInfo.getStartTime());
                } else {
                    shot.setDuration(5);
                }
                shot.setCameraAngle(shotInfo.getCameraAngle());
                shot.setCameraMovement(shotInfo.getCameraMovement());
                shot.setGenerationStatus(ShotGenerationStatus.PENDING.getCode());
                shot.setStatus(ShotStatus.PENDING_REVIEW.getCode());
                shot.setCreatedAt(LocalDateTime.now());
                shot.setUpdatedAt(LocalDateTime.now());

                // 构建完整的格式化描述文本
                StringBuilder fullDescription = new StringBuilder();

                // 时间
                int startTime = shotInfo.getStartTime() != null ? shotInfo.getStartTime() : 0;
                int duration = shot.getDuration() != null ? shot.getDuration() : 5;
                int endTime = startTime + duration;
                fullDescription.append("时间【").append(formatShotTime(startTime)).append("-").append(formatShotTime(endTime)).append("】\n");

                // 镜头
                if (shotInfo.getShotType() != null && !shotInfo.getShotType().isEmpty()) {
                    fullDescription.append("镜头【").append(shotInfo.getShotType()).append("】\n");
                }

                // 剧情
                if (shotInfo.getDescription() != null && !shotInfo.getDescription().isEmpty()) {
                    fullDescription.append("剧情【").append(shotInfo.getDescription()).append("】\n");
                }

                // 音效
                if (shotInfo.getSoundEffect() != null && !shotInfo.getSoundEffect().isEmpty()) {
                    fullDescription.append("音效【").append(shotInfo.getSoundEffect()).append("】");
                }

                shot.setDescription(fullDescription.toString().trim());

                if (shotInfo.getSceneCode() != null && sceneCodeToIdMap.containsKey(shotInfo.getSceneCode())) {
                    shot.setSceneId(sceneCodeToIdMap.get(shotInfo.getSceneCode()));
                }

                if (shotInfo.getCharacters() != null) {
                    shot.setCharactersJson(JSON.toJSONString(shotInfo.getCharacters()));
                }

                if (shotInfo.getProps() != null) {
                    shot.setPropsJson(JSON.toJSONString(shotInfo.getProps()));
                }

                shotMapper.insert(shot);

                // 保存分镜-角色关联
                if (shotInfo.getCharacters() != null) {
                    for (ScriptParseResult.CharacterInShot charInfo : shotInfo.getCharacters()) {
                        Long roleId = roleNameToIdMap.get(charInfo.getRoleName());
                        if (roleId != null) {
                            ShotCharacter shotCharacter = new ShotCharacter();
                            shotCharacter.setShotId(shot.getId());
                            shotCharacter.setRoleId(roleId);
                            shotCharacter.setCharacterAction(charInfo.getAction());
                            shotCharacter.setCharacterExpression(charInfo.getExpression());
                            shotCharacter.setClothingId(charInfo.getClothingId() != null ? charInfo.getClothingId() : 1);
                            shotCharacter.setCreatedAt(LocalDateTime.now());
                            shotCharacterMapper.insert(shotCharacter);
                        }
                    }
                }

                // 保存分镜-道具关联
                if (shotInfo.getProps() != null) {
                    for (ScriptParseResult.PropInShot propInfo : shotInfo.getProps()) {
                        Long propId = propNameToIdMap.get(propInfo.getPropName());
                        if (propId != null) {
                            ShotProp shotProp = new ShotProp();
                            shotProp.setShotId(shot.getId());
                            shotProp.setPropId(propId);
                            shotProp.setCreatedAt(LocalDateTime.now());
                            shotPropMapper.insert(shotProp);
                        }
                    }
                }

                log.info("创建分镜: shotId={}, shotNumber={}", shot.getId(), shot.getShotNumber());
            }
        }

        // 更新剧集统计和状态（在同一事务中）
        int totalDuration = 0;
        for (ScriptParseResult.ShotInfo shot : result.getShots()) {
            totalDuration += shot.getDuration() != null ? shot.getDuration() : 5;
        }
        episode.setTotalShots(result.getShots().size());
        episode.setTotalDuration(totalDuration);

        // 清除assetsConfirmed标记
        try {
            JSONObject parsedJson = JSON.parseObject(episode.getParsedScript());
            if (parsedJson != null) {
                parsedJson.remove("assetsConfirmed");
                episode.setParsedScript(parsedJson.toJSONString());
            }
        } catch (Exception e) {
            log.warn("清除assetsConfirmed标记失败: episodeId={}", episode.getId());
        }

        // 分镜解析完成，更新状态为待审核
        episode.setStatus(EpisodeStatus.PENDING_REVIEW.getCode());
        episode.setUpdatedAt(LocalDateTime.now());
        episodeMapper.updateById(episode);

        log.info("分镜保存完成，剧集状态更新为待审核: episodeId={}, shots={}", episode.getId(), result.getShots().size());
    }

    /**
     * 保存解析结果
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveParseResult(Episode episode, ScriptParseResult result) {
        Long seriesId = episode.getSeriesId();

        // 1. 保存场景
        Map<String, Long> sceneCodeToIdMap = new HashMap<>();
        if (result.getScenes() != null) {
            for (ScriptParseResult.SceneInfo sceneInfo : result.getScenes()) {
                // 检查场景是否已存在（按场景名称匹配）
                LambdaQueryWrapper<Scene> sceneWrapper = new LambdaQueryWrapper<>();
                sceneWrapper.eq(Scene::getSeriesId, seriesId)
                        .eq(Scene::getSceneName, sceneInfo.getSceneName());
                Scene existingScene = sceneMapper.selectOne(sceneWrapper);

                if (existingScene == null) {
                    // 新场景：创建记录，不自动生成图片（等待用户选择）
                    Scene scene = new Scene();
                    scene.setSeriesId(seriesId);
                    scene.setSceneName(sceneInfo.getSceneName());
                    scene.setSceneCode(sceneInfo.getSceneCode());
                    scene.setDescription(sceneInfo.getDescription());
                    scene.setLocationType(sceneInfo.getLocationType());
                    scene.setTimeOfDay(sceneInfo.getTimeOfDay());
                    scene.setWeather(sceneInfo.getWeather());
                    scene.setStatus(SceneStatus.PENDING_REVIEW.getCode()); // 待审核状态，不自动生成
                    scene.setCreatedAt(LocalDateTime.now());
                    scene.setUpdatedAt(LocalDateTime.now());
                    sceneMapper.insert(scene);
                    sceneCodeToIdMap.put(sceneInfo.getSceneCode(), scene.getId());
                    log.info("创建新场景: sceneId={}, sceneName={}", scene.getId(), scene.getSceneName());
                    // 不自动生成图片，等待用户选择
                } else {
                    // 场景已存在
                    sceneCodeToIdMap.put(sceneInfo.getSceneCode(), existingScene.getId());

                    // 更新场景信息（不自动重新生成图片）
                    if (!SceneStatus.LOCKED.getCode().equals(existingScene.getStatus())) {
                        existingScene.setDescription(sceneInfo.getDescription());
                        existingScene.setLocationType(sceneInfo.getLocationType());
                        existingScene.setTimeOfDay(sceneInfo.getTimeOfDay());
                        existingScene.setWeather(sceneInfo.getWeather());
                        existingScene.setUpdatedAt(LocalDateTime.now());
                        sceneMapper.updateById(existingScene);
                        log.info("场景已存在，更新信息: sceneId={}, sceneName={}", existingScene.getId(), existingScene.getSceneName());
                    } else {
                        log.info("场景已锁定，跳过更新: sceneId={}, sceneName={}", existingScene.getId(), existingScene.getSceneName());
                    }
                }
            }
        }

        // 2. 保存道具
        Map<String, Long> propCodeToIdMap = new HashMap<>();
        if (result.getProps() != null) {
            for (ScriptParseResult.PropInfo propInfo : result.getProps()) {
                // 检查道具是否已存在（按道具名称匹配）
                LambdaQueryWrapper<Prop> propWrapper = new LambdaQueryWrapper<>();
                propWrapper.eq(Prop::getSeriesId, seriesId)
                        .eq(Prop::getPropName, propInfo.getPropName());
                Prop existingProp = propMapper.selectOne(propWrapper);

                if (existingProp == null) {
                    // 新道具：创建记录，不自动生成图片（等待用户选择）
                    Prop prop = new Prop();
                    prop.setSeriesId(seriesId);
                    prop.setPropName(propInfo.getPropName());
                    prop.setPropCode(propInfo.getPropCode());
                    prop.setDescription(propInfo.getDescription());
                    prop.setPropType(propInfo.getPropType());
                    prop.setColor(propInfo.getColor());
                    prop.setStatus(PropStatus.PENDING_REVIEW.getCode()); // 待审核状态，不自动生成
                    prop.setCreatedAt(LocalDateTime.now());
                    prop.setUpdatedAt(LocalDateTime.now());
                    propMapper.insert(prop);
                    // 使用 propName 作为键，方便后续分镜关联查找
                    propCodeToIdMap.put(propInfo.getPropName(), prop.getId());
                    log.info("创建新道具: propId={}, propName={}", prop.getId(), prop.getPropName());
                    // 不自动生成图片，等待用户选择
                } else {
                    // 道具已存在，使用 propName 作为键
                    propCodeToIdMap.put(propInfo.getPropName(), existingProp.getId());

                    // 更新道具信息（不自动重新生成图片）
                    if (!PropStatus.LOCKED.getCode().equals(existingProp.getStatus())) {
                        existingProp.setDescription(propInfo.getDescription());
                        existingProp.setPropType(propInfo.getPropType());
                        existingProp.setColor(propInfo.getColor());
                        existingProp.setUpdatedAt(LocalDateTime.now());
                        propMapper.updateById(existingProp);
                        log.info("道具已存在，更新信息: propId={}, propName={}", existingProp.getId(), existingProp.getPropName());
                    } else {
                        log.info("道具已锁定，跳过更新: propId={}, propName={}", existingProp.getId(), existingProp.getPropName());
                    }
                }
            }
        }

        // 3. 获取角色映射
        Map<String, Long> roleNameToIdMap = new HashMap<>();
        LambdaQueryWrapper<Role> roleWrapper = new LambdaQueryWrapper<>();
        roleWrapper.eq(Role::getSeriesId, seriesId)
                .select(Role::getId, Role::getRoleName);
        List<Role> roles = roleMapper.selectList(roleWrapper);
        for (Role role : roles) {
            roleNameToIdMap.put(role.getRoleName(), role.getId());
        }

        // 4. 保存分镜
        int totalDuration = 0;
        if (result.getShots() != null) {
            for (ScriptParseResult.ShotInfo shotInfo : result.getShots()) {
                Shot shot = new Shot();
                shot.setEpisodeId(episode.getId());
                shot.setShotNumber(shotInfo.getShotNumber());
                shot.setDescription(shotInfo.getDescription());
                shot.setDuration(shotInfo.getDuration() != null ? shotInfo.getDuration() : 5);
                shot.setCameraAngle(shotInfo.getCameraAngle());
                shot.setCameraMovement(shotInfo.getCameraMovement());
                shot.setGenerationStatus(ShotGenerationStatus.PENDING.getCode());
                shot.setStatus(ShotStatus.PENDING_REVIEW.getCode());
                shot.setCreatedAt(LocalDateTime.now());
                shot.setUpdatedAt(LocalDateTime.now());

                // 设置场景ID
                if (shotInfo.getSceneCode() != null && sceneCodeToIdMap.containsKey(shotInfo.getSceneCode())) {
                    shot.setSceneId(sceneCodeToIdMap.get(shotInfo.getSceneCode()));
                }

                // 保存角色信息JSON
                if (shotInfo.getCharacters() != null) {
                    shot.setCharactersJson(JSON.toJSONString(shotInfo.getCharacters()));
                }

                // 保存道具信息JSON
                if (shotInfo.getProps() != null) {
                    shot.setPropsJson(JSON.toJSONString(shotInfo.getProps()));
                }

                shotMapper.insert(shot);
                totalDuration += shot.getDuration();

                // 保存分镜-角色关联
                if (shotInfo.getCharacters() != null) {
                    for (ScriptParseResult.CharacterInShot charInfo : shotInfo.getCharacters()) {
                        Long roleId = roleNameToIdMap.get(charInfo.getRoleName());
                        if (roleId != null) {
                            ShotCharacter shotCharacter = new ShotCharacter();
                            shotCharacter.setShotId(shot.getId());
                            shotCharacter.setRoleId(roleId);
                            shotCharacter.setCharacterAction(charInfo.getAction());
                            shotCharacter.setCharacterExpression(charInfo.getExpression());
                            shotCharacter.setClothingId(charInfo.getClothingId() != null ? charInfo.getClothingId() : 1);
                            shotCharacter.setCreatedAt(LocalDateTime.now());
                            shotCharacterMapper.insert(shotCharacter);
                        }
                    }
                }

                // 保存分镜-道具关联
                if (shotInfo.getProps() != null) {
                    for (ScriptParseResult.PropInShot propInfo : shotInfo.getProps()) {
                        Long propId = propCodeToIdMap.get(propInfo.getPropName());
                        if (propId == null) {
                            // 尝试通过名称查找
                            LambdaQueryWrapper<Prop> pWrapper = new LambdaQueryWrapper<>();
                            pWrapper.eq(Prop::getSeriesId, seriesId)
                                    .eq(Prop::getPropName, propInfo.getPropName())
                                    .last("LIMIT 1");
                            Prop existingProp = propMapper.selectOne(pWrapper);
                            if (existingProp != null) {
                                propId = existingProp.getId();
                            }
                        }
                        if (propId != null) {
                            ShotProp shotProp = new ShotProp();
                            shotProp.setShotId(shot.getId());
                            shotProp.setPropId(propId);
                            shotProp.setCreatedAt(LocalDateTime.now());
                            shotPropMapper.insert(shotProp);
                        }
                    }
                }

                log.info("创建分镜: shotId={}, shotNumber={}", shot.getId(), shot.getShotNumber());
            }
        }

        // 更新剧集统计
        episode.setTotalShots(result.getShots() != null ? result.getShots().size() : 0);
        episode.setTotalDuration(totalDuration);
        episodeMapper.updateById(episode);
    }

    @Override
    public EpisodeDetailVO getEpisodeDetail(Long episodeId) {
        Episode episode = episodeMapper.selectById(episodeId);
        if (episode == null) {
            throw new BusinessException("剧集不存在");
        }

        EpisodeDetailVO vo = convertToVO(episode);

        // 获取分镜列表
        LambdaQueryWrapper<Shot> shotWrapper = new LambdaQueryWrapper<>();
        shotWrapper.eq(Shot::getEpisodeId, episodeId)
                .orderByAsc(Shot::getShotNumber);
        List<Shot> shots = shotMapper.selectList(shotWrapper);

        List<EpisodeDetailVO.ShotSummary> shotSummaries = shots.stream()
                .map(this::convertToShotSummary)
                .collect(Collectors.toList());
        vo.setShots(shotSummaries);

        // 获取角色列表
        List<RoleDetailVO> roleDetailVOs = roleService.getRolesBySeriesId(episode.getSeriesId());
        List<EpisodeDetailVO.RoleInfo> roleInfos = roleDetailVOs.stream()
                .map(this::convertToRoleInfo)
                .collect(Collectors.toList());
        vo.setRoles(roleInfos);

        return vo;
    }

    @Override
    public EpisodeProgressVO getEpisodeProgress(Long episodeId) {
        Episode episode = episodeMapper.selectById(episodeId);
        if (episode == null) {
            throw new BusinessException("剧集不存在");
        }

        EpisodeProgressVO vo = new EpisodeProgressVO();
        vo.setEpisodeId(episodeId);
        vo.setStatus(episode.getStatus());
        vo.setTotalShots(episode.getTotalShots());

        // 检查资产是否解析完成（等待用户选择）
        // 如果 assetsConfirmed=true，说明用户已确认，正在解析分镜
        boolean assetsReady = false;
        boolean assetsConfirmed = false;
        boolean shotsParsing = false;
        if (EpisodeStatus.PARSING.getCode().equals(episode.getStatus())) {
            String parsedScript = episode.getParsedScript();
            if (parsedScript != null && !parsedScript.isEmpty()) {
                try {
                    JSONObject json = JSON.parseObject(parsedScript);
                    // 如果 assetsConfirmed=true，说明用户已确认，正在解析分镜
                    if (json.getBooleanValue("assetsConfirmed")) {
                        assetsConfirmed = true;
                        shotsParsing = true;
                    }
                    // 如果有 scenes 和 props 且没有 error，说明资产解析完成
                    else if (json.containsKey("scenes") && json.containsKey("props") && !json.getBooleanValue("error")) {
                        assetsReady = true;
                    }
                } catch (Exception e) {
                    // 解析失败，不设置
                }
            }
        }
        vo.setAssetsReady(assetsReady);
        vo.setAssetsConfirmed(assetsConfirmed);
        vo.setShotsParsing(shotsParsing);

        // 统计已完成和失败的分镜数
        LambdaQueryWrapper<Shot> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Shot::getEpisodeId, episodeId);
        List<Shot> shots = shotMapper.selectList(wrapper);

        int completed = 0, failed = 0;
        for (Shot shot : shots) {
            if (ShotGenerationStatus.COMPLETED.getCode().equals(shot.getGenerationStatus())) {
                completed++;
            } else if (ShotGenerationStatus.FAILED.getCode().equals(shot.getGenerationStatus())) {
                failed++;
            }
        }
        vo.setCompletedShots(completed);
        vo.setFailedShots(failed);

        // 添加分镜进度列表
        List<EpisodeProgressVO.ShotProgress> shotProgressList = shots.stream()
                .map(shot -> {
                    EpisodeProgressVO.ShotProgress sp = new EpisodeProgressVO.ShotProgress();
                    sp.setId(shot.getId());
                    sp.setGenerationStatus(shot.getGenerationStatus());
                    return sp;
                })
                .collect(Collectors.toList());
        vo.setShots(shotProgressList);

        if (episode.getTotalShots() != null && episode.getTotalShots() > 0) {
            vo.setProgress(completed * 100 / episode.getTotalShots());
        } else {
            vo.setProgress(0);
        }

        return vo;
    }

    @Override
    public List<EpisodeDetailVO> getEpisodesBySeriesId(Long seriesId) {
        LambdaQueryWrapper<Episode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Episode::getSeriesId, seriesId)
                .orderByAsc(Episode::getEpisodeNumber);
        List<Episode> episodes = episodeMapper.selectList(wrapper);

        return episodes.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteEpisode(Long episodeId) {
        Episode episode = episodeMapper.selectById(episodeId);
        if (episode == null) {
            throw new BusinessException("剧集不存在");
        }

        // 删除关联的分镜
        LambdaQueryWrapper<Shot> shotWrapper = new LambdaQueryWrapper<>();
        shotWrapper.eq(Shot::getEpisodeId, episodeId);
        List<Shot> shots = shotMapper.selectList(shotWrapper);

        for (Shot shot : shots) {
            // 删除分镜-角色关联
            LambdaQueryWrapper<ShotCharacter> scWrapper = new LambdaQueryWrapper<>();
            scWrapper.eq(ShotCharacter::getShotId, shot.getId());
            shotCharacterMapper.delete(scWrapper);

            // 删除分镜-道具关联
            LambdaQueryWrapper<ShotProp> spWrapper = new LambdaQueryWrapper<>();
            spWrapper.eq(ShotProp::getShotId, shot.getId());
            shotPropMapper.delete(spWrapper);

            // 删除分镜
            shotMapper.deleteById(shot.getId());
        }

        // 删除剧集
        episodeMapper.deleteById(episodeId);
        log.info("删除剧集: episodeId={}", episodeId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateScript(Long episodeId, String scriptText) {
        Episode episode = episodeMapper.selectById(episodeId);
        if (episode == null) {
            throw new BusinessException("剧集不存在");
        }

        // 检查剧集状态，只有待解析状态才能修改剧本
        if (!EpisodeStatus.PENDING_PARSE.getCode().equals(episode.getStatus())
                && !EpisodeStatus.PENDING_REVIEW.getCode().equals(episode.getStatus())) {
            throw new BusinessException("当前剧集状态不支持修改剧本");
        }

        episode.setScriptText(scriptText);
        episode.setUpdatedAt(LocalDateTime.now());
        episodeMapper.updateById(episode);
        log.info("更新剧本内容: episodeId={}", episodeId);
    }

    /**
     * 删除剧集已有的分镜数据
     */
    private void deleteExistingShots(Long episodeId) {
        log.info("删除已有分镜数据: episodeId={}", episodeId);

        LambdaQueryWrapper<Shot> shotWrapper = new LambdaQueryWrapper<>();
        shotWrapper.eq(Shot::getEpisodeId, episodeId);
        List<Shot> shots = shotMapper.selectList(shotWrapper);

        for (Shot shot : shots) {
            // 删除分镜-角色关联
            LambdaQueryWrapper<ShotCharacter> scWrapper = new LambdaQueryWrapper<>();
            scWrapper.eq(ShotCharacter::getShotId, shot.getId());
            shotCharacterMapper.delete(scWrapper);

            // 删除分镜-道具关联
            LambdaQueryWrapper<ShotProp> spWrapper = new LambdaQueryWrapper<>();
            spWrapper.eq(ShotProp::getShotId, shot.getId());
            shotPropMapper.delete(spWrapper);

            // 删除分镜
            shotMapper.deleteById(shot.getId());
        }

        log.info("已删除 {} 个分镜", shots.size());
    }

    private EpisodeDetailVO convertToVO(Episode episode) {
        EpisodeDetailVO vo = new EpisodeDetailVO();
        BeanUtils.copyProperties(episode, vo);

        // 获取系列名称
        Series series = seriesMapper.selectById(episode.getSeriesId());
        if (series != null) {
            vo.setSeriesName(series.getSeriesName());
        }

        // 检查是否有错误信息（解析失败）
        if (episode.getParsedScript() != null && !episode.getParsedScript().isEmpty()) {
            try {
                JSONObject parsedJson = JSON.parseObject(episode.getParsedScript());
                if (parsedJson.containsKey("error") && parsedJson.getBoolean("error")) {
                    vo.setErrorMessage(parsedJson.getString("errorMessage"));
                }
            } catch (Exception e) {
                // 忽略解析错误，说明是正常的解析结果
            }
        }

        return vo;
    }

    private EpisodeDetailVO.ShotSummary convertToShotSummary(Shot shot) {
        EpisodeDetailVO.ShotSummary summary = new EpisodeDetailVO.ShotSummary();
        BeanUtils.copyProperties(shot, summary);

        // 设置状态描述
        if (shot.getGenerationStatus() != null) {
            switch (shot.getGenerationStatus()) {
                case 0: summary.setGenerationStatusDesc("待生成"); break;
                case 1: summary.setGenerationStatusDesc("生成中"); break;
                case 2: summary.setGenerationStatusDesc("已完成"); break;
                case 3: summary.setGenerationStatusDesc("生成失败"); break;
            }
        }
        if (shot.getStatus() != null) {
            switch (shot.getStatus()) {
                case 0: summary.setStatusDesc("待审核"); break;
                case 1: summary.setStatusDesc("已通过"); break;
                case 2: summary.setStatusDesc("已拒绝"); break;
            }
        }

        return summary;
    }

    /**
     * 转换角色详情VO为剧集详情中的角色信息
     */
    private EpisodeDetailVO.RoleInfo convertToRoleInfo(RoleDetailVO roleDetailVO) {
        EpisodeDetailVO.RoleInfo roleInfo = new EpisodeDetailVO.RoleInfo();
        roleInfo.setId(roleDetailVO.getId());
        roleInfo.setRoleName(roleDetailVO.getRoleName());
        roleInfo.setStatus(roleDetailVO.getStatus());
        roleInfo.setStatusDesc(roleDetailVO.getStatusDesc());

        // 获取主资产图片（默认视图）
        if (roleDetailVO.getAssets() != null && !roleDetailVO.getAssets().isEmpty()) {
            // 找到默认视图的资产
            for (RoleDetailVO.AssetInfo asset : roleDetailVO.getAssets()) {
                if ("default".equals(asset.getViewType()) || asset.getViewType() == null) {
                    roleInfo.setAssetUrl(ossService.refreshUrl(asset.getFilePath()));
                    break;
                }
            }
            // 如果没有默认视图，使用第一个资产
            if (roleInfo.getAssetUrl() == null) {
                roleInfo.setAssetUrl(ossService.refreshUrl(roleDetailVO.getAssets().get(0).getFilePath()));
            }
        }

        // 获取服装信息（按clothingId分组）
        if (roleDetailVO.getAssets() != null) {
            Map<Integer, List<RoleDetailVO.AssetInfo>> clothingAssets = roleDetailVO.getAssets().stream()
                    .filter(a -> a.getClothingId() != null)
                    .collect(Collectors.groupingBy(RoleDetailVO.AssetInfo::getClothingId));

            List<EpisodeDetailVO.RoleInfo.ClothingInfo> clothings = new ArrayList<>();
            for (Map.Entry<Integer, List<RoleDetailVO.AssetInfo>> entry : clothingAssets.entrySet()) {
                EpisodeDetailVO.RoleInfo.ClothingInfo clothingInfo = new EpisodeDetailVO.RoleInfo.ClothingInfo();
                clothingInfo.setId(entry.getKey().longValue());
                clothingInfo.setStatus(1);

                // 获取第一个资产的名称和图片
                RoleDetailVO.AssetInfo firstAsset = entry.getValue().get(0);
                clothingInfo.setClothingName(firstAsset.getViewName() != null ? firstAsset.getViewName() : "服装" + entry.getKey());
                clothingInfo.setAssetUrl(ossService.refreshUrl(firstAsset.getFilePath()));

                clothings.add(clothingInfo);
            }
            roleInfo.setClothings(clothings);
        }

        return roleInfo;
    }

    @Override
    public ParsedAssetsVO getParsedAssets(Long episodeId) {
        Episode episode = episodeMapper.selectById(episodeId);
        if (episode == null) {
            throw new BusinessException("剧集不存在");
        }

        Long seriesId = episode.getSeriesId();
        ParsedAssetsVO vo = new ParsedAssetsVO();
        vo.setEpisodeId(episodeId);

        // 直接从 parsedScript 获取 LLM 解析的场景和道具数据
        if (episode.getParsedScript() == null || episode.getParsedScript().isEmpty()) {
            vo.setScenes(new ArrayList<>());
            vo.setProps(new ArrayList<>());
            return vo;
        }

        JSONObject parsedJson = JSON.parseObject(episode.getParsedScript());

        // 构建场景名称到数据库记录的映射（获取状态信息）
        Map<String, Scene> sceneMap = new HashMap<>();
        LambdaQueryWrapper<Scene> sceneWrapper = new LambdaQueryWrapper<>();
        sceneWrapper.eq(Scene::getSeriesId, seriesId);
        List<Scene> dbScenes = sceneMapper.selectList(sceneWrapper);
        for (Scene scene : dbScenes) {
            sceneMap.put(scene.getSceneName(), scene);
        }

        // 构建道具名称到数据库记录的映射
        Map<String, Prop> propMap = new HashMap<>();
        LambdaQueryWrapper<Prop> propWrapper = new LambdaQueryWrapper<>();
        propWrapper.eq(Prop::getSeriesId, seriesId);
        List<Prop> dbProps = propMapper.selectList(propWrapper);
        for (Prop prop : dbProps) {
            propMap.put(prop.getPropName(), prop);
        }

        // 从 LLM 解析结果构建场景列表
        List<ParsedAssetsVO.SceneAssetInfo> sceneInfos = new ArrayList<>();
        JSONArray scenesArray = parsedJson.getJSONArray("scenes");
        if (scenesArray != null) {
            for (int i = 0; i < scenesArray.size(); i++) {
                JSONObject sceneObj = scenesArray.getJSONObject(i);
                String sceneName = sceneObj.getString("sceneName");

                ParsedAssetsVO.SceneAssetInfo info = new ParsedAssetsVO.SceneAssetInfo();
                info.setSceneName(sceneName);
                info.setSceneCode(sceneObj.getString("sceneCode"));
                // 使用 LLM 解析的描述
                info.setDescription(sceneObj.getString("description"));

                // 检查数据库中是否已存在
                Scene dbScene = sceneMap.get(sceneName);
                if (dbScene != null) {
                    info.setId(dbScene.getId());
                    boolean isLocked = SceneStatus.LOCKED.getCode().equals(dbScene.getStatus());
                    info.setIsLocked(isLocked);

                    // 检查是否有资产
                    LambdaQueryWrapper<SceneAsset> assetWrapper = new LambdaQueryWrapper<>();
                    assetWrapper.eq(SceneAsset::getSceneId, dbScene.getId())
                            .eq(SceneAsset::getIsActive, 1);
                    SceneAsset activeAsset = sceneAssetMapper.selectOne(assetWrapper);
                    boolean hasAssets = activeAsset != null && activeAsset.getFilePath() != null;
                    info.setHasAssets(hasAssets);
                    if (hasAssets) {
                        info.setPreviewUrl(activeAsset.getFilePath());
                    }
                    info.setIsNew(false);
                    info.setSelected(!isLocked);
                } else {
                    info.setIsLocked(false);
                    info.setHasAssets(false);
                    info.setIsNew(true);
                    info.setSelected(true);
                }

                sceneInfos.add(info);
            }
        }
        vo.setScenes(sceneInfos);

        // 从 LLM 解析结果构建道具列表
        List<ParsedAssetsVO.PropAssetInfo> propInfos = new ArrayList<>();
        JSONArray propsArray = parsedJson.getJSONArray("props");
        if (propsArray != null) {
            for (int i = 0; i < propsArray.size(); i++) {
                JSONObject propObj = propsArray.getJSONObject(i);
                String propName = propObj.getString("propName");

                ParsedAssetsVO.PropAssetInfo info = new ParsedAssetsVO.PropAssetInfo();
                info.setPropName(propName);
                info.setPropCode(propObj.getString("propCode"));
                // 使用 LLM 解析的描述
                info.setDescription(propObj.getString("description"));

                // 检查数据库中是否已存在
                Prop dbProp = propMap.get(propName);
                if (dbProp != null) {
                    info.setId(dbProp.getId());
                    boolean isLocked = PropStatus.LOCKED.getCode().equals(dbProp.getStatus());
                    info.setIsLocked(isLocked);

                    // 检查是否有资产
                    LambdaQueryWrapper<PropAsset> propAssetWrapper = new LambdaQueryWrapper<>();
                    propAssetWrapper.eq(PropAsset::getPropId, dbProp.getId())
                            .eq(PropAsset::getIsActive, 1);
                    PropAsset activePropAsset = propAssetMapper.selectOne(propAssetWrapper);
                    boolean hasAssets = activePropAsset != null && activePropAsset.getFilePath() != null;
                    info.setHasAssets(hasAssets);
                    if (hasAssets) {
                        info.setPreviewUrl(activePropAsset.getFilePath());
                    }
                    info.setIsNew(false);
                    info.setSelected(!isLocked);
                } else {
                    info.setIsLocked(false);
                    info.setHasAssets(false);
                    info.setIsNew(true);
                    info.setSelected(true);
                }

                propInfos.add(info);
            }
        }
        vo.setProps(propInfos);

        log.info("getParsedAssets 返回: episodeId={}, scenes={}, props={}",
                episodeId, sceneInfos.size(), propInfos.size());

        return vo;
    }

    @Override
    @Async("llmExecutor")
    public void generateSelectedAssets(Long episodeId, GenerateAssetsRequest request, Long userId) {
        log.info("开始批量生成资产: episodeId={}, userId={}, sceneIds={}, propIds={}, newSceneNames={}, newPropNames={}",
                episodeId, userId, request.getSceneIds(), request.getPropIds(),
                request.getNewSceneNames(), request.getNewPropNames());

        Episode episode = episodeMapper.selectById(episodeId);
        if (episode == null) {
            log.error("剧集不存在: episodeId={}", episodeId);
            return;
        }

        // 标记资产已确认，正在解析分镜
        // 更新 parsedScript，添加 assetsConfirmed 标记和解析模式
        try {
            JSONObject parsedJson = JSON.parseObject(episode.getParsedScript());
            if (parsedJson != null) {
                parsedJson.put("assetsConfirmed", true);
                // 保存解析模式
                parsedJson.put("parseMode", request.getParseMode() != null ? request.getParseMode() : "default");
                episode.setParsedScript(parsedJson.toJSONString());
                episode.setUpdatedAt(LocalDateTime.now());
                episodeMapper.updateById(episode);
                log.info("标记资产已确认: episodeId={}, parseMode={}", episodeId, request.getParseMode());
            }
        } catch (Exception e) {
            log.error("更新资产确认状态失败: episodeId={}", episodeId, e);
        }

        Long seriesId = episode.getSeriesId();

        // 从 parsedScript 获取 LLM 解析的详细信息
        Map<String, JSONObject> sceneInfoMap = new HashMap<>();
        Map<String, JSONObject> propInfoMap = new HashMap<>();
        if (episode.getParsedScript() != null && !episode.getParsedScript().isEmpty()) {
            try {
                JSONObject parsedJson = JSON.parseObject(episode.getParsedScript());
                JSONArray scenesArray = parsedJson.getJSONArray("scenes");
                if (scenesArray != null) {
                    for (int i = 0; i < scenesArray.size(); i++) {
                        JSONObject sceneObj = scenesArray.getJSONObject(i);
                        sceneInfoMap.put(sceneObj.getString("sceneName"), sceneObj);
                    }
                }
                JSONArray propsArray = parsedJson.getJSONArray("props");
                if (propsArray != null) {
                    for (int i = 0; i < propsArray.size(); i++) {
                        JSONObject propObj = propsArray.getJSONObject(i);
                        propInfoMap.put(propObj.getString("propName"), propObj);
                    }
                }
            } catch (Exception e) {
                log.error("解析 parsedScript 失败: episodeId={}", episodeId, e);
            }
        }

        // 注意：不再自动删除未选中的已存在资产
        // 已存在的资产应该通过单独的删除操作来删除
        // 用户重新解析后只需选择要生成哪些新资产

        // 创建新道具并生成图片
        List<Long> allPropIds = new ArrayList<>(request.getPropIds() != null ? request.getPropIds() : new ArrayList<>());
        if (request.getNewPropNames() != null && !request.getNewPropNames().isEmpty()) {
            for (String propName : request.getNewPropNames()) {
                JSONObject propInfo = propInfoMap.get(propName);
                if (propInfo == null) continue;

                // 创建新道具
                Prop prop = new Prop();
                prop.setSeriesId(seriesId);
                prop.setPropName(propName);
                prop.setPropCode(propInfo.getString("propCode"));
                prop.setDescription(propInfo.getString("description"));
                prop.setPropType(propInfo.getString("propType"));
                prop.setColor(propInfo.getString("color"));
                prop.setStatus(PropStatus.GENERATING.getCode());
                prop.setCreatedAt(LocalDateTime.now());
                prop.setUpdatedAt(LocalDateTime.now());
                propMapper.insert(prop);

                allPropIds.add(prop.getId());
                log.info("创建新道具: propId={}, propName={}", prop.getId(), propName);
            }
        }

        // 创建新场景并生成图片
        List<Long> allSceneIds = new ArrayList<>(request.getSceneIds() != null ? request.getSceneIds() : new ArrayList<>());
        if (request.getNewSceneNames() != null && !request.getNewSceneNames().isEmpty()) {
            for (String sceneName : request.getNewSceneNames()) {
                JSONObject sceneInfo = sceneInfoMap.get(sceneName);
                if (sceneInfo == null) continue;

                // 创建新场景
                Scene scene = new Scene();
                scene.setSeriesId(seriesId);
                scene.setSceneName(sceneName);
                scene.setSceneCode(sceneInfo.getString("sceneCode"));
                scene.setDescription(sceneInfo.getString("description"));
                scene.setLocationType(sceneInfo.getString("locationType"));
                scene.setTimeOfDay(sceneInfo.getString("timeOfDay"));
                scene.setWeather(sceneInfo.getString("weather"));
                scene.setStatus(SceneStatus.GENERATING.getCode());
                scene.setCreatedAt(LocalDateTime.now());
                scene.setUpdatedAt(LocalDateTime.now());
                sceneMapper.insert(scene);

                allSceneIds.add(scene.getId());
                log.info("创建新场景: sceneId={}, sceneName={}", scene.getId(), sceneName);
            }
        }

        // 更新已存在的场景状态为生成中
        if (request.getSceneIds() != null && !request.getSceneIds().isEmpty()) {
            for (Long sceneId : request.getSceneIds()) {
                Scene scene = sceneMapper.selectById(sceneId);
                if (scene == null || SceneStatus.LOCKED.getCode().equals(scene.getStatus())) {
                    continue;
                }
                scene.setStatus(SceneStatus.GENERATING.getCode());
                scene.setUpdatedAt(LocalDateTime.now());
                sceneMapper.updateById(scene);
            }
        }

        // 更新已存在的道具状态为生成中
        if (request.getPropIds() != null && !request.getPropIds().isEmpty()) {
            for (Long propId : request.getPropIds()) {
                Prop prop = propMapper.selectById(propId);
                if (prop == null || PropStatus.LOCKED.getCode().equals(prop.getStatus())) {
                    continue;
                }
                prop.setStatus(PropStatus.GENERATING.getCode());
                prop.setUpdatedAt(LocalDateTime.now());
                propMapper.updateById(prop);
            }
        }

        // 触发场景生成任务
        for (Long sceneId : allSceneIds) {
            Scene scene = sceneMapper.selectById(sceneId);
            if (scene == null || SceneStatus.LOCKED.getCode().equals(scene.getStatus())) {
                continue;
            }

            // 检查是否已有资产
            LambdaQueryWrapper<SceneAsset> assetWrapper = new LambdaQueryWrapper<>();
            assetWrapper.eq(SceneAsset::getSceneId, sceneId)
                    .eq(SceneAsset::getIsActive, 1);
            SceneAsset existingAsset = sceneAssetMapper.selectOne(assetWrapper);

                if (existingAsset != null) {
                    // 已有资产，重新生成（版本+1）
                    log.info("提交场景重新生成任务: sceneId={}", sceneId);
                    sceneService.regenerateSceneAssetWithCredit(sceneId, null, null, request.getQuality(), userId);
                } else {
                    // 无资产，首次生成
                    log.info("提交场景首次生成任务: sceneId={}", sceneId);
                    sceneService.generateSceneAssetsWithCredit(sceneId, userId);
                }
        }

        // 触发道具生成任务
        for (Long propId : allPropIds) {
            Prop prop = propMapper.selectById(propId);
            if (prop == null || PropStatus.LOCKED.getCode().equals(prop.getStatus())) {
                continue;
            }

            // 检查是否已有资产
            LambdaQueryWrapper<PropAsset> propAssetWrapper = new LambdaQueryWrapper<>();
            propAssetWrapper.eq(PropAsset::getPropId, propId)
                    .eq(PropAsset::getIsActive, 1);
            PropAsset existingPropAsset = propAssetMapper.selectOne(propAssetWrapper);

            if (existingPropAsset != null) {
                log.info("提交道具重新生成任务: propId={}", propId);
                propService.regeneratePropAssetWithCredit(propId, null, request.getQuality(), userId);
            } else {
                log.info("提交道具首次生成任务: propId={}", propId);
                propService.generatePropAssetsWithCredit(propId, userId);
            }
        }

        // 异步触发分镜解析
        log.info("触发分镜解析: episodeId={}, userId={}", episodeId, userId);
        parseShots(episodeId, userId);

        log.info("批量生成资产任务已全部提交: episodeId={}", episodeId);
    }

    /**
     * 格式化分镜时间（秒转为 MM:SS 格式）
     */
    private String formatShotTime(int seconds) {
        int minutes = seconds / 60;
        int secs = seconds % 60;
        return String.format("%02d:%02d", minutes, secs);
    }
}
