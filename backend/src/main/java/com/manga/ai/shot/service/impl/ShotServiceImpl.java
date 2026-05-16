package com.manga.ai.shot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manga.ai.asset.entity.RoleAsset;
import com.manga.ai.asset.mapper.RoleAssetMapper;
import com.manga.ai.common.constants.CreditConstants;
import com.manga.ai.common.enums.CreditUsageType;
import com.manga.ai.common.enums.PropStatus;
import com.manga.ai.common.enums.ShotGenerationStatus;
import com.manga.ai.common.enums.ShotStatus;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.common.service.OssService;
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
import com.manga.ai.series.entity.Series;
import com.manga.ai.series.mapper.SeriesMapper;
import com.manga.ai.shot.dto.ReferenceImageDTO;
import com.manga.ai.shot.dto.ShotDetailVO;
import com.manga.ai.shot.dto.ShotReviewRequest;
import com.manga.ai.shot.dto.ShotUpdateRequest;
import com.manga.ai.shot.dto.ShotVideoAssetVO;
import com.manga.ai.shot.entity.Shot;
import com.manga.ai.shot.entity.ShotCharacter;
import com.manga.ai.shot.entity.ShotProp;
import com.manga.ai.shot.entity.ShotReferenceImage;
import com.manga.ai.shot.entity.ShotVideoAsset;
import com.manga.ai.shot.entity.ShotVideoAssetMetadata;
import com.manga.ai.shot.entity.VideoMetadata;
import com.manga.ai.shot.mapper.ShotCharacterMapper;
import com.manga.ai.shot.mapper.ShotMapper;
import com.manga.ai.shot.mapper.ShotPropMapper;
import com.manga.ai.shot.mapper.ShotReferenceImageMapper;
import com.manga.ai.shot.mapper.ShotVideoAssetMapper;
import com.manga.ai.shot.mapper.ShotVideoAssetMetadataMapper;
import com.manga.ai.shot.mapper.VideoMetadataMapper;
import com.manga.ai.shot.service.ShotService;
import com.manga.ai.user.service.UserService;
import com.manga.ai.user.service.impl.UserServiceImpl.UserContextHolder;
import com.manga.ai.video.dto.SeedanceRequest;
import com.manga.ai.video.dto.SeedanceResponse;
import com.manga.ai.video.service.SeedanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 分镜服务实现
 */
