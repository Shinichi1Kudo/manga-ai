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
import com.manga.ai.prop.entity.Prop;
import com.manga.ai.prop.entity.PropAsset;
import com.manga.ai.prop.mapper.PropAssetMapper;
import com.manga.ai.prop.mapper.PropMapper;
import com.manga.ai.role.entity.Role;
import com.manga.ai.role.mapper.RoleMapper;
import com.manga.ai.scene.entity.Scene;
import com.manga.ai.scene.entity.SceneAsset;
import com.manga.ai.scene.mapper.SceneAssetMapper;
import com.manga.ai.scene.mapper.SceneMapper;
import com.manga.ai.shot.dto.ReferenceImageDTO;
import com.manga.ai.shot.dto.ShotDetailVO;
import com.manga.ai.shot.dto.ShotReviewRequest;
import com.manga.ai.shot.dto.ShotUpdateRequest;
import com.manga.ai.shot.entity.Shot;
import com.manga.ai.shot.entity.ShotCharacter;
import com.manga.ai.shot.entity.ShotProp;
import com.manga.ai.shot.entity.ShotReferenceImage;
import com.manga.ai.shot.entity.VideoMetadata;
import com.manga.ai.shot.mapper.ShotCharacterMapper;
import com.manga.ai.shot.mapper.ShotMapper;
import com.manga.ai.shot.mapper.ShotPropMapper;
import com.manga.ai.shot.mapper.ShotReferenceImageMapper;
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
    private final SceneAssetMapper sceneAssetMapper;
    private final RoleMapper roleMapper;
    private final RoleAssetMapper roleAssetMapper;
    private final PropAssetMapper propAssetMapper;
    private final PropMapper propMapper;
    private final EpisodeMapper episodeMapper;
    private final VideoMetadataMapper videoMetadataMapper;
    private final SeedanceService seedanceService;
    private final ShotReferenceImageMapper shotReferenceImageMapper;

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
        if (request.getStartTime() != null) {
            shot.setStartTime(request.getStartTime());
        }
        if (request.getEndTime() != null) {
            shot.setEndTime(request.getEndTime());
        }
        if (request.getDuration() != null) {
            shot.setDuration(Math.min(request.getDuration(), 15));  // 最大15秒
        }
        if (request.getShotType() != null) {
            shot.setShotType(request.getShotType());
        }
        if (request.getCameraAngle() != null) {
            shot.setCameraAngle(request.getCameraAngle());
        }
        if (request.getCameraMovement() != null) {
            shot.setCameraMovement(request.getCameraMovement());
        }
        if (request.getSoundEffect() != null) {
            shot.setSoundEffect(request.getSoundEffect());
        }
        if (request.getSceneName() != null) {
            shot.setSceneName(request.getSceneName());
        }
        if (request.getUserPrompt() != null) {
            shot.setUserPrompt(request.getUserPrompt());
        }
        if (request.getGenerationStatus() != null) {
            shot.setGenerationStatus(request.getGenerationStatus());
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

            if ("completed".equals(response.getStatus()) || "succeeded".equals(response.getStatus())) {
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

    @Override
    public List<ReferenceImageDTO> getReferenceImages(Long shotId) {
        LambdaQueryWrapper<ShotReferenceImage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ShotReferenceImage::getShotId, shotId)
                .orderByAsc(ShotReferenceImage::getDisplayOrder);
        List<ShotReferenceImage> images = shotReferenceImageMapper.selectList(wrapper);

        return images.stream().map(this::convertToReferenceImageDTO).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateReferenceImages(Long shotId, List<ReferenceImageDTO> referenceImages) {
        // 删除旧的参考图
        LambdaQueryWrapper<ShotReferenceImage> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(ShotReferenceImage::getShotId, shotId);
        shotReferenceImageMapper.delete(deleteWrapper);

        // 保存新的参考图
        if (referenceImages != null && !referenceImages.isEmpty()) {
            for (int i = 0; i < referenceImages.size(); i++) {
                ReferenceImageDTO dto = referenceImages.get(i);
                ShotReferenceImage entity = new ShotReferenceImage();
                entity.setShotId(shotId);
                entity.setImageType(dto.getImageType());
                entity.setReferenceId(dto.getReferenceId());
                entity.setReferenceName(dto.getReferenceName());
                entity.setImageUrl(dto.getImageUrl());
                entity.setDisplayOrder(i);
                entity.setIsUserAdded(dto.getIsUserAdded() != null && dto.getIsUserAdded() ? 1 : 0);
                entity.setCreatedAt(LocalDateTime.now());
                entity.setUpdatedAt(LocalDateTime.now());
                shotReferenceImageMapper.insert(entity);
            }
        }

        log.info("更新分镜参考图: shotId={}, count={}", shotId, referenceImages != null ? referenceImages.size() : 0);
    }

    @Override
    public List<ReferenceImageDTO> matchAssetsFromDescription(Long shotId) {
        Shot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            throw new BusinessException("分镜不存在");
        }

        String description = shot.getDescription();
        if (description == null || description.isEmpty()) {
            return List.of();
        }

        List<ReferenceImageDTO> matchedAssets = new ArrayList<>();
        int order = 0;

        // 1. 匹配场景
        if (shot.getSceneId() != null) {
            Scene scene = sceneMapper.selectById(shot.getSceneId());
            if (scene != null && description.contains(scene.getSceneName())) {
                // 获取场景激活资产
                com.manga.ai.scene.entity.SceneAsset sceneAsset = getActiveSceneAsset(scene.getId());
                if (sceneAsset != null && sceneAsset.getFilePath() != null) {
                    ReferenceImageDTO dto = new ReferenceImageDTO();
                    dto.setImageType("scene");
                    dto.setReferenceId(scene.getId());
                    dto.setReferenceName(scene.getSceneName());
                    dto.setImageUrl(sceneAsset.getFilePath());
                    dto.setDisplayOrder(order++);
                    dto.setIsUserAdded(false);
                    matchedAssets.add(dto);
                }
            }
        }

        // 2. 匹配角色
        LambdaQueryWrapper<ShotCharacter> scWrapper = new LambdaQueryWrapper<>();
        scWrapper.eq(ShotCharacter::getShotId, shotId);
        List<ShotCharacter> shotCharacters = shotCharacterMapper.selectList(scWrapper);

        for (ShotCharacter sc : shotCharacters) {
            Role role = roleMapper.selectById(sc.getRoleId());
            if (role != null && description.contains(role.getRoleName())) {
                RoleAsset roleAsset = getActiveRoleAsset(sc.getRoleId(), sc.getClothingId());
                if (roleAsset != null && roleAsset.getFilePath() != null) {
                    ReferenceImageDTO dto = new ReferenceImageDTO();
                    dto.setImageType("role");
                    dto.setReferenceId(role.getId());
                    dto.setReferenceName(role.getRoleName());
                    dto.setImageUrl(roleAsset.getFilePath());
                    dto.setDisplayOrder(order++);
                    dto.setIsUserAdded(false);
                    matchedAssets.add(dto);
                }
            }
        }

        // 3. 匹配道具
        LambdaQueryWrapper<ShotProp> spWrapper = new LambdaQueryWrapper<>();
        spWrapper.eq(ShotProp::getShotId, shotId);
        List<ShotProp> shotProps = shotPropMapper.selectList(spWrapper);

        for (ShotProp sp : shotProps) {
            PropAsset propAsset = getActivePropAsset(sp.getPropId());
            if (propAsset != null && propAsset.getFilePath() != null) {
                // 使用道具文件名或道具ID进行匹配
                boolean matched = false;
                if (propAsset.getFileName() != null && description.contains(propAsset.getFileName())) {
                    matched = true;
                }

                if (matched) {
                    ReferenceImageDTO dto = new ReferenceImageDTO();
                    dto.setImageType("prop");
                    dto.setReferenceId(sp.getPropId());
                    dto.setReferenceName(propAsset.getFileName());
                    dto.setImageUrl(propAsset.getFilePath());
                    dto.setDisplayOrder(order++);
                    dto.setIsUserAdded(false);
                    matchedAssets.add(dto);
                }
            }
        }

        log.info("自动匹配分镜资产: shotId={}, matched={}", shotId, matchedAssets.size());
        return matchedAssets;
    }

    @Override
    @Async("videoGenerateExecutor")
    public void generateVideoWithReferences(Long shotId) {
        log.info("开始生成视频(带参考图): shotId={}", shotId);
        long startTime = System.currentTimeMillis();

        Shot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            log.error("分镜不存在: shotId={}", shotId);
            return;
        }

        try {
            // 更新状态为生成中，清除之前的错误信息，记录开始时间
            shot.setGenerationStatus(ShotGenerationStatus.GENERATING.getCode());
            shot.setGenerationError(null);
            shot.setGenerationStartTime(LocalDateTime.now());
            shot.setUpdatedAt(LocalDateTime.now());
            shotMapper.updateById(shot);

            // 获取 seriesId
            Episode episode = episodeMapper.selectById(shot.getEpisodeId());
            Long seriesId = episode != null ? episode.getSeriesId() : null;

            // 构建提示词和参考图
            PromptWithReferences result = buildPromptFromSceneAndDescription(shot, seriesId);

            log.info("构建的提示词: {}", result.getPrompt());
            log.info("原始参考图数量: {}", result.getReferenceImages().size());

            // 去重：基于图片URL去重
            List<AssetReference> references = new ArrayList<>();
            Set<String> seenUrls = new HashSet<>();
            for (AssetReference ref : result.getReferenceImages()) {
                if (ref.getImageUrl() != null && !seenUrls.contains(ref.getImageUrl())) {
                    seenUrls.add(ref.getImageUrl());
                    references.add(ref);
                }
            }
            log.info("去重后参考图数量: {}", references.size());

            // 参考图数量限制检查（最多9张）
            if (references.size() > 9) {
                throw new BusinessException("参考图数量超过9张（当前" + references.size() + "张），请删减后重试");
            }

            for (AssetReference ref : references) {
                log.info("参考图: type={}, name={}, url={}", ref.getType(), ref.getName(), ref.getImageUrl());
            }

            // 创建生成请求
            SeedanceRequest request = new SeedanceRequest();
            request.setPrompt(result.getPrompt());
            request.setDuration(shot.getDuration() != null ? shot.getDuration() : 5);
            request.setShotId(shotId);

            // 构建参考图列表
            if (!references.isEmpty()) {
                List<SeedanceRequest.ReferenceContent> contents = new ArrayList<>();
                for (AssetReference ref : references) {
                    SeedanceRequest.ReferenceContent content = new SeedanceRequest.ReferenceContent();
                    content.setType("image_url");
                    content.setRole("reference_image");

                    SeedanceRequest.ImageUrl imageUrl = new SeedanceRequest.ImageUrl();
                    imageUrl.setUrl(ref.getImageUrl());
                    content.setImageUrl(imageUrl);

                    contents.add(content);
                }
                request.setContents(contents);
            }

            // 调用 Seedance 生成视频
            SeedanceResponse response = seedanceService.generateVideo(request);

            if ("completed".equals(response.getStatus()) || "succeeded".equals(response.getStatus())) {
                // 计算生成耗时
                int durationSeconds = (int) ((System.currentTimeMillis() - startTime) / 1000);
                log.info("视频生成耗时: {}秒 (约{}分{}秒)", durationSeconds, durationSeconds / 60, durationSeconds % 60);

                // 保存视频URL
                shot.setVideoUrl(response.getVideoUrl());
                shot.setThumbnailUrl(response.getThumbnailUrl());
                shot.setVideoSeed(response.getSeed());
                shot.setGenerationStatus(ShotGenerationStatus.COMPLETED.getCode());
                shot.setGenerationDuration(durationSeconds);
                shot.setUpdatedAt(LocalDateTime.now());
                shotMapper.updateById(shot);

                // 保存元数据
                saveVideoMetadata(shot, result.getPrompt(), response);

                log.info("视频生成完成(带参考图): shotId={}, 耗时: {}分{}秒", shotId, durationSeconds / 60, durationSeconds % 60);
            } else {
                throw new RuntimeException("视频生成失败: " + response.getErrorMessage());
            }
        } catch (Exception e) {
            log.error("视频生成异常(带参考图): shotId={}", shotId, e);
            shot.setGenerationStatus(ShotGenerationStatus.FAILED.getCode());
            // 提取关键错误信息
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.contains("ModelNotOpen")) {
                shot.setGenerationError("模型未开通，请在火山引擎控制台开通 doubao-seedance-2-0-260128 模型");
            } else if (errorMsg != null && errorMsg.length() > 200) {
                shot.setGenerationError(errorMsg.substring(0, 200));
            } else {
                shot.setGenerationError(errorMsg);
            }
            shot.setUpdatedAt(LocalDateTime.now());
            shotMapper.updateById(shot);
        }
    }

    /**
     * 从场景和描述构建结构化提示词
     * 格式：场景：xxx，时间【00:00-00:08】 镜头【xxx】 剧情【xxx】 音效【xxx】
     * 同时解析 @{资产名称} 并转换为 [图N] 格式
     */
    private PromptWithReferences buildPromptFromSceneAndDescription(Shot shot, Long seriesId) {
        PromptWithReferences result = new PromptWithReferences();
        List<AssetReference> references = new ArrayList<>();
        StringBuilder promptBuilder = new StringBuilder();

        // 1. 场景 - 解析 @{资产名称} 并添加参考图
        String sceneName = shot.getSceneName();
        if (sceneName != null && !sceneName.isEmpty()) {
            String processedSceneName = parseSceneMentions(sceneName, seriesId, references);
            promptBuilder.append("场景：").append(processedSceneName);
        }

        // 2. 时间
        String timeRange = formatTimeRange(shot.getStartTime(), shot.getEndTime());
        promptBuilder.append("，时间【").append(timeRange).append("】");

        // 3. 镜头
        String shotType = shot.getShotType();
        if (shotType != null && !shotType.isEmpty()) {
            promptBuilder.append(" 镜头【").append(shotType).append("】");
        }

        // 4. 剧情 - 解析资产引用
        String description = shot.getDescription();
        if (description != null && !description.isEmpty()) {
            String processedDesc = parseAssetMentionsAndAutoMatch(description, seriesId, references);
            promptBuilder.append(" 剧情【").append(processedDesc).append("】");
        }

        // 5. 音效
        String soundEffect = shot.getSoundEffect();
        if (soundEffect != null && !soundEffect.isEmpty()) {
            promptBuilder.append(" 音效【").append(soundEffect).append("】");
        }

        result.setPrompt(promptBuilder.toString());
        result.setReferenceImages(references);

        log.info("构建的提示词: {}", result.getPrompt());
        log.info("参考图数量: {}", references.size());
        for (int i = 0; i < references.size(); i++) {
            AssetReference ref = references.get(i);
            log.info("  [图{}] {} - {}", i + 1, ref.getType(), ref.getName());
        }

        return result;
    }

    /**
     * 解析场景名称中的 @{资产名称} 并转换为 [图N] 格式
     */
    private String parseSceneMentions(String sceneName, Long seriesId, List<AssetReference> references) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("@\\{([^}]+)\\}");
        java.util.regex.Matcher matcher = pattern.matcher(sceneName);

        StringBuffer result = new StringBuffer();
        Set<String> matchedAssets = new HashSet<>();

        while (matcher.find()) {
            String assetName = matcher.group(1);
            // 检查是否已匹配过（去重）
            if (matchedAssets.contains(assetName)) {
                int existingIndex = findAssetIndex(references, assetName);
                if (existingIndex > 0) {
                    matcher.appendReplacement(result, "[图" + existingIndex + "]");
                } else {
                    matcher.appendReplacement(result, assetName);
                }
                continue;
            }

            AssetReference assetRef = findAssetByName(assetName, seriesId);
            if (assetRef != null) {
                int imageIndex = references.size() + 1;
                references.add(assetRef);
                matchedAssets.add(assetName);
                matcher.appendReplacement(result, "[图" + imageIndex + "]");
            } else {
                matcher.appendReplacement(result, assetName);
            }
        }
        matcher.appendTail(result);

        String processed = result.toString().trim();
        // 如果没有找到 @{} 格式，尝试按场景名称匹配
        if (references.isEmpty() && seriesId != null) {
            AssetReference sceneRef = findAssetByName(sceneName.trim(), seriesId);
            if (sceneRef != null) {
                references.add(sceneRef);
                // 场景名称不需要替换，保持原样
            }
        }

        return processed;
    }

    /**
     * 格式化时间范围
     */
    private String formatTimeRange(Integer startTime, Integer endTime) {
        String start = formatTime(startTime != null ? startTime : 0);
        String end = formatTime(endTime != null ? endTime : 0);
        return start + "-" + end;
    }

    /**
     * 格式化秒数为 mm:ss 格式
     */
    private String formatTime(int seconds) {
        int minutes = seconds / 60;
        int secs = seconds % 60;
        return String.format("%02d:%02d", minutes, secs);
    }

    /**
     * 解析文本中的 @{资产名称} 并转换为 [图N] 格式
     * 同时自动匹配文本中出现的资产名称
     */
    private String parseAssetMentionsAndAutoMatch(String text, Long seriesId, List<AssetReference> references) {
        // 先处理 @{资产名称} 格式
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("@\\{([^}]+)\\}");
        java.util.regex.Matcher matcher = pattern.matcher(text);

        StringBuffer result = new StringBuffer();
        Set<String> matchedAssets = new HashSet<>(); // 已匹配的资产名称

        while (matcher.find()) {
            String assetName = matcher.group(1);
            // 检查是否已匹配过（去重）
            if (matchedAssets.contains(assetName)) {
                // 已匹配过，用已有索引
                int existingIndex = findAssetIndex(references, assetName);
                if (existingIndex > 0) {
                    matcher.appendReplacement(result, "[图" + existingIndex + "]");
                } else {
                    matcher.appendReplacement(result, assetName);
                }
                continue;
            }

            AssetReference assetRef = findAssetByName(assetName, seriesId);
            if (assetRef != null) {
                int imageIndex = references.size() + 1;
                references.add(assetRef);
                matchedAssets.add(assetName);
                matcher.appendReplacement(result, "[图" + imageIndex + "]");
            } else {
                matcher.appendReplacement(result, assetName);
            }
        }
        matcher.appendTail(result);
        String processedText = result.toString();

        // 如果没有找到 @{} 格式，尝试自动匹配资产名称
        if (references.isEmpty() && seriesId != null) {
            processedText = autoMatchAssetsInText(processedText, seriesId, references);
        }

        return processedText;
    }

    /**
     * 查找资产在列表中的索引
     */
    private int findAssetIndex(List<AssetReference> references, String assetName) {
        for (int i = 0; i < references.size(); i++) {
            if (references.get(i).getName().equals(assetName)) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * 自动匹配文本中出现的资产名称
     */
    private String autoMatchAssetsInText(String text, Long seriesId, List<AssetReference> references) {
        // 获取该系列所有资产
        List<AssetReference> allAssets = new ArrayList<>();

        // 场景
        LambdaQueryWrapper<Scene> sceneWrapper = new LambdaQueryWrapper<>();
        sceneWrapper.eq(Scene::getSeriesId, seriesId);
        List<Scene> scenes = sceneMapper.selectList(sceneWrapper);
        for (Scene scene : scenes) {
            SceneAsset asset = getActiveSceneAsset(scene.getId());
            if (asset != null && asset.getFilePath() != null) {
                AssetReference ref = new AssetReference();
                ref.setType("scene");
                ref.setName(scene.getSceneName());
                ref.setImageUrl(asset.getFilePath());
                allAssets.add(ref);
            }
        }

        // 角色
        LambdaQueryWrapper<Role> roleWrapper = new LambdaQueryWrapper<>();
        roleWrapper.eq(Role::getSeriesId, seriesId);
        List<Role> roles = roleMapper.selectList(roleWrapper);
        for (Role role : roles) {
            RoleAsset asset = getActiveRoleAsset(role.getId(), null);
            if (asset != null && asset.getFilePath() != null) {
                AssetReference ref = new AssetReference();
                ref.setType("role");
                ref.setName(role.getRoleName());
                ref.setImageUrl(asset.getFilePath());
                allAssets.add(ref);
            }
        }

        // 道具
        LambdaQueryWrapper<Prop> propWrapper = new LambdaQueryWrapper<>();
        propWrapper.eq(Prop::getSeriesId, seriesId);
        List<Prop> props = propMapper.selectList(propWrapper);
        for (Prop prop : props) {
            PropAsset asset = getActivePropAsset(prop.getId());
            if (asset != null && asset.getFilePath() != null) {
                AssetReference ref = new AssetReference();
                ref.setType("prop");
                ref.setName(prop.getPropName());
                ref.setImageUrl(asset.getFilePath());
                allAssets.add(ref);
            }
        }

        // 按名称长度降序排序，优先匹配长名称
        allAssets.sort((a, b) -> b.getName().length() - a.getName().length());

        // 在文本中查找资产名称并替换
        String result = text;
        Set<String> matched = new HashSet<>();
        for (AssetReference asset : allAssets) {
            if (!matched.contains(asset.getName()) && result.contains(asset.getName())) {
                int imageIndex = references.size() + 1;
                references.add(asset);
                matched.add(asset.getName());
                result = result.replace(asset.getName(), "[图" + imageIndex + "]");
            }
        }

        return result;
    }

    /**
     * 根据名称查找资产
     */
    private AssetReference findAssetByName(String name, Long seriesId) {
        log.info("查找资产: name={}, seriesId={}", name, seriesId);

        // 1. 查找场景
        if (seriesId != null) {
            LambdaQueryWrapper<Scene> sceneWrapper = new LambdaQueryWrapper<>();
            sceneWrapper.eq(Scene::getSeriesId, seriesId)
                    .eq(Scene::getSceneName, name);
            Scene scene = sceneMapper.selectOne(sceneWrapper);
            if (scene != null) {
                SceneAsset asset = getActiveSceneAsset(scene.getId());
                if (asset != null && asset.getFilePath() != null) {
                    log.info("找到场景资产: name={}, url={}", name, asset.getFilePath());
                    AssetReference ref = new AssetReference();
                    ref.setType("scene");
                    ref.setName(name);
                    ref.setImageUrl(asset.getFilePath());
                    return ref;
                }
            }
        }

        // 2. 查找角色
        if (seriesId != null) {
            LambdaQueryWrapper<Role> roleWrapper = new LambdaQueryWrapper<>();
            roleWrapper.eq(Role::getSeriesId, seriesId)
                    .eq(Role::getRoleName, name);
            Role role = roleMapper.selectOne(roleWrapper);
            if (role != null) {
                RoleAsset asset = getActiveRoleAsset(role.getId(), null);
                log.info("找到角色: name={}, roleId={}, asset={}", name, role.getId(), asset != null ? asset.getFilePath() : "null");
                if (asset != null && asset.getFilePath() != null) {
                    AssetReference ref = new AssetReference();
                    ref.setType("role");
                    ref.setName(name);
                    ref.setImageUrl(asset.getFilePath());
                    return ref;
                }
            } else {
                log.info("未找到角色: name={}", name);
            }
        }

        // 3. 查找道具
        if (seriesId != null) {
            LambdaQueryWrapper<Prop> propWrapper = new LambdaQueryWrapper<>();
            propWrapper.eq(Prop::getSeriesId, seriesId)
                    .eq(Prop::getPropName, name);
            Prop prop = propMapper.selectOne(propWrapper);
            if (prop != null) {
                PropAsset asset = getActivePropAsset(prop.getId());
                if (asset != null && asset.getFilePath() != null) {
                    AssetReference ref = new AssetReference();
                    ref.setType("prop");
                    ref.setName(name);
                    ref.setImageUrl(asset.getFilePath());
                    return ref;
                }
            }
        }

        return null;
    }

    /**
     * 提示词和参考图结果
     */
    private static class PromptWithReferences {
        private String prompt;
        private List<AssetReference> referenceImages;

        public String getPrompt() { return prompt; }
        public void setPrompt(String prompt) { this.prompt = prompt; }
        public List<AssetReference> getReferenceImages() { return referenceImages; }
        public void setReferenceImages(List<AssetReference> referenceImages) { this.referenceImages = referenceImages; }
    }

    /**
     * 资产引用
     */
    private static class AssetReference {
        private String type;
        private String name;
        private String imageUrl;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getImageUrl() { return imageUrl; }
        public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    }

    /**
     * 获取场景激活资产
     */
    private SceneAsset getActiveSceneAsset(Long sceneId) {
        LambdaQueryWrapper<SceneAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SceneAsset::getSceneId, sceneId)
                .eq(SceneAsset::getIsActive, 1)
                .last("LIMIT 1");
        return sceneAssetMapper.selectOne(wrapper);
    }

    /**
     * 获取角色激活资产
     * 如果未指定服装ID，使用默认服装(clothingId=1)
     */
    private RoleAsset getActiveRoleAsset(Long roleId, Integer clothingId) {
        // 如果未指定服装，使用默认服装
        if (clothingId == null) {
            clothingId = 1;
        }

        LambdaQueryWrapper<RoleAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RoleAsset::getRoleId, roleId)
                .eq(RoleAsset::getClothingId, clothingId)
                .eq(RoleAsset::getIsActive, 1)
                .last("LIMIT 1");
        return roleAssetMapper.selectOne(wrapper);
    }

    /**
     * 获取道具激活资产
     */
    private PropAsset getActivePropAsset(Long propId) {
        LambdaQueryWrapper<PropAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PropAsset::getPropId, propId)
                .eq(PropAsset::getIsActive, 1)
                .last("LIMIT 1");
        return propAssetMapper.selectOne(wrapper);
    }

    /**
     * 转换为 ReferenceImageDTO
     */
    private ReferenceImageDTO convertToReferenceImageDTO(ShotReferenceImage entity) {
        ReferenceImageDTO dto = new ReferenceImageDTO();
        dto.setId(entity.getId());
        dto.setImageType(entity.getImageType());
        dto.setReferenceId(entity.getReferenceId());
        dto.setReferenceName(entity.getReferenceName());
        dto.setImageUrl(entity.getImageUrl());
        dto.setDisplayOrder(entity.getDisplayOrder());
        dto.setIsUserAdded(entity.getIsUserAdded() != null && entity.getIsUserAdded() == 1);
        return dto;
    }
}
