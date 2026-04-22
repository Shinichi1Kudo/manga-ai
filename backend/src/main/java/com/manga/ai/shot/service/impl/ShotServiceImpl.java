package com.manga.ai.shot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.manga.ai.asset.entity.RoleAsset;
import com.manga.ai.asset.mapper.RoleAssetMapper;
import com.manga.ai.common.enums.ShotGenerationStatus;
import com.manga.ai.common.enums.ShotStatus;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.episode.entity.Episode;
import com.manga.ai.episode.mapper.EpisodeMapper;
import com.manga.ai.common.enums.EpisodeStatus;
import com.manga.ai.prop.entity.PropAsset;
import com.manga.ai.prop.mapper.PropAssetMapper;
import com.manga.ai.role.entity.Role;
import com.manga.ai.role.mapper.RoleMapper;
import com.manga.ai.scene.entity.Scene;
import com.manga.ai.scene.mapper.SceneMapper;
import com.manga.ai.shot.dto.ShotDetailVO;
import com.manga.ai.shot.dto.ShotReviewRequest;
import com.manga.ai.shot.dto.ShotUpdateRequest;
import com.manga.ai.shot.entity.Shot;
import com.manga.ai.shot.entity.ShotCharacter;
import com.manga.ai.shot.entity.ShotProp;
import com.manga.ai.shot.entity.VideoMetadata;
import com.manga.ai.shot.mapper.ShotCharacterMapper;
import com.manga.ai.shot.mapper.ShotMapper;
import com.manga.ai.shot.mapper.ShotPropMapper;
import com.manga.ai.shot.mapper.VideoMetadataMapper;
import com.manga.ai.shot.service.ShotService;
import com.manga.ai.video.dto.SeedanceRequest;
import com.manga.ai.video.dto.SeedanceResponse;
import com.manga.ai.video.service.SeedanceService;
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
import java.util.stream.Collectors;