@Slf4j
@Service
public class ShotServiceImpl implements ShotService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> PROP_JSON_TYPE = new TypeReference<>() {};
    private static final List<String> ALLOWED_UPLOAD_VIDEO_TYPES = Arrays.asList(
            "video/mp4", "video/webm", "video/quicktime", "video/x-m4v"
    );
    private static final List<String> ALLOWED_SHOT_ASPECT_RATIOS = Arrays.asList(
            "16:9", "4:3", "1:1", "3:4", "9:16", "21:9"
    );
    private static final long MAX_UPLOAD_VIDEO_SIZE = 50 * 1024 * 1024;
    private static final String VIDEO_SOURCE_MANUAL = "manual";
    private static final String VIDEO_SOURCE_SYSTEM = "system";
    private static final String VIDEO_MODEL_MANUAL_UPLOAD = "manual-upload";

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
    private final SeriesMapper seriesMapper;
    private final VideoMetadataMapper videoMetadataMapper;
    private final SeedanceService seedanceService;
    private final ShotReferenceImageMapper shotReferenceImageMapper;
    private final ShotVideoAssetMapper shotVideoAssetMapper;
    private final ShotVideoAssetMetadataMapper shotVideoAssetMetadataMapper;
    private final OssService ossService;
    private final UserService userService;

    // 自注入代理，用于正确调用 @Async 方法
    private final ShotService self;

    public ShotServiceImpl(ShotMapper shotMapper,
                          ShotCharacterMapper shotCharacterMapper,
                          ShotPropMapper shotPropMapper,
                          SceneMapper sceneMapper,
                          SceneAssetMapper sceneAssetMapper,
                          RoleMapper roleMapper,
                          RoleAssetMapper roleAssetMapper,
                          PropAssetMapper propAssetMapper,
                          PropMapper propMapper,
                          EpisodeMapper episodeMapper,
                          SeriesMapper seriesMapper,
                          VideoMetadataMapper videoMetadataMapper,
                          SeedanceService seedanceService,
                          ShotReferenceImageMapper shotReferenceImageMapper,
                          ShotVideoAssetMapper shotVideoAssetMapper,
                          ShotVideoAssetMetadataMapper shotVideoAssetMetadataMapper,
                          OssService ossService,
                          UserService userService,
                          @Lazy ShotService self) {
        this.shotMapper = shotMapper;
        this.shotCharacterMapper = shotCharacterMapper;
        this.shotPropMapper = shotPropMapper;
        this.sceneMapper = sceneMapper;
        this.sceneAssetMapper = sceneAssetMapper;
        this.roleMapper = roleMapper;
        this.roleAssetMapper = roleAssetMapper;
        this.propAssetMapper = propAssetMapper;
        this.propMapper = propMapper;
        this.episodeMapper = episodeMapper;
        this.seriesMapper = seriesMapper;
        this.videoMetadataMapper = videoMetadataMapper;
        this.seedanceService = seedanceService;
        this.shotReferenceImageMapper = shotReferenceImageMapper;
        this.shotVideoAssetMapper = shotVideoAssetMapper;
        this.shotVideoAssetMetadataMapper = shotVideoAssetMetadataMapper;
        this.ossService = ossService;
        this.userService = userService;
        this.self = self;
    }

    /**
     * 根据分辨率和比例计算视频尺寸
     * @param resolution 分辨率: 480p, 720p, 1080p (仅VIP模型支持)
     * @param aspectRatio 比例: 16:9, 4:3, 1:1, 3:4, 9:16, 21:9
     * @return [width, height]
     */
    private int[] calculateVideoSize(String resolution, String aspectRatio) {
        // 默认值
        int width = 1280;
        int height = 720;

        if (resolution == null) resolution = "720p";
        if (aspectRatio == null) aspectRatio = "16:9";

        // 分辨率映射
        if ("480p".equals(resolution)) {
            switch (aspectRatio) {
                case "16:9": width = 864; height = 480; break;
                case "4:3":  width = 736; height = 544; break;
                case "1:1":  width = 640; height = 640; break;
                case "3:4":  width = 544; height = 736; break;
                case "9:16": width = 480; height = 864; break;
                case "21:9": width = 960; height = 416; break;
                default:     width = 864; height = 480; break;
            }
        } else if ("1080p".equals(resolution)) {
            // 1080p (4K) - 仅 Seedance 2.0 VIP 支持
            switch (aspectRatio) {
                case "16:9": width = 1920; height = 1080; break;
                case "4:3":  width = 1664; height = 1248; break;
                case "1:1":  width = 1440; height = 1440; break;
                case "3:4":  width = 1248; height = 1664; break;
                case "9:16": width = 1080; height = 1920; break;
                case "21:9": width = 2206; height = 946; break;
                default:     width = 1920; height = 1080; break;
            }
        } else { // 720p
            switch (aspectRatio) {
                case "16:9": width = 1280; height = 720; break;
                case "4:3":  width = 1112; height = 834; break;
                case "1:1":  width = 960;  height = 960; break;
                case "3:4":  width = 834;  height = 1112; break;
                case "9:16": width = 720;  height = 1280; break;
                case "21:9": width = 1470; height = 630; break;
                default:     width = 1280; height = 720; break;
            }
        }

        return new int[]{width, height};
    }

    /**
     * 将前端模型标识转换为API模型名称
     * @param videoModel 前端模型标识 (seedance-2.0-fast, seedance-2.0, kling-v3-omni)
     * @return API模型名称
     */
    private String convertToApiModel(String videoModel) {
        return convertToApiModel(videoModel, null);
    }

    private String convertToApiModel(String videoModel, String resolution) {
        if (videoModel == null || videoModel.isEmpty()) {
            return "doubao-seedance-2-0-fast-260128"; // 默认 Fast 模型
        }
        switch (videoModel) {
            case "seedance-2.0":
            case "doubao-seedance-2-0-260128":
                if ("1080p".equals(resolution)) {
                    return "seedance-2";
                }
                return "doubao-seedance-2-0-260128"; // VIP 模型
            case "seedance-2":
                return "seedance-2";
            case "doubao-seedance-2-0":
                return "doubao-seedance-2-0";
            case "kling-v3-omni":
                return "kling-v3-omni"; // Kling v3 Omni 模型
            case "seedance-2.0-fast":
            case "doubao-seedance-2-0-fast-260128":
            default:
                return "doubao-seedance-2-0-fast-260128"; // Fast VIP 模型
        }
    }

    private String normalizeResolutionForModel(String videoModel, String resolution) {
        if ("kling-v3-omni".equals(videoModel) && "480p".equals(resolution)) {
            return "720p";
        }
        return resolution;
    }

    /**
     * 获取分镜所属用户的ID（用于异步方法中获取用户）
     */
    private Long getUserIdForShot(Shot shot) {
        if (shot.getEpisodeId() == null) {
            return null;
        }
        Episode episode = episodeMapper.selectById(shot.getEpisodeId());
        if (episode == null || episode.getSeriesId() == null) {
            return null;
        }
        Series series = seriesMapper.selectById(episode.getSeriesId());
        return series != null ? series.getUserId() : null;
    }

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
                .orderByAsc(Shot::getStatus)
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

        // 批量查询场景资产
        Map<Long, SceneAsset> sceneAssetMap = new HashMap<>();
        if (!sceneIds.isEmpty()) {
            LambdaQueryWrapper<SceneAsset> saWrapper = new LambdaQueryWrapper<>();
            saWrapper.in(SceneAsset::getSceneId, sceneIds)
                    .eq(SceneAsset::getIsActive, 1);
            List<SceneAsset> sceneAssets = sceneAssetMapper.selectList(saWrapper);
            sceneAssetMap = sceneAssets.stream().collect(Collectors.toMap(SceneAsset::getSceneId, a -> a, (a, b) -> a));
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

        // 按shotId分组道具
        Map<Long, List<ShotProp>> propsByShotId = allShotProps.stream()
                .collect(Collectors.groupingBy(ShotProp::getShotId));

        // 查询该系列所有道具，建立道具名称到资产的映射（用于解析 propsJson）
        Map<Long, String> propIdToNameMap = new HashMap<>();
        Map<Long, Prop> propByIdMap = new HashMap<>();
        Map<String, PropAsset> propNameToAssetMap = new HashMap<>();
        Map<Long, PropAsset> propAssetMap = new HashMap<>();
        if (!shots.isEmpty()) {
            Episode episode = shots.get(0).getEpisodeId() != null
                    ? episodeMapper.selectById(shots.get(0).getEpisodeId())
                    : null;
            Long seriesId = episode != null ? episode.getSeriesId() : null;
            if (seriesId != null) {
                // 查询该系列所有道具
                LambdaQueryWrapper<Prop> propQueryWrapper = new LambdaQueryWrapper<>();
                propQueryWrapper.eq(Prop::getSeriesId, seriesId);
                List<Prop> allSeriesProps = propMapper.selectList(propQueryWrapper);
                for (Prop prop : allSeriesProps) {
                    if (prop.getId() != null && prop.getPropName() != null) {
                        propIdToNameMap.put(prop.getId(), prop.getPropName());
                        propByIdMap.put(prop.getId(), prop);
                    }
                }

                // 查询这些道具的资产：锁定道具使用系列激活版本，未锁定道具只使用当前剧集版本。
                if (!allSeriesProps.isEmpty()) {
                    List<Long> allPropIds = allSeriesProps.stream().map(Prop::getId).collect(Collectors.toList());
                    LambdaQueryWrapper<PropAsset> paQueryWrapper = new LambdaQueryWrapper<>();
                    paQueryWrapper.in(PropAsset::getPropId, allPropIds)
                            .orderByDesc(PropAsset::getIsActive)
                            .orderByDesc(PropAsset::getVersion)
                            .orderByDesc(PropAsset::getId);
                    List<PropAsset> allPropAssets = propAssetMapper.selectList(paQueryWrapper);

                    Map<Long, List<PropAsset>> assetsByPropId = allPropAssets.stream()
                            .collect(Collectors.groupingBy(PropAsset::getPropId));

                    // 建立道具名称到资产的映射
                    for (Prop prop : allSeriesProps) {
                        PropAsset asset = selectVisiblePropAsset(prop, assetsByPropId.getOrDefault(prop.getId(), List.of()), episodeId);
                        if (asset != null && prop.getPropName() != null) {
                            propAssetMap.put(prop.getId(), asset);
                            propNameToAssetMap.put(prop.getPropName(), asset);
                        }
                    }
                }
            }
        }

        // 最终的Map
        final Map<Long, Scene> finalSceneMap = sceneMap;
        final Map<Long, SceneAsset> finalSceneAssetMap = sceneAssetMap;
        final Map<Long, Role> finalRoleMap = roleMap;
        final Map<String, RoleAsset> finalAssetMap = assetMap;
        final Map<Long, PropAsset> finalPropAssetMap = propAssetMap;
        final Map<Long, List<ShotProp>> finalPropsByShotId = propsByShotId;
        final Map<Long, String> finalPropIdToNameMap = propIdToNameMap;
        final Map<String, PropAsset> finalPropNameToAssetMap = propNameToAssetMap;
        final Map<Long, ShotVideoAsset> finalActiveVideoAssetMap = loadActiveVideoAssetMap(shotIds);
        final Map<Long, ShotVideoAssetMetadata> finalActiveVideoMetadataMap = loadVideoMetadataMap(finalActiveVideoAssetMap.values());

        // 组装VO
        return shots.stream()
                .map(shot -> convertToDetailVOOptimized(shot, finalSceneMap, finalSceneAssetMap, charactersByShotId.getOrDefault(shot.getId(), List.of()), finalRoleMap, finalAssetMap, finalPropsByShotId.getOrDefault(shot.getId(), List.of()), finalPropAssetMap, finalPropIdToNameMap, finalPropNameToAssetMap, finalActiveVideoAssetMap, finalActiveVideoMetadataMap))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateShot(Long shotId, ShotUpdateRequest request) {
        if (request.getShotName() != null && isShotNameOnlyUpdate(request)) {
            int updated = shotMapper.updateShotName(shotId, normalizeShotName(request.getShotName()));
            if (updated == 0) {
                throw new BusinessException("分镜不存在");
            }
            log.info("更新分镜名称: shotId={}", shotId);
            return;
        }

        Shot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            throw new BusinessException("分镜不存在");
        }
        ensureShotCanBeModified(shot);

        LambdaUpdateWrapper<Shot> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Shot::getId, shotId);
        if (request.getDescription() != null) {
            updateWrapper.set(Shot::getDescription, request.getDescription());
        }
        if (request.getDescriptionEdited() != null) {
            updateWrapper.set(Shot::getDescriptionEdited, request.getDescriptionEdited());
        }
        if (request.getStartTime() != null) {
            updateWrapper.set(Shot::getStartTime, request.getStartTime());
        }
        if (request.getEndTime() != null) {
            updateWrapper.set(Shot::getEndTime, request.getEndTime());
        }
        if (request.getDuration() != null) {
            updateWrapper.set(Shot::getDuration, Math.min(request.getDuration(), 15));  // 最大15秒
        }
        String nextVideoModel = request.getVideoModel() != null ? request.getVideoModel() : shot.getVideoModel();
        String nextResolution = request.getResolution() != null ? request.getResolution() : shot.getResolution();
        nextResolution = normalizeResolutionForModel(nextVideoModel, nextResolution);
        if (request.getResolution() != null || (request.getVideoModel() != null && !Objects.equals(nextResolution, shot.getResolution()))) {
            updateWrapper.set(Shot::getResolution, nextResolution);
        }
        if (request.getAspectRatio() != null) {
            updateWrapper.set(Shot::getAspectRatio, request.getAspectRatio());
        }
        if (request.getShotType() != null) {
            updateWrapper.set(Shot::getShotType, request.getShotType());
        }
        if (request.getCameraAngle() != null) {
            updateWrapper.set(Shot::getCameraAngle, request.getCameraAngle());
        }
        if (request.getCameraMovement() != null) {
            updateWrapper.set(Shot::getCameraMovement, request.getCameraMovement());
        }
        if (request.getSoundEffect() != null) {
            updateWrapper.set(Shot::getSoundEffect, request.getSoundEffect());
        }
        if (request.getShotName() != null) {
            updateWrapper.set(Shot::getShotName, normalizeShotName(request.getShotName()));
        }
        if (request.getSceneName() != null) {
            updateWrapper.set(Shot::getSceneName, request.getSceneName());
        }
        if (request.getSceneEdited() != null) {
            updateWrapper.set(Shot::getSceneEdited, request.getSceneEdited());
        }
        if (request.getUserPrompt() != null) {
            updateWrapper.set(Shot::getUserPrompt, request.getUserPrompt());
        }
        if (request.getVideoModel() != null) {
            updateWrapper.set(Shot::getVideoModel, request.getVideoModel());
        }
        updateWrapper.set(Shot::getUpdatedAt, LocalDateTime.now());
        shotMapper.update(null, updateWrapper);
        log.info("更新分镜: shotId={}", shotId);
    }

    private boolean isShotNameOnlyUpdate(ShotUpdateRequest request) {
        return request.getShotName() != null
                && request.getDescription() == null
                && request.getDescriptionEdited() == null
                && request.getStartTime() == null
                && request.getEndTime() == null
                && request.getDuration() == null
                && request.getResolution() == null
                && request.getAspectRatio() == null
                && request.getShotType() == null
                && request.getCameraAngle() == null
                && request.getCameraMovement() == null
                && request.getSoundEffect() == null
                && request.getSceneName() == null
                && request.getSceneEdited() == null
                && request.getUserPrompt() == null
                && request.getGenerationStatus() == null
                && request.getVideoModel() == null;
    }

    private String normalizeShotName(String shotName) {
        if (shotName == null) {
            return null;
        }
        String trimmed = shotName.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void ensureShotCanBeModified(Shot shot) {
        if (shot != null && ShotStatus.APPROVED.getCode().equals(shot.getStatus())) {
            throw new BusinessException("已锁定分镜只能修改标题");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reviewShot(Long shotId, ShotReviewRequest request) {
        Shot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            throw new BusinessException("分镜不存在");
        }
        ensureShotCanBeModified(shot);

        if (Boolean.TRUE.equals(request.getApproved())) {
            Integer oldShotNumber = shot.getShotNumber();
            Integer maxLockedShotNumber = shotMapper.selectMaxLockedShotNumber(
                    shot.getEpisodeId(),
                    ShotStatus.APPROVED.getCode()
            );
            shot.setShotNumber((maxLockedShotNumber != null ? maxLockedShotNumber : 0) + 1);
            shot.setStatus(ShotStatus.APPROVED.getCode());
            if (oldShotNumber != null) {
                shotMapper.decrementUnlockedShotNumbers(
                        shot.getEpisodeId(),
                        oldShotNumber,
                        ShotStatus.APPROVED.getCode()
                );
            }
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
    @Transactional(rollbackFor = Exception.class)
    public void unlockShot(Long shotId) {
        Shot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            throw new BusinessException("分镜不存在");
        }
        if (!ShotStatus.APPROVED.getCode().equals(shot.getStatus())) {
            return;
        }

        Integer oldShotNumber = shot.getShotNumber();
        Integer maxUnlockedShotNumber = shotMapper.selectMaxUnlockedShotNumber(
                shot.getEpisodeId(),
                ShotStatus.APPROVED.getCode()
        );
        shot.setShotNumber((maxUnlockedShotNumber != null ? maxUnlockedShotNumber : 0) + 1);
        shot.setStatus(ShotStatus.PENDING_REVIEW.getCode());
        shot.setUpdatedAt(LocalDateTime.now());
        shotMapper.updateById(shot);
        if (oldShotNumber != null) {
            shotMapper.decrementLockedShotNumbers(
                    shot.getEpisodeId(),
                    oldShotNumber,
                    ShotStatus.APPROVED.getCode()
            );
        }

        Episode episode = episodeMapper.selectById(shot.getEpisodeId());
        if (episode != null && EpisodeStatus.COMPLETED.getCode().equals(episode.getStatus())) {
            episode.setStatus(EpisodeStatus.PENDING_REVIEW.getCode());
            episode.setUpdatedAt(LocalDateTime.now());
            episodeMapper.updateById(episode);
        }

        log.info("解锁分镜: shotId={}", shotId);
    }

    @Override
    public void generateVideo(Long shotId) {
        log.info("开始生成视频: shotId={}", shotId);

        // 同步更新状态为生成中，确保状态立即持久化
        Shot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            log.error("分镜不存在: shotId={}", shotId);
            throw new BusinessException("分镜不存在");
        }
        ensureShotCanBeModified(shot);
        if (ShotGenerationStatus.GENERATING.getCode().equals(shot.getGenerationStatus())) {
            log.info("分镜视频已在生成中，拒绝重复提交: shotId={}", shotId);
            throw new BusinessException("分镜正在生成中，请等待当前任务完成");
        }

        // 计算并扣除积分
        int requiredCredits = CreditConstants.calculateCredits(shot.getResolution(), shot.getDuration(), shot.getVideoModel());
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new BusinessException("用户未登录");
        }
        userService.deductCredits(userId, requiredCredits, CreditUsageType.VIDEO_GENERATION.getCode(),
                "视频生成-分镜" + shot.getShotNumber(), shotId, "SHOT");
        log.info("积分扣除成功: userId={}, amount={}, shotId={}", userId, requiredCredits, shotId);

        // 记录扣除的积分（用于失败时返还）
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<Shot> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Shot::getId, shotId)
                .ne(Shot::getGenerationStatus, ShotGenerationStatus.GENERATING.getCode())
                .set(Shot::getDeductedCredits, requiredCredits)
                .set(Shot::getGenerationStatus, ShotGenerationStatus.GENERATING.getCode())
                .set(Shot::getGenerationError, null)
                .set(Shot::getGenerationStartTime, now)
                .set(Shot::getUpdatedAt, now);
        int updated = shotMapper.update(null, updateWrapper);
        if (updated == 0) {
            userService.refundCredits(userId, requiredCredits, "视频生成重复提交返还-分镜" + shot.getShotNumber(), shotId, "SHOT");
            log.info("分镜视频生成重复提交，积分已返还: shotId={}, credits={}", shotId, requiredCredits);
            throw new BusinessException("分镜正在生成中，请等待当前任务完成");
        }
        log.info("已更新分镜状态为生成中: shotId={}", shotId);

        // 通过代理异步执行视频生成（确保真正的异步）
        self.doGenerateVideo(shotId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShotDetailVO uploadVideo(Long shotId, String aspectRatio, MultipartFile file) {
        Shot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            throw new BusinessException("分镜不存在");
        }
        ensureShotCanBeModified(shot);
        if (ShotGenerationStatus.GENERATING.getCode().equals(shot.getGenerationStatus())) {
            throw new BusinessException("分镜正在生成中，请等待当前任务完成");
        }

        String normalizedAspectRatio = normalizeShotAspectRatio(aspectRatio != null ? aspectRatio : shot.getAspectRatio());
        validateUploadedShotVideo(file);

        try {
            String contentType = normalizeVideoContentType(file.getContentType(), file.getOriginalFilename());
            String extension = resolveVideoExtension(contentType, file.getOriginalFilename());
            String videoUrl = ossService.uploadVideo(file.getBytes(), "videos", contentType, extension);
            if (videoUrl == null || videoUrl.isBlank()) {
                throw new BusinessException("上传视频失败");
            }

            shotVideoAssetMapper.deactivateAllByShotId(shotId);

            Integer maxVersion = shotVideoAssetMapper.selectMaxVersion(shotId);
            int newVersion = (maxVersion != null ? maxVersion : 0) + 1;

            ShotVideoAsset videoAsset = new ShotVideoAsset();
            videoAsset.setShotId(shotId);
            videoAsset.setVersion(newVersion);
            videoAsset.setVideoUrl(videoUrl);
            videoAsset.setThumbnailUrl(null);
            videoAsset.setIsActive(1);
            videoAsset.setGenerationDuration(null);
            videoAsset.setCreatedAt(LocalDateTime.now());
            videoAsset.setUpdatedAt(LocalDateTime.now());
            shotVideoAssetMapper.insert(videoAsset);

            ShotVideoAssetMetadata metadata = new ShotVideoAssetMetadata();
            metadata.setShotVideoAssetId(videoAsset.getId());
            metadata.setModel("manual-upload");
            metadata.setPrompt("用户手动上传分镜视频");
            metadata.setGenerationParams("{\"aspectRatio\":\"" + normalizedAspectRatio + "\",\"contentType\":\"" + contentType + "\"}");
            metadata.setCreatedAt(LocalDateTime.now());
            shotVideoAssetMetadataMapper.insert(metadata);

            shot.setVideoUrl(videoUrl);
            shot.setThumbnailUrl(null);
            shot.setAspectRatio(normalizedAspectRatio);
            shot.setGenerationStatus(ShotGenerationStatus.COMPLETED.getCode());
            shot.setGenerationError(null);
            shot.setGenerationDuration(null);
            shot.setGenerationStartTime(null);
            shot.setDeductedCredits(null);
            shot.setUpdatedAt(LocalDateTime.now());
            shotMapper.updateById(shot);

            log.info("手动上传分镜视频完成: shotId={}, version={}, assetId={}", shotId, newVersion, videoAsset.getId());
            return convertToDetailVO(shot);
        } catch (IOException e) {
            throw new BusinessException("读取上传视频失败");
        }
    }

    private String normalizeShotAspectRatio(String aspectRatio) {
        if (aspectRatio == null || aspectRatio.isBlank()) {
            return "16:9";
        }
        String trimmed = aspectRatio.trim();
        if (!ALLOWED_SHOT_ASPECT_RATIOS.contains(trimmed)) {
            throw new BusinessException("不支持的视频比例");
        }
        return trimmed;
    }

    private void validateUploadedShotVideo(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传视频不能为空");
        }
        String contentType = normalizeVideoContentType(file.getContentType(), file.getOriginalFilename());
        if (!ALLOWED_UPLOAD_VIDEO_TYPES.contains(contentType)) {
            throw new BusinessException("只支持 MP4、WEBM、MOV 格式的视频");
        }
        if (file.getSize() > MAX_UPLOAD_VIDEO_SIZE) {
            throw new BusinessException("视频大小不能超过50MB");
        }
    }

    private String normalizeVideoContentType(String contentType, String filename) {
        String lowerType = contentType != null ? contentType.toLowerCase() : "";
        if (ALLOWED_UPLOAD_VIDEO_TYPES.contains(lowerType)) {
            return lowerType;
        }
        String lowerFilename = filename != null ? filename.toLowerCase() : "";
        if (lowerFilename.endsWith(".webm")) {
            return "video/webm";
        }
        if (lowerFilename.endsWith(".mov")) {
            return "video/quicktime";
        }
        if (lowerFilename.endsWith(".m4v")) {
            return "video/x-m4v";
        }
        if (lowerFilename.endsWith(".mp4")) {
            return "video/mp4";
        }
        return lowerType;
    }

    private String resolveVideoExtension(String contentType, String filename) {
        String lowerFilename = filename != null ? filename.toLowerCase() : "";
        if (lowerFilename.endsWith(".webm")) {
            return "webm";
        }
        if (lowerFilename.endsWith(".mov")) {
            return "mov";
        }
        if (lowerFilename.endsWith(".m4v")) {
            return "m4v";
        }
        if ("video/webm".equals(contentType)) {
            return "webm";
        }
        if ("video/quicktime".equals(contentType)) {
            return "mov";
        }
        if ("video/x-m4v".equals(contentType)) {
            return "m4v";
        }
        return "mp4";
    }

    @Override
    @Async("videoGenerateExecutor")
    public void doGenerateVideo(Long shotId) {
        log.info("异步执行视频生成: shotId={}", shotId);
        long startTime = System.currentTimeMillis();

        Shot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            log.error("分镜不存在: shotId={}", shotId);
            return;
        }

        try {
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
            request.setModel(convertToApiModel(shot.getVideoModel(), shot.getResolution()));
            request.setResolution(shot.getResolution());
            request.setGenerateAudio(true);

            // 设置视频尺寸
            int[] size = calculateVideoSize(shot.getResolution(), shot.getAspectRatio());
            request.setWidth(size[0]);
            request.setHeight(size[1]);
            request.setRatio(shot.getAspectRatio() != null ? shot.getAspectRatio() : "16:9");

            // 调用Seedance生成视频
            SeedanceResponse response = seedanceService.generateVideo(request);

            if ("completed".equals(response.getStatus()) || "succeeded".equals(response.getStatus())) {
                ensureGeneratedVideoUrlPresent(response);
                // 计算生成耗时
                int durationSeconds = (int) ((System.currentTimeMillis() - startTime) / 1000);
                log.info("视频生成耗时: {}秒 (约{}分{}秒)", durationSeconds, durationSeconds / 60, durationSeconds % 60);

                // 保存视频URL
                persistSuccessfulVideoGeneration(shot.getId(), response, durationSeconds);

                // 保存元数据
                shot.setVideoUrl(response.getVideoUrl());
                shot.setThumbnailUrl(response.getThumbnailUrl());
                shot.setVideoSeed(response.getSeed());
                shot.setGenerationStatus(ShotGenerationStatus.COMPLETED.getCode());
                shot.setGenerationError(null);
                shot.setGenerationDuration(durationSeconds);
                shot.setDeductedCredits(null);
                saveVideoMetadata(shot, prompt, response);

                // 保存视频版本资产
                saveVideoAsset(shot, prompt, response, null, durationSeconds);

                log.info("视频生成完成: shotId={}", shotId);
            } else {
                String errorMsg = response.getErrorMessage();
                log.error("视频生成失败: shotId={}, status={}, errorMessage={}", shotId, response.getStatus(), errorMsg);
                throw new RuntimeException("视频生成失败: " + (errorMsg != null ? errorMsg : "状态-" + response.getStatus()));
            }
        } catch (Exception e) {
            log.error("视频生成异常: shotId={}", shotId, e);
            shot.setGenerationStatus(ShotGenerationStatus.FAILED.getCode());
            shot.setGenerationError(resolveVideoGenerationError(e.getMessage(), false));

            // 生成失败，返还积分
            if (shot.getDeductedCredits() != null && shot.getDeductedCredits() > 0) {
                Long userId = getUserIdForShot(shot);
                if (userId != null) {
                    userService.refundCredits(userId, shot.getDeductedCredits(),
                            "视频生成失败返还-分镜" + shot.getShotNumber(), shotId, "SHOT");
                    log.info("视频生成失败，积分已返还: shotId={}, credits={}", shotId, shot.getDeductedCredits());
                }
                shot.setDeductedCredits(null);
            }

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
                .ne(Shot::getStatus, ShotStatus.APPROVED.getCode())
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
        // 先删除旧的元数据记录（重新生成的情况）
        LambdaQueryWrapper<VideoMetadata> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(VideoMetadata::getShotId, shot.getId());
        videoMetadataMapper.delete(deleteWrapper);

        VideoMetadata metadata = new VideoMetadata();
        metadata.setShotId(shot.getId());
        metadata.setPrompt(prompt);
        metadata.setUserPrompt(shot.getUserPrompt());
        metadata.setSeed(response.getSeed());
        metadata.setModelVersion(resolveVideoModelForMetadata(shot, response));
        metadata.setVideoDuration(shot.getDuration());
        metadata.setGenerationTimeMs(response.getGenerationTimeMs());
        metadata.setCreatedAt(LocalDateTime.now());
        videoMetadataMapper.insert(metadata);
    }

    /**
     * 转换为详情VO（优化版，使用预查询的数据）
     */
    private ShotDetailVO convertToDetailVOOptimized(Shot shot, Map<Long, Scene> sceneMap,
            Map<Long, SceneAsset> sceneAssetMap, List<ShotCharacter> shotCharacters, Map<Long, Role> roleMap, Map<String, RoleAsset> assetMap,
            List<ShotProp> shotProps, Map<Long, PropAsset> propAssetMap, Map<Long, String> propIdToNameMap, Map<String, PropAsset> propNameToAssetMap,
            Map<Long, ShotVideoAsset> activeVideoAssetMap, Map<Long, ShotVideoAssetMetadata> activeVideoMetadataMap) {
        ShotDetailVO vo = new ShotDetailVO();
        BeanUtils.copyProperties(shot, vo);
        applyVideoSource(vo, shot, activeVideoAssetMap.get(shot.getId()), activeVideoMetadataMap);

        // 不再覆盖 sceneName，保留分镜表中的原始值（可能包含 @{...} 标记）
        // 如果分镜的 sceneName 为空，才使用场景表的名称
        if (shot.getSceneName() == null || shot.getSceneName().isEmpty()) {
            if (shot.getSceneId() != null) {
                Scene scene = sceneMap.get(shot.getSceneId());
                if (scene != null) {
                    vo.setSceneName(scene.getSceneName());
                }
            }
        }

        // 获取场景资产缩略图
        if (shot.getSceneId() != null) {
            SceneAsset sceneAsset = sceneAssetMap.get(shot.getSceneId());
            if (sceneAsset != null) {
                vo.setSceneAssetUrl(sceneAsset.getFilePath());
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
        Set<String> addedPropNames = new HashSet<>();

        // 1. 从 ShotProp 表获取道具
        for (ShotProp sp : shotProps) {
            ShotDetailVO.PropInfo propInfo = new ShotDetailVO.PropInfo();
            propInfo.setPropId(sp.getPropId());
            propInfo.setPositionX(sp.getPositionX());
            propInfo.setPositionY(sp.getPositionY());
            propInfo.setScale(sp.getScale());
            propInfo.setRotation(sp.getRotation());
            String propName = propIdToNameMap.get(sp.getPropId());
            if (propName != null) {
                propInfo.setPropName(propName);
                addedPropNames.add(propName);
            }

            // 获取道具资产图片
            PropAsset asset = propAssetMap.get(sp.getPropId());
            if (asset != null) {
                if (propInfo.getPropName() == null) {
                    propInfo.setPropName(asset.getFileName());
                    addedPropNames.add(asset.getFileName());
                }
                propInfo.setAssetUrl(asset.getFilePath());
            }

            props.add(propInfo);
        }

        // 2. 从 propsJson 解析道具（补充 ShotProp 表中没有的道具）
        if (shot.getPropsJson() != null && !shot.getPropsJson().isEmpty()) {
            try {
                List<Map<String, Object>> propsFromJson = OBJECT_MAPPER.readValue(shot.getPropsJson(), PROP_JSON_TYPE);

                for (Map<String, Object> propData : propsFromJson) {
                    String propName = (String) propData.get("propName");
                    if (propName != null && !addedPropNames.contains(propName)) {
                        ShotDetailVO.PropInfo propInfo = new ShotDetailVO.PropInfo();
                        propInfo.setPropName(propName);

                        // 从 propNameToAssetMap 查找道具资产
                        PropAsset propAsset = propNameToAssetMap.get(propName);
                        if (propAsset != null) {
                            propInfo.setPropId(propAsset.getPropId());
                            propInfo.setAssetUrl(propAsset.getFilePath());
                        }

                        props.add(propInfo);
                        addedPropNames.add(propName);
                    }
                }
            } catch (Exception e) {
                log.warn("解析propsJson失败: {}", e.getMessage());
            }
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
        ShotVideoAsset activeVideoAsset = shotVideoAssetMapper.selectActiveByShotId(shot.getId());
        Map<Long, ShotVideoAssetMetadata> videoMetadataMap = activeVideoAsset == null
                ? Map.of()
                : loadVideoMetadataMap(List.of(activeVideoAsset));
        applyVideoSource(vo, shot, activeVideoAsset, videoMetadataMap);

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

    private Map<Long, ShotVideoAsset> loadActiveVideoAssetMap(List<Long> shotIds) {
        if (shotIds == null || shotIds.isEmpty()) {
            return Map.of();
        }
        List<ShotVideoAsset> activeAssets = shotVideoAssetMapper.selectActiveByShotIds(shotIds);
        if (activeAssets == null || activeAssets.isEmpty()) {
            return Map.of();
        }
        return activeAssets.stream()
                .collect(Collectors.toMap(ShotVideoAsset::getShotId, asset -> asset, (a, b) -> a));
    }

    private Map<Long, ShotVideoAssetMetadata> loadVideoMetadataMap(Iterable<ShotVideoAsset> assets) {
        if (assets == null) {
            return Map.of();
        }
        List<Long> assetIds = new ArrayList<>();
        for (ShotVideoAsset asset : assets) {
            if (asset != null && asset.getId() != null) {
                assetIds.add(asset.getId());
            }
        }
        if (assetIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<ShotVideoAssetMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(ShotVideoAssetMetadata::getShotVideoAssetId, assetIds);
        List<ShotVideoAssetMetadata> metadataList = shotVideoAssetMetadataMapper.selectList(wrapper);
        if (metadataList == null || metadataList.isEmpty()) {
            return Map.of();
        }
        return metadataList.stream()
                .collect(Collectors.toMap(ShotVideoAssetMetadata::getShotVideoAssetId, metadata -> metadata, (a, b) -> a));
    }

    private void applyVideoSource(ShotDetailVO vo, Shot shot, ShotVideoAsset activeVideoAsset,
                                  Map<Long, ShotVideoAssetMetadata> activeVideoMetadataMap) {
        if (vo == null || shot == null || !ShotGenerationStatus.COMPLETED.getCode().equals(shot.getGenerationStatus())) {
            return;
        }

        ShotVideoAssetMetadata metadata = null;
        if (activeVideoAsset != null && activeVideoAsset.getId() != null && activeVideoMetadataMap != null) {
            metadata = activeVideoMetadataMap.get(activeVideoAsset.getId());
        }
        if (metadata != null && VIDEO_MODEL_MANUAL_UPLOAD.equals(metadata.getModel())) {
            vo.setVideoSource(VIDEO_SOURCE_MANUAL);
            return;
        }
        if ((activeVideoAsset != null && activeVideoAsset.getVideoUrl() != null && !activeVideoAsset.getVideoUrl().isBlank())
                || (shot.getVideoUrl() != null && !shot.getVideoUrl().isBlank())) {
            vo.setVideoSource(VIDEO_SOURCE_SYSTEM);
        }
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
        Shot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            throw new BusinessException("分镜不存在");
        }
        ensureShotCanBeModified(shot);

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
        PropAsset propAsset = getActivePropAsset(sp.getPropId(), shot.getEpisodeId());
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
    public void generateVideoWithReferences(Long shotId, List<String> referenceUrls) {
        generateVideoWithReferences(shotId, referenceUrls, null);
    }

    @Override
    public void generateVideoWithReferences(Long shotId, List<String> referenceUrls, List<ReferenceImageDTO> referenceImages) {
        generateVideoWithReferences(shotId, referenceUrls, referenceImages, null, null);
    }

    @Override
    public void generateVideoWithReferences(Long shotId, List<String> referenceUrls, List<ReferenceImageDTO> referenceImages,
            ShotUpdateRequest shotUpdate, LocalDateTime generationStartTime) {
        self.prepareVideoGenerationWithReferences(shotId, referenceUrls, referenceImages, shotUpdate, generationStartTime);
        self.startPreparedVideoGenerationWithReferences(shotId, referenceUrls);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShotDetailVO prepareVideoGenerationWithReferences(Long shotId, List<String> referenceUrls, List<ReferenceImageDTO> referenceImages,
            ShotUpdateRequest shotUpdate, LocalDateTime generationStartTime) {
        log.info("开始生成视频(带参考图): shotId={}, referenceUrls={}", shotId, referenceUrls);

        // 获取分镜信息
        Shot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            log.error("分镜不存在: shotId={}", shotId);
            throw new BusinessException("分镜不存在");
        }
        ensureShotCanBeModified(shot);
        if (ShotGenerationStatus.GENERATING.getCode().equals(shot.getGenerationStatus())) {
            log.info("分镜视频已在生成中，拒绝重复提交(带参考图): shotId={}", shotId);
            throw new BusinessException("分镜正在生成中，请等待当前任务完成");
        }
        applyShotUpdateForGeneration(shot, shotUpdate);

        // 计算并扣除积分
        int requiredCredits = CreditConstants.calculateCredits(shot.getResolution(), shot.getDuration(), shot.getVideoModel());
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new BusinessException("用户未登录");
        }
        userService.deductCredits(userId, requiredCredits, CreditUsageType.VIDEO_GENERATION.getCode(),
                "视频生成-分镜" + shot.getShotNumber(), shotId, "SHOT");
        log.info("积分扣除成功: userId={}, amount={}, shotId={}", userId, requiredCredits, shotId);

        // 更新状态并记录扣除的积分
        LocalDateTime now = generationStartTime != null ? generationStartTime : LocalDateTime.now();
        LambdaUpdateWrapper<Shot> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Shot::getId, shotId)
                .ne(Shot::getGenerationStatus, ShotGenerationStatus.GENERATING.getCode())
                .set(Shot::getGenerationStatus, ShotGenerationStatus.GENERATING.getCode())
                .set(Shot::getGenerationError, null)
                .set(Shot::getDeductedCredits, requiredCredits)
                .set(Shot::getGenerationStartTime, now)
                .set(Shot::getUpdatedAt, now);
        applyShotUpdateToWrapper(updateWrapper, shotUpdate, shot);
        int updated = shotMapper.update(null, updateWrapper);

        if (updated == 0) {
            userService.refundCredits(userId, requiredCredits, "视频生成重复提交返还-分镜" + shot.getShotNumber(), shotId, "SHOT");
            log.info("分镜视频生成重复提交，积分已返还(带参考图): shotId={}, credits={}", shotId, requiredCredits);
            throw new BusinessException("分镜正在生成中，请等待当前任务完成");
        }
        replaceReferenceImagesForGeneration(shotId, referenceImages);
        log.info("已更新分镜状态为生成中: shotId={}", shotId);
        shot.setGenerationStatus(ShotGenerationStatus.GENERATING.getCode());
        shot.setGenerationError(null);
        shot.setDeductedCredits(requiredCredits);
        shot.setGenerationStartTime(now);
        shot.setUpdatedAt(now);

        return convertToDetailVO(shot);
    }

    @Override
    public void startPreparedVideoGenerationWithReferences(Long shotId, List<String> referenceUrls) {
        Shot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            log.error("分镜不存在: shotId={}", shotId);
            throw new BusinessException("分镜不存在");
        }
        ensureShotCanBeModified(shot);
        if (!ShotGenerationStatus.GENERATING.getCode().equals(shot.getGenerationStatus())) {
            log.warn("分镜未处于生成中，拒绝启动后台生成: shotId={}, generationStatus={}", shotId, shot.getGenerationStatus());
            throw new BusinessException("分镜生成任务尚未提交，请重新点击生成");
        }
        log.info("启动已准备的视频生成任务: shotId={}", shotId);
        self.doGenerateVideoWithReferences(shotId, referenceUrls);
    }

    private void applyShotUpdateForGeneration(Shot shot, ShotUpdateRequest request) {
        if (shot == null || request == null) {
            return;
        }
        if (request.getDescription() != null) {
            shot.setDescription(request.getDescription());
        }
        if (request.getDescriptionEdited() != null) {
            shot.setDescriptionEdited(request.getDescriptionEdited());
        }
        if (request.getStartTime() != null) {
            shot.setStartTime(request.getStartTime());
        }
        if (request.getEndTime() != null) {
            shot.setEndTime(request.getEndTime());
        }
        if (request.getDuration() != null) {
            shot.setDuration(Math.min(request.getDuration(), 15));
        }
        String nextVideoModel = request.getVideoModel() != null ? request.getVideoModel() : shot.getVideoModel();
        String nextResolution = request.getResolution() != null ? request.getResolution() : shot.getResolution();
        nextResolution = normalizeResolutionForModel(nextVideoModel, nextResolution);
        if (request.getResolution() != null) {
            shot.setResolution(nextResolution);
        }
        if (request.getAspectRatio() != null) {
            shot.setAspectRatio(request.getAspectRatio());
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
        if (request.getShotName() != null) {
            shot.setShotName(normalizeShotName(request.getShotName()));
        }
        if (request.getSceneName() != null) {
            shot.setSceneName(request.getSceneName());
        }
        if (request.getSceneEdited() != null) {
            shot.setSceneEdited(request.getSceneEdited());
        }
        if (request.getUserPrompt() != null) {
            shot.setUserPrompt(request.getUserPrompt());
        }
        if (request.getVideoModel() != null) {
            shot.setVideoModel(nextVideoModel);
            if (!Objects.equals(nextResolution, shot.getResolution())) {
                shot.setResolution(nextResolution);
            }
        }
    }

    private void applyShotUpdateToWrapper(LambdaUpdateWrapper<Shot> updateWrapper, ShotUpdateRequest request, Shot resolvedShot) {
        if (request == null) {
            return;
        }
        if (request.getDescription() != null) {
            updateWrapper.set(Shot::getDescription, request.getDescription());
        }
        if (request.getDescriptionEdited() != null) {
            updateWrapper.set(Shot::getDescriptionEdited, request.getDescriptionEdited());
        }
        if (request.getStartTime() != null) {
            updateWrapper.set(Shot::getStartTime, request.getStartTime());
        }
        if (request.getEndTime() != null) {
            updateWrapper.set(Shot::getEndTime, request.getEndTime());
        }
        if (request.getDuration() != null) {
            updateWrapper.set(Shot::getDuration, Math.min(request.getDuration(), 15));
        }
        if (request.getResolution() != null) {
            updateWrapper.set(Shot::getResolution, resolvedShot != null ? resolvedShot.getResolution() : request.getResolution());
        }
        if (request.getAspectRatio() != null) {
            updateWrapper.set(Shot::getAspectRatio, request.getAspectRatio());
        }
        if (request.getShotType() != null) {
            updateWrapper.set(Shot::getShotType, request.getShotType());
        }
        if (request.getCameraAngle() != null) {
            updateWrapper.set(Shot::getCameraAngle, request.getCameraAngle());
        }
        if (request.getCameraMovement() != null) {
            updateWrapper.set(Shot::getCameraMovement, request.getCameraMovement());
        }
        if (request.getSoundEffect() != null) {
            updateWrapper.set(Shot::getSoundEffect, request.getSoundEffect());
        }
        if (request.getShotName() != null) {
            updateWrapper.set(Shot::getShotName, normalizeShotName(request.getShotName()));
        }
        if (request.getSceneName() != null) {
            updateWrapper.set(Shot::getSceneName, request.getSceneName());
        }
        if (request.getSceneEdited() != null) {
            updateWrapper.set(Shot::getSceneEdited, request.getSceneEdited());
        }
        if (request.getUserPrompt() != null) {
            updateWrapper.set(Shot::getUserPrompt, request.getUserPrompt());
        }
        if (request.getVideoModel() != null) {
            updateWrapper.set(Shot::getVideoModel, request.getVideoModel());
            if (request.getResolution() == null && resolvedShot != null
                    && "kling-v3-omni".equals(resolvedShot.getVideoModel())
                    && "720p".equals(resolvedShot.getResolution())) {
                updateWrapper.set(Shot::getResolution, resolvedShot.getResolution());
            }
        }
    }

    private void replaceReferenceImagesForGeneration(Long shotId, List<ReferenceImageDTO> referenceImages) {
        if (referenceImages == null) {
            return;
        }
        LambdaQueryWrapper<ShotReferenceImage> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(ShotReferenceImage::getShotId, shotId);
        shotReferenceImageMapper.delete(deleteWrapper);

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
        log.info("生成前同步分镜参考图: shotId={}, count={}", shotId, referenceImages.size());
    }

    @Override
    @Async("videoGenerateExecutor")
    public void doGenerateVideoWithReferences(Long shotId, List<String> referenceUrls) {
        log.info("异步执行视频生成: shotId={}, referenceUrls={}", shotId, referenceUrls);
        long startTime = System.currentTimeMillis();

        Shot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            log.error("分镜不存在: shotId={}", shotId);
            return;
        }

        try {

            // 获取 seriesId
            Episode episode = episodeMapper.selectById(shot.getEpisodeId());
            Long seriesId = episode != null ? episode.getSeriesId() : null;

            // 构建提示词
            PromptWithReferences result = buildPromptFromSceneAndDescription(shot, seriesId);
            log.info("构建的提示词: {}", result.getPrompt());

            List<AssetReference> references = collectShotVideoReferences(shot, seriesId, referenceUrls, result);
            log.info("去重后参考图数量: {}", references.size());

            // 参考图数量限制检查（最多9张）
            if (references.size() > 9) {
                log.warn("参考图数量超过9张，按优先级截取前9张: shotId={}, count={}", shotId, references.size());
                references = new ArrayList<>(references.subList(0, 9));
            }

            for (AssetReference ref : references) {
                log.info("参考图: type={}, name={}, url={}", ref.getType(), ref.getName(), ref.getImageUrl());
            }
            // 创建生成请求
            SeedanceRequest request = new SeedanceRequest();
            request.setModel(convertToApiModel(shot.getVideoModel(), shot.getResolution()));
            String finalPrompt = appendReferenceGuide(result.getPrompt(), references, request.getModel());
            request.setPrompt(finalPrompt);
            request.setDuration(shot.getDuration() != null ? shot.getDuration() : 5);
            request.setShotId(shotId);
            request.setResolution(shot.getResolution());
            request.setGenerateAudio(true);

            // 设置视频尺寸
            int[] size = calculateVideoSize(shot.getResolution(), shot.getAspectRatio());
            request.setWidth(size[0]);
            request.setHeight(size[1]);
            request.setRatio(shot.getAspectRatio() != null ? shot.getAspectRatio() : "16:9");

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
                ensureGeneratedVideoUrlPresent(response);
                // 计算生成耗时
                int durationSeconds = (int) ((System.currentTimeMillis() - startTime) / 1000);
                log.info("视频生成耗时: {}秒 (约{}分{}秒)", durationSeconds, durationSeconds / 60, durationSeconds % 60);

                // 保存视频URL
                persistSuccessfulVideoGeneration(shot.getId(), response, durationSeconds);

                // 保存元数据
                shot.setVideoUrl(response.getVideoUrl());
                shot.setThumbnailUrl(response.getThumbnailUrl());
                shot.setVideoSeed(response.getSeed());
                shot.setGenerationStatus(ShotGenerationStatus.COMPLETED.getCode());
                shot.setGenerationError(null);
                shot.setGenerationDuration(durationSeconds);
                shot.setDeductedCredits(null);
                saveVideoMetadata(shot, finalPrompt, response);

                // 保存视频版本资产
                saveVideoAsset(shot, finalPrompt, response,
                        references.stream().map(AssetReference::getImageUrl).collect(Collectors.toList()), durationSeconds);

                log.info("视频生成完成(带参考图): shotId={}, 耗时: {}分{}秒", shotId, durationSeconds / 60, durationSeconds % 60);
            } else {
                String errorMsg = response.getErrorMessage();
                log.error("视频生成失败(带参考图): shotId={}, status={}, errorMessage={}", shotId, response.getStatus(), errorMsg);
                throw new RuntimeException("视频生成失败: " + (errorMsg != null ? errorMsg : "状态-" + response.getStatus()));
            }
        } catch (Exception e) {
            log.error("视频生成异常(带参考图): shotId={}", shotId, e);
            shot.setGenerationStatus(ShotGenerationStatus.FAILED.getCode());
            shot.setGenerationError(resolveVideoGenerationError(e.getMessage(), true));

            // 生成失败，返还积分
            if (shot.getDeductedCredits() != null && shot.getDeductedCredits() > 0) {
                Long userId = getUserIdForShot(shot);
                if (userId != null) {
                    userService.refundCredits(userId, shot.getDeductedCredits(),
                            "视频生成失败返还-分镜" + shot.getShotNumber(), shotId, "SHOT");
                    log.info("视频生成失败，积分已返还: shotId={}, credits={}", shotId, shot.getDeductedCredits());
                }
                shot.setDeductedCredits(null);
            }

            shot.setUpdatedAt(LocalDateTime.now());
            shotMapper.updateById(shot);
        }
    }

    private void persistSuccessfulVideoGeneration(Long shotId, SeedanceResponse response, Integer durationSeconds) {
        LambdaUpdateWrapper<Shot> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Shot::getId, shotId)
                .set(Shot::getVideoUrl, response.getVideoUrl())
                .set(Shot::getThumbnailUrl, response.getThumbnailUrl())
                .set(Shot::getVideoSeed, response.getSeed())
                .set(Shot::getGenerationStatus, ShotGenerationStatus.COMPLETED.getCode())
                .set(Shot::getGenerationError, null)
                .set(Shot::getGenerationDuration, durationSeconds)
                .set(Shot::getDeductedCredits, null)
                .set(Shot::getUpdatedAt, LocalDateTime.now());
        shotMapper.update(null, updateWrapper);
    }

    private void ensureGeneratedVideoUrlPresent(SeedanceResponse response) {
        if (response == null || response.getVideoUrl() == null || response.getVideoUrl().isBlank()) {
            throw new RuntimeException("视频生成完成但未返回视频地址，请重新生成");
        }
    }

    private String resolveVideoGenerationError(String errorMsg, boolean withReferences) {
        String detail = normalizeVideoGenerationErrorDetail(errorMsg);
        if (detail.contains("ModelNotOpen")) {
            return "模型未开通，请在火山引擎控制台开通对应视频模型";
        }
        if (detail.contains("SensitiveContent")) {
            return withReferences
                    ? "内容审核未通过，请修改分镜描述后重试（避免戏剧化镜头语言）"
                    : "内容审核未通过，请修改分镜描述后重试";
        }
        if (detail.toLowerCase().contains("copyright")) {
            return withReferences
                    ? "内容审核未通过，请修改分镜描述后重试（避免标志性镜头描述）"
                    : "内容审核未通过，请修改分镜描述后重试";
        }
        if (detail.contains("超时")) {
            return "视频生成超时，请稍后重试或减少视频时长";
        }
        if (detail.contains("未返回视频地址")) {
            return "视频生成完成但未返回视频地址，请重新生成";
        }
        if (!detail.isBlank()) {
            return detail;
        }
        return "视频生成失败，请稍后重试";
    }

    private String normalizeVideoGenerationErrorDetail(String errorMsg) {
        if (errorMsg == null || errorMsg.isBlank()) {
            return "";
        }
        String detail = errorMsg.replaceFirst("^视频生成失败[:：]\\s*", "").trim();
        if (detail.startsWith("状态-")) {
            return "";
        }
        return detail;
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
            String processedSceneName = parseSceneMentions(sceneName, seriesId, shot.getEpisodeId(), references);
            promptBuilder.append("场景：").append(processedSceneName);
        }

        String description = shot.getDescription();
        if (description != null
                && description.contains("时间【")
                && description.contains("镜头【")
                && description.contains("剧情【")
                && description.contains("音效【")) {
            String processedDesc = parseAssetMentionsAndAutoMatch(description, seriesId, shot.getEpisodeId(), references);
            promptBuilder.append(" ").append(processedDesc);
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

        // 2. 时间
        String timeRange = formatTimeRange(shot.getStartTime(), shot.getEndTime());
        promptBuilder.append("，时间【").append(timeRange).append("】");

        // 3. 镜头
        String shotType = shot.getShotType();
        if (shotType != null && !shotType.isEmpty()) {
            promptBuilder.append(" 镜头【").append(shotType).append("】");
        }

        // 4. 剧情 - 解析资产引用
        if (description != null && !description.isEmpty()) {
            String processedDesc = parseAssetMentionsAndAutoMatch(description, seriesId, shot.getEpisodeId(), references);
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
    private String parseSceneMentions(String sceneName, Long seriesId, Long episodeId, List<AssetReference> references) {
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

            AssetReference assetRef = findAssetByName(assetName, seriesId, episodeId);
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
            AssetReference sceneRef = findAssetByName(sceneName.trim(), seriesId, episodeId);
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
    private String parseAssetMentionsAndAutoMatch(String text, Long seriesId, Long episodeId, List<AssetReference> references) {
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

            AssetReference assetRef = findAssetByName(assetName, seriesId, episodeId);
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
            processedText = autoMatchAssetsInText(processedText, seriesId, episodeId, references);
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
    private String autoMatchAssetsInText(String text, Long seriesId, Long episodeId, List<AssetReference> references) {
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
            PropAsset asset = getActivePropAsset(prop.getId(), episodeId);
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
     * 根据名称查找资产（支持模糊匹配场景名称）
     */
    private AssetReference findAssetByName(String name, Long seriesId) {
        return findAssetByName(name, seriesId, null);
    }

    private AssetReference findAssetByName(String name, Long seriesId, Long episodeId) {
        log.info("查找资产: name={}, seriesId={}", name, seriesId);

        // 1. 查找场景（支持模糊匹配）
        if (seriesId != null) {
            LambdaQueryWrapper<Scene> sceneWrapper = new LambdaQueryWrapper<>();
            sceneWrapper.eq(Scene::getSeriesId, seriesId)
                    .like(Scene::getSceneName, name);  // 使用 LIKE 匹配
            List<Scene> scenes = sceneMapper.selectList(sceneWrapper);

            // 优先精确匹配，其次模糊匹配
            Scene matchedScene = null;
            for (Scene scene : scenes) {
                if (scene.getSceneName().equals(name)) {
                    matchedScene = scene;
                    break;
                }
                if (name.contains(scene.getSceneName()) || scene.getSceneName().contains(name)) {
                    matchedScene = scene;
                }
            }

            if (matchedScene != null) {
                SceneAsset asset = getActiveSceneAsset(matchedScene.getId());
                if (asset != null && asset.getFilePath() != null) {
                    log.info("找到场景资产: name={}, sceneName={}, url={}", name, matchedScene.getSceneName(), asset.getFilePath());
                    AssetReference ref = new AssetReference();
                    ref.setType("scene");
                    ref.setName(matchedScene.getSceneName());
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
                PropAsset asset = getActivePropAsset(prop.getId(), episodeId);
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

    private List<AssetReference> collectShotVideoReferences(Shot shot, Long seriesId, List<String> frontendReferenceUrls,
            PromptWithReferences promptReferences) {
        List<AssetReference> references = new ArrayList<>();

        if (promptReferences != null && promptReferences.getReferenceImages() != null
                && !promptReferences.getReferenceImages().isEmpty()) {
            log.info("提示词自动匹配参考图数量: {}", promptReferences.getReferenceImages().size());
            for (AssetReference autoRef : promptReferences.getReferenceImages()) {
                addReference(references, autoRef.getType(), autoRef.getName(), autoRef.getImageUrl());
            }
        }

        if (frontendReferenceUrls != null && !frontendReferenceUrls.isEmpty()) {
            log.info("使用前端传入的参考图: {} 张", frontendReferenceUrls.size());
            for (String url : frontendReferenceUrls) {
                addReference(references, "frontend", "页面参考图", url);
            }
        }

        List<AssetReference> dbReferences = getReferenceImagesFromDB(shot.getId());
        log.info("从 shot_reference_image 表获取参考图数量: {}", dbReferences.size());
        references.addAll(dbReferences);

        addShotBoundAssetReferences(shot, seriesId, references);
        return dedupeReferences(references);
    }

    private void addShotBoundAssetReferences(Shot shot, Long seriesId, List<AssetReference> references) {
        if (shot == null) {
            return;
        }

        if (shot.getSceneId() != null) {
            Scene scene = sceneMapper.selectById(shot.getSceneId());
            SceneAsset sceneAsset = getActiveSceneAsset(shot.getSceneId());
            if (sceneAsset != null && sceneAsset.getFilePath() != null) {
                addReference(references, "scene", scene != null ? scene.getSceneName() : "分镜场景", sceneAsset.getFilePath());
            }
        }

        LambdaQueryWrapper<ShotCharacter> scWrapper = new LambdaQueryWrapper<>();
        scWrapper.eq(ShotCharacter::getShotId, shot.getId());
        List<ShotCharacter> shotCharacters = shotCharacterMapper.selectList(scWrapper);
        for (ShotCharacter sc : shotCharacters) {
            Role role = roleMapper.selectById(sc.getRoleId());
            RoleAsset roleAsset = getActiveRoleAsset(sc.getRoleId(), sc.getClothingId());
            if (roleAsset != null && roleAsset.getFilePath() != null) {
                String roleName = role != null ? role.getRoleName() : "分镜角色";
                String clothingName = roleAsset.getClothingName();
                String name = clothingName == null || clothingName.isBlank()
                        ? roleName
                        : roleName + "-" + clothingName;
                addReference(references, "role", name, roleAsset.getFilePath());
            }
        }

        LambdaQueryWrapper<ShotProp> spWrapper = new LambdaQueryWrapper<>();
        spWrapper.eq(ShotProp::getShotId, shot.getId());
        List<ShotProp> shotProps = shotPropMapper.selectList(spWrapper);
        for (ShotProp sp : shotProps) {
            Prop prop = propMapper.selectById(sp.getPropId());
            PropAsset propAsset = getActivePropAsset(sp.getPropId(), shot.getEpisodeId());
            if (propAsset != null && propAsset.getFilePath() != null) {
                addReference(references, "prop", prop != null ? prop.getPropName() : propAsset.getFileName(), propAsset.getFilePath());
            }
        }
        log.info("分镜绑定资产参考图已合并: shotId={}, seriesId={}, currentCount={}", shot.getId(), seriesId, references.size());
    }

    private void addReference(List<AssetReference> references, String type, String name, String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return;
        }
        AssetReference ref = new AssetReference();
        ref.setType(type);
        ref.setName(name);
        ref.setImageUrl(imageUrl.trim());
        references.add(ref);
    }

    private List<AssetReference> dedupeReferences(List<AssetReference> references) {
        List<AssetReference> dedupedReferences = new ArrayList<>();
        Map<String, Integer> indexByUrl = new HashMap<>();
        for (AssetReference ref : references) {
            if (ref.getImageUrl() == null) {
                continue;
            }
            String url = ref.getImageUrl();
            Integer existingIndex = indexByUrl.get(url);
            if (existingIndex == null) {
                indexByUrl.put(url, dedupedReferences.size());
                dedupedReferences.add(ref);
                continue;
            }
            AssetReference existing = dedupedReferences.get(existingIndex);
            if (referenceSpecificity(ref) > referenceSpecificity(existing)) {
                dedupedReferences.set(existingIndex, ref);
            }
        }
        return dedupedReferences;
    }

    private int referenceSpecificity(AssetReference ref) {
        int score = 0;
        String type = ref.getType();
        if (type != null && !type.isBlank() && !"frontend".equals(type)) {
            score += 2;
        }
        String name = ref.getName();
        if (name != null && !name.isBlank() && !"页面参考图".equals(name)) {
            score += 1;
        }
        return score;
    }

    private String appendReferenceGuide(String prompt, List<AssetReference> references, String model) {
        if (references == null || references.isEmpty()) {
            return prompt;
        }
        if ("kling-v3-omni".equals(model)) {
            return appendKlingOmniReferenceGuide(prompt, references);
        }
        return appendSeedanceReferenceGuide(prompt, references);
    }

    private String appendSeedanceReferenceGuide(String prompt, List<AssetReference> references) {
        StringBuilder builder = new StringBuilder(prompt != null ? prompt : "");
        builder.append("\n\n参考图映射：");
        for (int i = 0; i < references.size(); i++) {
            AssetReference ref = references.get(i);
            builder.append("\n[图").append(i + 1).append("] ")
                    .append(ref.getType() != null ? ref.getType() : "asset")
                    .append("：")
                    .append(ref.getName() != null ? ref.getName() : "未命名参考图");
        }
        builder.append("\n请严格按以上参考图保持角色、场景、道具外观一致，尤其人物脸型、发型、服装和身份特征不要替换成其他形象。");
        return builder.toString();
    }

    private String appendKlingOmniReferenceGuide(String prompt, List<AssetReference> references) {
        StringBuilder builder = new StringBuilder();
        builder.append("Omni 输入绑定：");
        for (int i = 0; i < references.size(); i++) {
            AssetReference ref = references.get(i);
            builder.append("\n- ")
                    .append(omniBindingSubject(ref))
                    .append("必须使用 <<<image_").append(i + 1).append(">>>，")
                    .append(referenceInstruction(ref))
                    .append("。");
        }
        builder.append("\n\n");
        builder.append(prompt != null ? prompt : "");
        builder.append("\n\nKling Omni 参考图绑定：");
        for (int i = 0; i < references.size(); i++) {
            AssetReference ref = references.get(i);
            builder.append("\n").append(referenceLabel(ref)).append("：")
                    .append(ref.getName() != null ? ref.getName() : "未命名参考图")
                    .append("，必须使用 <<<image_").append(i + 1).append(">>> 作为")
                    .append(referenceInstruction(ref));
            String type = ref.getType();
            if ("role".equals(type)) {
                builder.append("；画面中的").append(ref.getName() != null ? ref.getName() : "该人物")
                        .append("必须以 <<<image_").append(i + 1)
                        .append(">>> 为人物外观参考，保持脸型、五官、发型、服装和身份特征一致");
            } else if ("scene".equals(type)) {
                builder.append("；当前镜头环境必须以 <<<image_").append(i + 1)
                        .append(">>> 为场景参考，保持空间结构、陈设、色彩氛围一致");
            } else if ("prop".equals(type)) {
                builder.append("；画面中的").append(ref.getName() != null ? ref.getName() : "该道具")
                        .append("必须以 <<<image_").append(i + 1)
                        .append(">>> 为道具外观参考");
            }
        }
        builder.append("\n请严格按照上述 <<<image_N>>> 参考图生成：场景参考图只约束环境和空间布局，人物参考图只约束对应角色的脸型、发型、服装和身份特征，道具参考图只约束对应物品外观。不要把人物替换成其他形象，不要忽略场景图。");
        return builder.toString();
    }

    private String omniBindingSubject(AssetReference ref) {
        String name = ref.getName() != null && !ref.getName().isBlank() ? ref.getName() : "未命名参考图";
        String type = ref.getType();
        if ("scene".equals(type)) {
            return "场景【" + name + "】";
        }
        if ("role".equals(type)) {
            return "人物【" + name + "】";
        }
        if ("prop".equals(type)) {
            return "道具【" + name + "】";
        }
        return "参考图【" + name + "】";
    }

    private String referenceLabel(AssetReference ref) {
        String type = ref.getType();
        if ("scene".equals(type)) {
            return "场景参考图";
        }
        if ("role".equals(type)) {
            return "人物参考图";
        }
        if ("prop".equals(type)) {
            return "道具参考图";
        }
        return "参考图";
    }

    private String referenceInstruction(AssetReference ref) {
        String type = ref.getType();
        if ("scene".equals(type)) {
            return "画面环境、建筑结构、色彩氛围和空间布局参考";
        }
        if ("role".equals(type)) {
            return "对应人物角色外观参考";
        }
        if ("prop".equals(type)) {
            return "对应道具外观参考";
        }
        return "视觉参考";
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
        return getActivePropAsset(propId, null);
    }

    private PropAsset getActivePropAsset(Long propId, Long episodeId) {
        Prop prop = propMapper.selectById(propId);
        LambdaQueryWrapper<PropAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PropAsset::getPropId, propId)
                .orderByDesc(PropAsset::getIsActive)
                .orderByDesc(PropAsset::getVersion)
                .orderByDesc(PropAsset::getId);
        List<PropAsset> assets = propAssetMapper.selectList(wrapper);
        return selectVisiblePropAsset(prop, assets, episodeId);
    }

    private PropAsset selectVisiblePropAsset(Prop prop, List<PropAsset> assets, Long episodeId) {
        if (prop == null || assets == null || assets.isEmpty()) {
            return null;
        }

        if (PropStatus.LOCKED.getCode().equals(prop.getStatus())) {
            return assets.stream()
                    .filter(asset -> asset.getIsActive() != null && asset.getIsActive() == 1)
                    .findFirst()
                    .orElse(assets.get(0));
        }

        if (episodeId == null) {
            return null;
        }

        return assets.stream()
                .filter(asset -> episodeId.equals(asset.getEpisodeId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 从 shot_reference_image 表获取用户选择的参考图
     */
    private List<AssetReference> getReferenceImagesFromDB(Long shotId) {
        LambdaQueryWrapper<ShotReferenceImage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ShotReferenceImage::getShotId, shotId)
                .orderByAsc(ShotReferenceImage::getDisplayOrder);
        List<ShotReferenceImage> images = shotReferenceImageMapper.selectList(wrapper);

        List<AssetReference> references = new ArrayList<>();
        for (ShotReferenceImage image : images) {
            AssetReference ref = new AssetReference();
            ref.setType(image.getImageType());
            ref.setName(image.getReferenceName());
            ref.setImageUrl(image.getImageUrl());
            references.add(ref);
            log.info("用户选择参考图: type={}, name={}, url={}", image.getImageType(), image.getReferenceName(), image.getImageUrl());
        }
        return references;
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

    @Override
    public List<ShotVideoAssetVO> getVideoHistory(Long shotId) {
        List<ShotVideoAsset> assets = shotVideoAssetMapper.selectByShotId(shotId);
        Map<Long, ShotVideoAssetMetadata> metadataMap = loadVideoMetadataMap(assets);
        return assets.stream()
                .map(asset -> convertToVideoAssetVO(asset, metadataMap.get(asset.getId())))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShotDetailVO rollbackToVersion(Long shotId, Long assetId) {
        Shot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            throw new BusinessException("分镜不存在");
        }
        ensureShotCanBeModified(shot);

        ShotVideoAsset targetAsset = shotVideoAssetMapper.selectById(assetId);
        if (targetAsset == null || !targetAsset.getShotId().equals(shotId)) {
            throw new BusinessException("视频资产不存在或不属于该分镜");
        }

        // 先将该分镜所有视频版本设为非激活
        shotVideoAssetMapper.deactivateAllByShotId(shotId);

        // 激活目标版本
        targetAsset.setIsActive(1);
        targetAsset.setUpdatedAt(LocalDateTime.now());
        shotVideoAssetMapper.updateById(targetAsset);

        // 更新 Shot 表的 videoUrl、thumbnailUrl 和 generationDuration（兼容旧逻辑）
        shot.setVideoUrl(targetAsset.getVideoUrl());
        shot.setThumbnailUrl(targetAsset.getThumbnailUrl());
        shot.setGenerationDuration(targetAsset.getGenerationDuration());
        shot.setUpdatedAt(LocalDateTime.now());
        shotMapper.updateById(shot);

        log.info("回滚视频版本: shotId={}, assetId={}, version={}", shotId, assetId, targetAsset.getVersion());
        return convertToDetailVO(shot);
    }

    /**
     * 保存视频版本资产
     */
    private void saveVideoAsset(Shot shot, String prompt, SeedanceResponse response, List<String> referenceUrls, Integer generationDuration) {
        // 先将该分镜所有视频版本设为非激活
        shotVideoAssetMapper.deactivateAllByShotId(shot.getId());

        // 获取当前最大版本号
        Integer maxVersion = shotVideoAssetMapper.selectMaxVersion(shot.getId());
        int newVersion = (maxVersion != null ? maxVersion : 0) + 1;

        // 创建新的视频资产
        ShotVideoAsset videoAsset = new ShotVideoAsset();
        videoAsset.setShotId(shot.getId());
        videoAsset.setVersion(newVersion);
        videoAsset.setVideoUrl(response.getVideoUrl());
        videoAsset.setThumbnailUrl(response.getThumbnailUrl());
        videoAsset.setIsActive(1);
        videoAsset.setGenerationDuration(generationDuration);
        videoAsset.setCreatedAt(LocalDateTime.now());
        videoAsset.setUpdatedAt(LocalDateTime.now());
        shotVideoAssetMapper.insert(videoAsset);

        // 保存元数据
        ShotVideoAssetMetadata metadata = new ShotVideoAssetMetadata();
        metadata.setShotVideoAssetId(videoAsset.getId());
        metadata.setModel(resolveVideoModelForMetadata(shot, response));
        metadata.setPrompt(prompt);
        if (referenceUrls != null && !referenceUrls.isEmpty()) {
            metadata.setReferenceUrls(String.join(",", referenceUrls));
        }
        metadata.setGenerationParams(buildVideoGenerationParams(response, referenceUrls));
        metadata.setCreatedAt(LocalDateTime.now());
        shotVideoAssetMetadataMapper.insert(metadata);

        log.info("保存视频版本资产: shotId={}, version={}, assetId={}", shot.getId(), newVersion, videoAsset.getId());
    }

    private String resolveVideoModelForMetadata(Shot shot, SeedanceResponse response) {
        if (response != null && response.getSubmitModel() != null && !response.getSubmitModel().isBlank()) {
            return response.getSubmitModel();
        }
        if (shot != null && shot.getVideoModel() != null && !shot.getVideoModel().isBlank()) {
            return convertToApiModel(shot.getVideoModel());
        }
        return "seedance-2.0";
    }

    private String buildVideoGenerationParams(SeedanceResponse response, List<String> referenceUrls) {
        if (response == null) {
            return null;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        putIfPresent(params, "requestUrl", response.getSubmitRequestUrl());
        putIfPresent(params, "requestBody", parseJsonObjectOrRaw(response.getSubmitRequestBody()));
        putIfPresent(params, "model", response.getSubmitModel());
        putIfPresent(params, "providerTaskId", response.getTaskId());
        putIfPresent(params, "providerVideoUrl", response.getProviderVideoUrl());
        putIfPresent(params, "ossVideoUrl", response.getVideoUrl());
        putIfPresent(params, "thumbnailUrl", response.getThumbnailUrl());
        putIfPresent(params, "seed", response.getSeed());
        if (referenceUrls != null && !referenceUrls.isEmpty()) {
            params.put("referenceUrls", referenceUrls);
        }
        if (params.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(params);
        } catch (Exception e) {
            log.warn("序列化视频生成参数失败: {}", e.getMessage());
            return null;
        }
    }

    private void putIfPresent(Map<String, Object> params, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        params.put(key, value);
    }

    private Object parseJsonObjectOrRaw(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(value, Object.class);
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * 转换为 ShotVideoAssetVO
     */
    private ShotVideoAssetVO convertToVideoAssetVO(ShotVideoAsset asset) {
        LambdaQueryWrapper<ShotVideoAssetMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ShotVideoAssetMetadata::getShotVideoAssetId, asset.getId());
        ShotVideoAssetMetadata metadata = shotVideoAssetMetadataMapper.selectOne(wrapper);
        return convertToVideoAssetVO(asset, metadata);
    }

    private ShotVideoAssetVO convertToVideoAssetVO(ShotVideoAsset asset, ShotVideoAssetMetadata metadata) {
        ShotVideoAssetVO vo = new ShotVideoAssetVO();
        vo.setId(asset.getId());
        vo.setShotId(asset.getShotId());
        vo.setVersion(asset.getVersion());
        vo.setVideoUrl(asset.getVideoUrl());
        vo.setThumbnailUrl(asset.getThumbnailUrl());
        vo.setIsActive(asset.getIsActive() != null && asset.getIsActive() == 1);
        vo.setCreatedAt(asset.getCreatedAt());

        if (metadata != null) {
            vo.setModel(metadata.getModel());
            vo.setPrompt(metadata.getPrompt());
            vo.setReferenceUrls(metadata.getReferenceUrls());
            vo.setGenerationParams(metadata.getGenerationParams());
        }

        return vo;
    }

    @Override
    @Transactional
    public ShotDetailVO createShot(Long episodeId, ShotUpdateRequest request) {
        log.info("创建分镜: episodeId={}, request={}", episodeId, request);

        // 检查剧集是否存在
        Episode episode = episodeMapper.selectById(episodeId);
        if (episode == null) {
            throw new BusinessException("剧集不存在");
        }

        // 自动计算待审核列表内分镜编号（添加到待审核列表末尾）
        Integer maxUnlockedShotNumber = shotMapper.selectMaxUnlockedShotNumber(
                episodeId,
                ShotStatus.APPROVED.getCode()
        );
        int shotNumber = (maxUnlockedShotNumber != null ? maxUnlockedShotNumber : 0) + 1;

        // 创建新分镜
        Shot shot = new Shot();
        shot.setEpisodeId(episodeId);
        shot.setShotNumber(shotNumber);
        shot.setStartTime(0);
        shot.setEndTime(5);
        shot.setDuration(5);
        shot.setGenerationStatus(ShotGenerationStatus.PENDING.getCode());
        shot.setStatus(ShotStatus.PENDING_REVIEW.getCode());
        shot.setCreatedAt(LocalDateTime.now());
        shot.setUpdatedAt(LocalDateTime.now());

        // 填充请求数据
        if (request != null) {
            if (request.getShotName() != null && !request.getShotName().trim().isEmpty()) {
                shot.setShotName(request.getShotName().trim());
            }
            if (request.getSceneName() != null) {
                shot.setSceneName(request.getSceneName());
            }
            if (request.getDescription() != null) {
                shot.setDescription(request.getDescription());
            }
            if (request.getDuration() != null) {
                shot.setDuration(Math.min(request.getDuration(), 15));
                shot.setEndTime(shot.getDuration());
            }
            if (request.getShotType() != null) {
                shot.setShotType(request.getShotType());
            }
            if (request.getSoundEffect() != null) {
                shot.setSoundEffect(request.getSoundEffect());
            }
            if (request.getCameraAngle() != null) {
                shot.setCameraAngle(request.getCameraAngle());
            }
            if (request.getCameraMovement() != null) {
                shot.setCameraMovement(request.getCameraMovement());
            }
        }

        shotMapper.insert(shot);
        log.info("分镜创建成功: shotId={}, shotNumber={}", shot.getId(), shotNumber);

        return getShotDetail(shot.getId());
    }

    @Override
    @Transactional
    public void deleteShot(Long shotId) {
        log.info("删除分镜: shotId={}", shotId);

        Shot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            throw new BusinessException("分镜不存在");
        }
        ensureShotCanBeModified(shot);

        Long episodeId = shot.getEpisodeId();
        Integer deletedNumber = shot.getShotNumber();

        // 删除分镜（逻辑删除）
        shotMapper.deleteById(shotId);

        // 删除相关的角色关联
        LambdaQueryWrapper<ShotCharacter> charWrapper = new LambdaQueryWrapper<>();
        charWrapper.eq(ShotCharacter::getShotId, shotId);
        shotCharacterMapper.delete(charWrapper);

        // 删除相关的道具关联
        LambdaQueryWrapper<ShotProp> propWrapper = new LambdaQueryWrapper<>();
        propWrapper.eq(ShotProp::getShotId, shotId);
        shotPropMapper.delete(propWrapper);

        // 删除相关的参考图
        LambdaQueryWrapper<ShotReferenceImage> refWrapper = new LambdaQueryWrapper<>();
        refWrapper.eq(ShotReferenceImage::getShotId, shotId);
        shotReferenceImageMapper.delete(refWrapper);

        // 批量删除视频资产及其元数据
        LambdaQueryWrapper<ShotVideoAsset> videoWrapper = new LambdaQueryWrapper<>();
        videoWrapper.eq(ShotVideoAsset::getShotId, shotId).select(ShotVideoAsset::getId);
        List<Long> videoAssetIds = shotVideoAssetMapper.selectList(videoWrapper)
                .stream().map(ShotVideoAsset::getId).collect(Collectors.toList());

        if (!videoAssetIds.isEmpty()) {
            // 批量删除元数据
            LambdaQueryWrapper<ShotVideoAssetMetadata> metaWrapper = new LambdaQueryWrapper<>();
            metaWrapper.in(ShotVideoAssetMetadata::getShotVideoAssetId, videoAssetIds);
            shotVideoAssetMetadataMapper.delete(metaWrapper);
            // 批量删除视频资产
            LambdaQueryWrapper<ShotVideoAsset> deleteVideoWrapper = new LambdaQueryWrapper<>();
            deleteVideoWrapper.in(ShotVideoAsset::getId, videoAssetIds);
            shotVideoAssetMapper.delete(deleteVideoWrapper);
        }

        // 调整后面分镜的编号（-1）
        if (deletedNumber != null) {
            shotMapper.decrementUnlockedShotNumbers(episodeId, deletedNumber, ShotStatus.APPROVED.getCode());
        }

        log.info("分镜删除成功: shotId={}", shotId);
    }

    @Override
    @Transactional
    public void reorderShots(Long episodeId, List<Long> shotIds, Integer reviewStatus) {
        log.info("重新排序分镜开始: episodeId={}, reviewStatus={}, shotIds={}", episodeId, reviewStatus, shotIds);
        if (shotIds == null || shotIds.isEmpty()) {
            throw new BusinessException("分镜顺序不能为空");
        }
        int targetReviewStatus;
        if (reviewStatus == null) {
            targetReviewStatus = ShotStatus.PENDING_REVIEW.getCode();
        } else {
            targetReviewStatus = reviewStatus;
        }
        if (!ShotStatus.PENDING_REVIEW.getCode().equals(targetReviewStatus)
                && !ShotStatus.APPROVED.getCode().equals(targetReviewStatus)) {
            throw new BusinessException("不支持的分镜排序状态");
        }

        Set<Long> requestIds = new HashSet<>(shotIds);
        if (requestIds.size() != shotIds.size()) {
            throw new BusinessException("分镜顺序包含重复分镜");
        }

        List<Shot> existingShots = shotMapper.selectOrderFieldsByEpisodeId(episodeId);
        List<Shot> sortableShots = existingShots.stream()
                .filter(shot -> ShotStatus.APPROVED.getCode().equals(targetReviewStatus)
                        ? ShotStatus.APPROVED.getCode().equals(shot.getStatus())
                        : !ShotStatus.APPROVED.getCode().equals(shot.getStatus()))
                .collect(Collectors.toList());
        if (sortableShots.size() != shotIds.size()) {
            throw new BusinessException("分镜数量不一致，请刷新后重试");
        }
        Set<Long> sortableIds = sortableShots.stream().map(Shot::getId).collect(Collectors.toSet());
        if (!sortableIds.equals(requestIds)) {
            throw new BusinessException("只能调整当前列表内的分镜顺序");
        }

        Map<Long, Integer> currentNumberById = sortableShots.stream()
                .collect(Collectors.toMap(Shot::getId, Shot::getShotNumber));
        List<Shot> changedShots = new ArrayList<>();
        for (int i = 0; i < shotIds.size(); i++) {
            Long shotId = shotIds.get(i);
            int newNumber = i + 1;
            if (!Integer.valueOf(newNumber).equals(currentNumberById.get(shotId))) {
                Shot shot = new Shot();
                shot.setId(shotId);
                shot.setShotNumber(newNumber);
                changedShots.add(shot);
            }
        }

        if (changedShots.isEmpty()) {
            log.info("分镜排序未变化: episodeId={}", episodeId);
            return;
        }

        int updated = shotMapper.batchUpdateShotNumbers(episodeId, changedShots);
        log.info("分镜排序完成: episodeId={}, changed={}, updated={}", episodeId, changedShots.size(), updated);
    }

    @Override
    public Map<String, Object> getVideoCreditPreview(Long shotId) {
        Shot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            throw new BusinessException("分镜不存在");
        }

        int duration = shot.getDuration() != null ? shot.getDuration() : CreditConstants.DEFAULT_DURATION;
        String resolution = shot.getResolution() != null ? shot.getResolution() : "720p";
        String videoModel = shot.getVideoModel();
        int creditsPerSecond = CreditConstants.getCreditsPerSecond(resolution, videoModel);
        int totalCredits = CreditConstants.calculateCredits(resolution, duration, videoModel);

        Long userId = UserContextHolder.getUserId();
        Integer currentCredits = userId != null ? userService.getUserCredits(userId) : null;

        Map<String, Object> result = new HashMap<>();
        result.put("duration", duration);
        result.put("resolution", resolution);
        result.put("creditsPerSecond", creditsPerSecond);
        result.put("totalCredits", totalCredits);
        result.put("currentCredits", currentCredits);
        result.put("sufficient", currentCredits != null && currentCredits >= totalCredits);

        return result;
    }

    @Override
    public String getShotVideoUrl(Long shotId) {
        // 优先从激活的视频资产获取
        LambdaQueryWrapper<ShotVideoAsset> assetWrapper = new LambdaQueryWrapper<>();
        assetWrapper.eq(ShotVideoAsset::getShotId, shotId)
                .eq(ShotVideoAsset::getIsActive, 1)
                .last("LIMIT 1");
        ShotVideoAsset activeAsset = shotVideoAssetMapper.selectOne(assetWrapper);
        if (activeAsset != null && activeAsset.getVideoUrl() != null && !activeAsset.getVideoUrl().isBlank()) {
            return activeAsset.getVideoUrl();
        }

        // 回退到 shot 表
        Shot shot = shotMapper.selectById(shotId);
        if (shot != null && shot.getVideoUrl() != null && !shot.getVideoUrl().isBlank()) {
            return shot.getVideoUrl();
        }

        return null;
    }
}
