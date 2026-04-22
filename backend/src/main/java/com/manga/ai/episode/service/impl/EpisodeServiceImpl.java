package com.manga.ai.episode.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.manga.ai.common.enums.EpisodeStatus;
import com.manga.ai.common.enums.SceneStatus;
import com.manga.ai.common.enums.PropStatus;
import com.manga.ai.common.enums.ShotStatus;
import com.manga.ai.common.enums.ShotGenerationStatus;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.common.utils.NamingUtil;
import com.manga.ai.episode.dto.EpisodeCreateRequest;
import com.manga.ai.episode.dto.EpisodeDetailVO;
import com.manga.ai.episode.dto.EpisodeProgressVO;
import com.manga.ai.episode.entity.Episode;
import com.manga.ai.episode.mapper.EpisodeMapper;
import com.manga.ai.episode.service.EpisodeService;
import com.manga.ai.llm.dto.ScriptParseResult;
import com.manga.ai.llm.service.ScriptParseService;
import com.manga.ai.prop.entity.Prop;
import com.manga.ai.prop.mapper.PropMapper;
import com.manga.ai.prop.service.PropService;
import com.manga.ai.scene.entity.Scene;
import com.manga.ai.scene.mapper.SceneMapper;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final PropMapper propMapper;
    private final ShotMapper shotMapper;
    private final ShotCharacterMapper shotCharacterMapper;
    private final ShotPropMapper shotPropMapper;
    private final RoleMapper roleMapper;
    private final ScriptParseService scriptParseService;
    private final SceneService sceneService;
    private final PropService propService;

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
    public void parseScript(Long episodeId) {
        log.info("开始解析剧本: episodeId={}", episodeId);

        Episode episode = episodeMapper.selectById(episodeId);
        if (episode == null) {
            log.error("剧集不存在: episodeId={}", episodeId);
            return;
        }

        try {
            // 更新状态为解析中
            episode.setStatus(EpisodeStatus.PARSING.getCode());
            episode.setUpdatedAt(LocalDateTime.now());
            episodeMapper.updateById(episode);

            // 删除已有的分镜数据（重新解析）
            deleteExistingShots(episodeId);

            // 调用剧本解析服务
            ScriptParseResult result = scriptParseService.parseScript(episode.getScriptText(), episode.getSeriesId());

            if ("success".equals(result.getStatus())) {
                // 保存解析结果
                saveParseResult(episode, result);

                // 更新状态为待审核
                episode.setStatus(EpisodeStatus.PENDING_REVIEW.getCode());
                episode.setParsedScript(JSON.toJSONString(result));
                episode.setUpdatedAt(LocalDateTime.now());
                episodeMapper.updateById(episode);

                log.info("剧本解析完成: episodeId={}, shots={}", episodeId, result.getShots().size());
            } else {
                // 解析失败
                log.error("剧本解析失败: episodeId={}, error={}", episodeId, result.getErrorMessage());
                episode.setStatus(EpisodeStatus.PENDING_PARSE.getCode());
                episode.setUpdatedAt(LocalDateTime.now());
                episodeMapper.updateById(episode);
            }
        } catch (Exception e) {
            log.error("剧本解析异常: episodeId={}", episodeId, e);
            episode.setStatus(EpisodeStatus.PENDING_PARSE.getCode());
            episode.setUpdatedAt(LocalDateTime.now());
            episodeMapper.updateById(episode);
        }
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
                    // 新场景：创建并生成
                    Scene scene = new Scene();
                    scene.setSeriesId(seriesId);
                    scene.setSceneName(sceneInfo.getSceneName());
                    scene.setSceneCode(sceneInfo.getSceneCode());
                    scene.setDescription(sceneInfo.getDescription());
                    scene.setLocationType(sceneInfo.getLocationType());
                    scene.setTimeOfDay(sceneInfo.getTimeOfDay());
                    scene.setWeather(sceneInfo.getWeather());
                    scene.setStatus(SceneStatus.GENERATING.getCode());
                    scene.setCreatedAt(LocalDateTime.now());
                    scene.setUpdatedAt(LocalDateTime.now());
                    sceneMapper.insert(scene);
                    sceneCodeToIdMap.put(sceneInfo.getSceneCode(), scene.getId());
                    log.info("创建新场景: sceneId={}, sceneName={}", scene.getId(), scene.getSceneName());
                    // 异步生成场景资产图片
                    sceneService.generateSceneAssets(scene.getId());
                } else {
                    // 场景已存在
                    sceneCodeToIdMap.put(sceneInfo.getSceneCode(), existingScene.getId());

                    // 检查是否已锁定
                    if (!SceneStatus.LOCKED.getCode().equals(existingScene.getStatus())) {
                        // 未锁定：重新生成图片（版本号+1）
                        log.info("场景已存在且未锁定，重新生成: sceneId={}, sceneName={}", existingScene.getId(), existingScene.getSceneName());
                        // 更新场景信息
                        existingScene.setDescription(sceneInfo.getDescription());
                        existingScene.setLocationType(sceneInfo.getLocationType());
                        existingScene.setTimeOfDay(sceneInfo.getTimeOfDay());
                        existingScene.setWeather(sceneInfo.getWeather());
                        existingScene.setUpdatedAt(LocalDateTime.now());
                        sceneMapper.updateById(existingScene);
                        // 异步重新生成（会创建新版本）
                        sceneService.regenerateSceneAsset(existingScene.getId(), null, null, null);
                    } else {
                        log.info("场景已锁定，跳过重新生成: sceneId={}, sceneName={}", existingScene.getId(), existingScene.getSceneName());
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
                    // 新道具：创建并生成
                    Prop prop = new Prop();
                    prop.setSeriesId(seriesId);
                    prop.setPropName(propInfo.getPropName());
                    prop.setPropCode(propInfo.getPropCode());
                    prop.setDescription(propInfo.getDescription());
                    prop.setPropType(propInfo.getPropType());
                    prop.setColor(propInfo.getColor());
                    prop.setStatus(PropStatus.GENERATING.getCode());
                    prop.setCreatedAt(LocalDateTime.now());
                    prop.setUpdatedAt(LocalDateTime.now());
                    propMapper.insert(prop);
                    // 使用 propName 作为键，方便后续分镜关联查找
                    propCodeToIdMap.put(propInfo.getPropName(), prop.getId());
                    log.info("创建新道具: propId={}, propName={}", prop.getId(), prop.getPropName());
                    // 异步生成道具资产图片
                    propService.generatePropAssets(prop.getId());
                } else {
                    // 道具已存在，使用 propName 作为键
                    propCodeToIdMap.put(propInfo.getPropName(), existingProp.getId());

                    // 检查是否已锁定
                    if (!PropStatus.LOCKED.getCode().equals(existingProp.getStatus())) {
                        // 未锁定：重新生成图片（版本号+1）
                        log.info("道具已存在且未锁定，重新生成: propId={}, propName={}", existingProp.getId(), existingProp.getPropName());
                        // 更新道具信息
                        existingProp.setDescription(propInfo.getDescription());
                        existingProp.setPropType(propInfo.getPropType());
                        existingProp.setColor(propInfo.getColor());
                        existingProp.setUpdatedAt(LocalDateTime.now());
                        propMapper.updateById(existingProp);
                        // 异步重新生成（会创建新版本）
                        propService.regeneratePropAsset(existingProp.getId(), null, null);
                    } else {
                        log.info("道具已锁定，跳过重新生成: propId={}, propName={}", existingProp.getId(), existingProp.getPropName());
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
}