/**
 * 分镜服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShotServiceImpl implements ShotService {

    private final ShotMapper shotMapper;
    private final ShotCharacterMapper shotCharacterMapper;
    private final ShotPropMapper shotPropMapper;
    private final SceneMapper sceneMapper;
    private final RoleMapper roleMapper;
    private final RoleAssetMapper roleAssetMapper;
    private final PropAssetMapper propAssetMapper;
    private final EpisodeMapper episodeMapper;
    private final VideoMetadataMapper videoMetadataMapper;
    private final SeedanceService seedanceService;

    @Override
    public ShotDetailVO getShotDetail(Long shotId) {
        Shot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            throw new BusinessException("分镜不存在");
        }

        return convertToDetailVO(shot);
    }

    @Override
    public List<ShotDetailVO> getShotsByEpisodeId(Long episodeId) {
        // 查询所有分镜
        LambdaQueryWrapper<Shot> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Shot::getEpisodeId, episodeId)
                .orderByAsc(Shot::getShotNumber);
        List<Shot> shots = shotMapper.selectList(wrapper);

        if (shots.isEmpty()) {
            return List.of();
        }

        List<Long> shotIds = shots.stream().map(Shot::getId).collect(Collectors.toList());
        Set<Long> sceneIds = shots.stream().map(Shot::getSceneId).filter(id -> id != null).collect(Collectors.toSet());

        // 批量查询场景
        Map<Long, Scene> sceneMap = Map.of();
        if (!sceneIds.isEmpty()) {
            List<Scene> scenes = sceneMapper.selectBatchIds(sceneIds);
            sceneMap = scenes.stream().collect(Collectors.toMap(Scene::getId, s -> s));
        }

        // 批量查询分镜角色
        LambdaQueryWrapper<ShotCharacter> scWrapper = new LambdaQueryWrapper<>();
        scWrapper.in(ShotCharacter::getShotId, shotIds);
        List<ShotCharacter> allShotCharacters = shotCharacterMapper.selectList(scWrapper);

        // 收集所有角色ID
        Set<Long> roleIds = allShotCharacters.stream().map(ShotCharacter::getRoleId).collect(Collectors.toSet());

        // 批量查询角色
        Map<Long, Role> roleMap = Map.of();
        if (!roleIds.isEmpty()) {
            List<Role> roles = roleMapper.selectBatchIds(roleIds);
            roleMap = roles.stream().collect(Collectors.toMap(Role::getId, r -> r));
        }

        // 批量查询角色资产 - 一次性查询所有需要的角色资产
        Map<String, RoleAsset> assetMap = new HashMap<>();
        if (!roleIds.isEmpty()) {
            LambdaQueryWrapper<RoleAsset> assetWrapper = new LambdaQueryWrapper<>();
            assetWrapper.in(RoleAsset::getRoleId, roleIds)
                    .eq(RoleAsset::getIsActive, 1);
            List<RoleAsset> assets = roleAssetMapper.selectList(assetWrapper);
            for (RoleAsset asset : assets) {
                String key = asset.getRoleId() + "_" + asset.getClothingId();
                assetMap.put(key, asset);
            }
        }

        // 按shotId分组角色
        Map<Long, List<ShotCharacter>> charactersByShotId = allShotCharacters.stream()
                .collect(Collectors.groupingBy(ShotCharacter::getShotId));

        // 批量查询分镜道具
        LambdaQueryWrapper<ShotProp> spWrapper = new LambdaQueryWrapper<>();
        spWrapper.in(ShotProp::getShotId, shotIds);
        List<ShotProp> allShotProps = shotPropMapper.selectList(spWrapper);

        // 收集道具ID
        Set<Long> propIds = allShotProps.stream().map(ShotProp::getPropId).collect(Collectors.toSet());

        // 批量查询道具资产
        Map<Long, PropAsset> propAssetMap = new HashMap<>();
        if (!propIds.isEmpty()) {
            LambdaQueryWrapper<PropAsset> paWrapper = new LambdaQueryWrapper<>();
            paWrapper.in(PropAsset::getPropId, propIds)
                    .eq(PropAsset::getIsActive, 1);
            List<PropAsset> propAssets = propAssetMapper.selectList(paWrapper);
            propAssetMap = propAssets.stream().collect(Collectors.toMap(PropAsset::getPropId, a -> a, (a, b) -> a));
        }

        // 按shotId分组道具
        Map<Long, List<ShotProp>> propsByShotId = allShotProps.stream()
                .collect(Collectors.groupingBy(ShotProp::getShotId));

        // 最终的Map
        final Map<Long, Scene> finalSceneMap = sceneMap;
        final Map<Long, Role> finalRoleMap = roleMap;
        final Map<String, RoleAsset> finalAssetMap = assetMap;
        final Map<Long, PropAsset> finalPropAssetMap = propAssetMap;
        final Map<Long, List<ShotProp>> finalPropsByShotId = propsByShotId;

        // 组装VO
        return shots.stream()
                .map(shot -> convertToDetailVOOptimized(shot, finalSceneMap, charactersByShotId.getOrDefault(shot.getId(), List.of()), finalRoleMap, finalAssetMap, finalPropsByShotId.getOrDefault(shot.getId(), List.of()), finalPropAssetMap))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateShot(Long shotId, ShotUpdateRequest request) {
        Shot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            throw new BusinessException("分镜不存在");
        }

        if (request.getDescription() != null) {
            shot.setDescription(request.getDescription());
        }
        if (request.getDuration() != null) {
            shot.setDuration(Math.min(request.getDuration(), 15));  // 最大15秒
        }
        if (request.getCameraAngle() != null) {
            shot.setCameraAngle(request.getCameraAngle());
        }
        if (request.getCameraMovement() != null) {
            shot.setCameraMovement(request.getCameraMovement());
        }
        if (request.getUserPrompt() != null) {
            shot.setUserPrompt(request.getUserPrompt());
        }

        shot.setUpdatedAt(LocalDateTime.now());
        shotMapper.updateById(shot);
        log.info("更新分镜: shotId={}", shotId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reviewShot(Long shotId, ShotReviewRequest request) {
        Shot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            throw new BusinessException("分镜不存在");
        }

        if (Boolean.TRUE.equals(request.getApproved())) {
            shot.setStatus(ShotStatus.APPROVED.getCode());
        } else {
            shot.setStatus(ShotStatus.REJECTED.getCode());
        }

        shot.setUpdatedAt(LocalDateTime.now());
        shotMapper.updateById(shot);
        log.info("审核分镜: shotId={}, approved={}", shotId, request.getApproved());

        // 检查剧集是否所有分镜都已审核通过
        checkEpisodeCompletion(shot.getEpisodeId());
    }

    @Override
    @Async("videoGenerateExecutor")
    public void generateVideo(Long shotId) {
        log.info("开始生成视频: shotId={}", shotId);

        Shot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            log.error("分镜不存在: shotId={}", shotId);
            return;
        }

        try {
            // 更新状态为生成中
            shot.setGenerationStatus(ShotGenerationStatus.GENERATING.getCode());
            shot.setUpdatedAt(LocalDateTime.now());
            shotMapper.updateById(shot);

            // 构建提示词
            String prompt = shot.getUserPrompt();
            if (prompt == null || prompt.trim().isEmpty()) {
                prompt = seedanceService.buildShotPrompt(shotId);
            }

            // 创建生成请求
            SeedanceRequest request = new SeedanceRequest();
            request.setPrompt(prompt);
            request.setDuration(shot.getDuration() != null ? shot.getDuration() : 5);
            request.setShotId(shotId);

            // 调用Seedance生成视频
            SeedanceResponse response = seedanceService.generateVideo(request);

            if ("completed".equals(response.getStatus())) {
                // 保存视频URL
                shot.setVideoUrl(response.getVideoUrl());
                shot.setThumbnailUrl(response.getThumbnailUrl());
                shot.setVideoSeed(response.getSeed());
                shot.setGenerationStatus(ShotGenerationStatus.COMPLETED.getCode());
                shot.setUpdatedAt(LocalDateTime.now());
                shotMapper.updateById(shot);

                // 保存元数据
                saveVideoMetadata(shot, prompt, response);

                log.info("视频生成完成: shotId={}", shotId);
            } else {
                throw new RuntimeException("视频生成失败: " + response.getErrorMessage());
            }
        } catch (Exception e) {
            log.error("视频生成异常: shotId={}", shotId, e);
            shot.setGenerationStatus(ShotGenerationStatus.FAILED.getCode());
            shot.setUpdatedAt(LocalDateTime.now());
            shotMapper.updateById(shot);
        }
    }

    @Override
    public void generateVideosForEpisode(Long episodeId) {
        log.info("开始批量生成视频: episodeId={}", episodeId);

        // 更新剧集状态为制作中
        Episode episode = episodeMapper.selectById(episodeId);
        if (episode != null) {
            episode.setStatus(EpisodeStatus.PRODUCING.getCode());
            episode.setUpdatedAt(LocalDateTime.now());
            episodeMapper.updateById(episode);
        }

        // 获取所有待生成的分镜
        LambdaQueryWrapper<Shot> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Shot::getEpisodeId, episodeId)
                .eq(Shot::getGenerationStatus, ShotGenerationStatus.PENDING.getCode())
                .orderByAsc(Shot::getShotNumber);
        List<Shot> shots = shotMapper.selectList(wrapper);

        // 异步生成每个分镜的视频
        for (Shot shot : shots) {
            generateVideo(shot.getId());
        }

        log.info("批量视频生成任务已提交: episodeId={}, count={}", episodeId, shots.size());
    }

    /**
     * 检查剧集是否完成
     */
    private void checkEpisodeCompletion(Long episodeId) {
        LambdaQueryWrapper<Shot> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Shot::getEpisodeId, episodeId);
        List<Shot> shots = shotMapper.selectList(wrapper);

        boolean allApproved = true;
        for (Shot shot : shots) {
            if (!ShotStatus.APPROVED.getCode().equals(shot.getStatus())) {
                allApproved = false;
                break;
            }
        }

        if (allApproved && !shots.isEmpty()) {
            Episode episode = episodeMapper.selectById(episodeId);
            if (episode != null) {
                episode.setStatus(EpisodeStatus.COMPLETED.getCode());
                episode.setUpdatedAt(LocalDateTime.now());
                episodeMapper.updateById(episode);
                log.info("剧集已完成: episodeId={}", episodeId);
            }
        }
    }

    /**
     * 保存视频元数据
     */
    private void saveVideoMetadata(Shot shot, String prompt, SeedanceResponse response) {
        VideoMetadata metadata = new VideoMetadata();
        metadata.setShotId(shot.getId());
        metadata.setPrompt(prompt);
        metadata.setUserPrompt(shot.getUserPrompt());
        metadata.setSeed(response.getSeed());
        metadata.setModelVersion("seedance-2.0");
        metadata.setVideoDuration(shot.getDuration());
        metadata.setGenerationTimeMs(response.getGenerationTimeMs());
        metadata.setCreatedAt(LocalDateTime.now());
        videoMetadataMapper.insert(metadata);
    }

    /**
     * 转换为详情VO（优化版，使用预查询的数据）
     */
    private ShotDetailVO convertToDetailVOOptimized(Shot shot, Map<Long, Scene> sceneMap,
            List<ShotCharacter> shotCharacters, Map<Long, Role> roleMap, Map<String, RoleAsset> assetMap,
            List<ShotProp> shotProps, Map<Long, PropAsset> propAssetMap) {
        ShotDetailVO vo = new ShotDetailVO();
        BeanUtils.copyProperties(shot, vo);

        // 获取场景名称
        if (shot.getSceneId() != null) {
            Scene scene = sceneMap.get(shot.getSceneId());
            if (scene != null) {
                vo.setSceneName(scene.getSceneName());
            }
        }

        // 处理角色信息
        List<ShotDetailVO.CharacterInfo> characters = new ArrayList<>();
        for (ShotCharacter sc : shotCharacters) {
            ShotDetailVO.CharacterInfo charInfo = new ShotDetailVO.CharacterInfo();
            charInfo.setRoleId(sc.getRoleId());
            charInfo.setAction(sc.getCharacterAction());
            charInfo.setExpression(sc.getCharacterExpression());
            charInfo.setClothingId(sc.getClothingId());
            charInfo.setPositionX(sc.getPositionX());
            charInfo.setPositionY(sc.getPositionY());
            charInfo.setScale(sc.getScale());

            // 获取角色名称
            Role role = roleMap.get(sc.getRoleId());
            if (role != null) {
                charInfo.setRoleName(role.getRoleName());
            }

            // 获取角色资产图片
            if (sc.getClothingId() != null) {
                String key = sc.getRoleId() + "_" + sc.getClothingId();
                RoleAsset asset = assetMap.get(key);
                if (asset != null) {
                    charInfo.setAssetUrl(asset.getFilePath());
                    charInfo.setClothingName(asset.getClothingName());
                }
            }

            characters.add(charInfo);
        }
        vo.setCharacters(characters);

        // 处理道具信息（使用预查询的数据）
        List<ShotDetailVO.PropInfo> props = new ArrayList<>();
        for (ShotProp sp : shotProps) {
            ShotDetailVO.PropInfo propInfo = new ShotDetailVO.PropInfo();
            propInfo.setPropId(sp.getPropId());
            propInfo.setPositionX(sp.getPositionX());
            propInfo.setPositionY(sp.getPositionY());
            propInfo.setScale(sp.getScale());
            propInfo.setRotation(sp.getRotation());

            // 获取道具资产图片
            PropAsset asset = propAssetMap.get(sp.getPropId());
            if (asset != null) {
                propInfo.setPropName(asset.getFileName());
                propInfo.setAssetUrl(asset.getFilePath());
            }

            props.add(propInfo);
        }
        vo.setProps(props);

        return vo;
    }

    /**
     * 转换为详情VO（单个查询，用于详情页）
     */
    private ShotDetailVO convertToDetailVO(Shot shot) {
        ShotDetailVO vo = new ShotDetailVO();
        BeanUtils.copyProperties(shot, vo);

        // 获取场景名称
        if (shot.getSceneId() != null) {
            Scene scene = sceneMapper.selectById(shot.getSceneId());
            if (scene != null) {
                vo.setSceneName(scene.getSceneName());
            }
        }

        // 获取角色信息
        LambdaQueryWrapper<ShotCharacter> scWrapper = new LambdaQueryWrapper<>();
        scWrapper.eq(ShotCharacter::getShotId, shot.getId());
        List<ShotCharacter> shotCharacters = shotCharacterMapper.selectList(scWrapper);

        List<ShotDetailVO.CharacterInfo> characters = new ArrayList<>();
        for (ShotCharacter sc : shotCharacters) {
            ShotDetailVO.CharacterInfo charInfo = new ShotDetailVO.CharacterInfo();
            charInfo.setRoleId(sc.getRoleId());
            charInfo.setAction(sc.getCharacterAction());
            charInfo.setExpression(sc.getCharacterExpression());
            charInfo.setClothingId(sc.getClothingId());
            charInfo.setPositionX(sc.getPositionX());
            charInfo.setPositionY(sc.getPositionY());
            charInfo.setScale(sc.getScale());

            // 获取角色名称
            Role role = roleMapper.selectById(sc.getRoleId());
            if (role != null) {
                charInfo.setRoleName(role.getRoleName());
            }

            // 获取角色资产图片
            if (sc.getClothingId() != null) {
                LambdaQueryWrapper<RoleAsset> assetWrapper = new LambdaQueryWrapper<>();
                assetWrapper.eq(RoleAsset::getRoleId, sc.getRoleId())
                        .eq(RoleAsset::getClothingId, sc.getClothingId())
                        .eq(RoleAsset::getIsActive, 1)
                        .last("LIMIT 1");
                RoleAsset asset = roleAssetMapper.selectOne(assetWrapper);
                if (asset != null) {
                    charInfo.setAssetUrl(asset.getFilePath());
                    charInfo.setClothingName(asset.getClothingName());
                }
            }

            characters.add(charInfo);
        }
        vo.setCharacters(characters);

        // 获取道具信息
        LambdaQueryWrapper<ShotProp> spWrapper = new LambdaQueryWrapper<>();
        spWrapper.eq(ShotProp::getShotId, shot.getId());
        List<ShotProp> shotProps = shotPropMapper.selectList(spWrapper);

        List<ShotDetailVO.PropInfo> props = new ArrayList<>();
        for (ShotProp sp : shotProps) {
            ShotDetailVO.PropInfo propInfo = new ShotDetailVO.PropInfo();
            propInfo.setPropId(sp.getPropId());
            propInfo.setPositionX(sp.getPositionX());
            propInfo.setPositionY(sp.getPositionY());
            propInfo.setScale(sp.getScale());
            propInfo.setRotation(sp.getRotation());

            // 获取道具资产图片
            LambdaQueryWrapper<PropAsset> assetWrapper = new LambdaQueryWrapper<>();
            assetWrapper.eq(PropAsset::getPropId, sp.getPropId())
                    .eq(PropAsset::getIsActive, 1)
                    .last("LIMIT 1");
            PropAsset asset = propAssetMapper.selectOne(assetWrapper);
            if (asset != null) {
                propInfo.setPropName(asset.getFileName());
                propInfo.setAssetUrl(asset.getFilePath());
            }

            props.add(propInfo);
        }
        vo.setProps(props);

        return vo;
    }
}
