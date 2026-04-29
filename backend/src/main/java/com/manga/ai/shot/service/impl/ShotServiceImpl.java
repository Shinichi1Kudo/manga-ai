package com.manga.ai.shot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.manga.ai.asset.entity.RoleAsset;
import com.manga.ai.asset.mapper.RoleAssetMapper;
import com.manga.ai.common.constants.CreditConstants;
import com.manga.ai.common.enums.CreditUsageType;
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
    private final SeriesMapper seriesMapper;
    private final VideoMetadataMapper videoMetadataMapper;
    private final SeedanceService seedanceService;
    private final ShotReferenceImageMapper shotReferenceImageMapper;
    private final ShotVideoAssetMapper shotVideoAssetMapper;
    private final ShotVideoAssetMetadataMapper shotVideoAssetMetadataMapper;
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
     * @param videoModel 前端模型标识 (seedance-2.0-fast, seedance-2.0)
     * @return API模型名称
     */
    private String convertToApiModel(String videoModel) {
        if (videoModel == null || videoModel.isEmpty()) {
            return "doubao-seedance-2-0-fast-260128"; // 默认 Fast 模型
        }
        switch (videoModel) {
            case "seedance-2.0":
            case "doubao-seedance-2-0-260128":
                return "doubao-seedance-2-0-260128"; // VIP 模型
            case "seedance-2.0-fast":
            case "doubao-seedance-2-0-fast-260128":
            default:
                return "doubao-seedance-2-0-fast-260128"; // Fast VIP 模型
        }
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
            shot.setDescriptionEdited(true);  // 标记用户已编辑剧情
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
        if (request.getResolution() != null) {
            shot.setResolution(request.getResolution());
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
            shot.setShotName(request.getShotName());
        }
        if (request.getSceneName() != null) {
            shot.setSceneName(request.getSceneName());
            shot.setSceneEdited(true);  // 标记用户已编辑场景
        }
        if (request.getUserPrompt() != null) {
            shot.setUserPrompt(request.getUserPrompt());
        }
        if (request.getGenerationStatus() != null) {
            shot.setGenerationStatus(request.getGenerationStatus());
        }
        if (request.getVideoModel() != null) {
            shot.setVideoModel(request.getVideoModel());
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
    public void generateVideo(Long shotId) {
        log.info("开始生成视频: shotId={}", shotId);

        // 同步更新状态为生成中，确保状态立即持久化
        Shot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            log.error("分镜不存在: shotId={}", shotId);
            throw new BusinessException("分镜不存在");
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
        shot.setDeductedCredits(requiredCredits);
        shot.setGenerationStatus(ShotGenerationStatus.GENERATING.getCode());
        shot.setGenerationError(null);
        shot.setGenerationStartTime(LocalDateTime.now());
        shot.setUpdatedAt(LocalDateTime.now());
        shotMapper.updateById(shot);
        log.info("已更新分镜状态为生成中: shotId={}", shotId);

        // 通过代理异步执行视频生成（确保真正的异步）
        self.doGenerateVideo(shotId);
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
            request.setModel(convertToApiModel(shot.getVideoModel()));

            // 设置视频尺寸
            int[] size = calculateVideoSize(shot.getResolution(), shot.getAspectRatio());
            request.setWidth(size[0]);
            request.setHeight(size[1]);

            // 调用Seedance生成视频
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
                shot.setDeductedCredits(null); // 成功后清除扣除积分记录
                shot.setUpdatedAt(LocalDateTime.now());
                shotMapper.updateById(shot);

                // 保存元数据
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
            // 提取关键错误信息，转换为用户友好的提示
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.contains("ModelNotOpen")) {
                shot.setGenerationError("模型未开通，请在火山引擎控制台开通 Seedance 2.0 模型");
            } else if (errorMsg != null && errorMsg.contains("SensitiveContent")) {
                shot.setGenerationError("内容审核未通过，请修改分镜描述后重试");
            } else if (errorMsg != null && errorMsg.contains("copyright")) {
                shot.setGenerationError("内容审核未通过，请修改分镜描述后重试");
            } else {
                shot.setGenerationError("视频生成失败，请稍后重试");
            }

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
    public void generateVideoWithReferences(Long shotId, List<String> referenceUrls) {
        log.info("开始生成视频(带参考图): shotId={}, referenceUrls={}", shotId, referenceUrls);

        // 获取分镜信息
        Shot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            log.error("分镜不存在: shotId={}", shotId);
            throw new BusinessException("分镜不存在");
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

        // 更新状态并记录扣除的积分
        LambdaUpdateWrapper<Shot> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Shot::getId, shotId)
                .set(Shot::getGenerationStatus, ShotGenerationStatus.GENERATING.getCode())
                .set(Shot::getGenerationError, null)
                .set(Shot::getDeductedCredits, requiredCredits)
                .set(Shot::getGenerationStartTime, LocalDateTime.now())
                .set(Shot::getUpdatedAt, LocalDateTime.now());
        int updated = shotMapper.update(null, updateWrapper);

        if (updated == 0) {
            log.error("分镜更新失败: shotId={}", shotId);
            // 返还积分
            userService.refundCredits(userId, requiredCredits, "视频生成失败返还-分镜" + shot.getShotNumber(), shotId, "SHOT");
            throw new BusinessException("分镜更新失败");
        }
        log.info("已更新分镜状态为生成中: shotId={}", shotId);

        // 通过代理异步执行视频生成（确保真正的异步）
        self.doGenerateVideoWithReferences(shotId, referenceUrls);
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

            // 构建参考图列表
            List<AssetReference> references = new ArrayList<>();

            // 优先使用前端传入的参考图 URL
            if (referenceUrls != null && !referenceUrls.isEmpty()) {
                log.info("使用前端传入的参考图: {} 张", referenceUrls.size());
                for (String url : referenceUrls) {
                    if (url != null && !url.isEmpty()) {
                        AssetReference ref = new AssetReference();
                        ref.setImageUrl(url);
                        ref.setType("frontend");
                        ref.setName("用户选择");
                        references.add(ref);
                    }
                }
            } else {
                // 如果前端没有传入，从数据库获取用户选择的参考图
                references = getReferenceImagesFromDB(shotId);
                log.info("从 shot_reference_image 表获取参考图数量: {}", references.size());

                // 合并自动匹配的参考图（场景图等），确保场景图不丢失
                List<AssetReference> autoRefs = result.getReferenceImages();
                if (autoRefs != null && !autoRefs.isEmpty()) {
                    log.info("自动匹配参考图数量: {}", autoRefs.size());
                    for (AssetReference autoRef : autoRefs) {
                        references.add(autoRef);
                    }
                }
            }

            // 去重：基于图片URL去重
            List<AssetReference> dedupedReferences = new ArrayList<>();
            Set<String> seenUrls = new HashSet<>();
            for (AssetReference ref : references) {
                if (ref.getImageUrl() != null && !seenUrls.contains(ref.getImageUrl())) {
                    seenUrls.add(ref.getImageUrl());
                    dedupedReferences.add(ref);
                }
            }
            references = dedupedReferences;
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
            request.setModel(convertToApiModel(shot.getVideoModel()));

            // 设置视频尺寸
            int[] size = calculateVideoSize(shot.getResolution(), shot.getAspectRatio());
            request.setWidth(size[0]);
            request.setHeight(size[1]);

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
                shot.setDeductedCredits(null); // 成功后清除扣除积分记录
                shot.setUpdatedAt(LocalDateTime.now());
                shotMapper.updateById(shot);

                // 保存元数据
                saveVideoMetadata(shot, result.getPrompt(), response);

                // 保存视频版本资产
                saveVideoAsset(shot, result.getPrompt(), response, referenceUrls, durationSeconds);

                log.info("视频生成完成(带参考图): shotId={}, 耗时: {}分{}秒", shotId, durationSeconds / 60, durationSeconds % 60);
            } else {
                String errorMsg = response.getErrorMessage();
                log.error("视频生成失败(带参考图): shotId={}, status={}, errorMessage={}", shotId, response.getStatus(), errorMsg);
                throw new RuntimeException("视频生成失败: " + (errorMsg != null ? errorMsg : "状态-" + response.getStatus()));
            }
        } catch (Exception e) {
            log.error("视频生成异常(带参考图): shotId={}", shotId, e);
            shot.setGenerationStatus(ShotGenerationStatus.FAILED.getCode());
            // 提取关键错误信息，转换为用户友好的提示
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.contains("ModelNotOpen")) {
                shot.setGenerationError("模型未开通，请在火山引擎控制台开通 Seedance 2.0 模型");
            } else if (errorMsg != null && errorMsg.contains("SensitiveContent")) {
                shot.setGenerationError("内容审核未通过，请修改分镜描述后重试（避免戏剧化镜头语言）");
            } else if (errorMsg != null && errorMsg.contains("copyright")) {
                shot.setGenerationError("内容审核未通过，请修改分镜描述后重试（避免标志性镜头描述）");
            } else if (errorMsg != null && errorMsg.contains("超时")) {
                shot.setGenerationError("视频生成超时，请稍后重试或减少视频时长");
            } else {
                shot.setGenerationError("视频生成失败，请稍后重试");
            }

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
     * 根据名称查找资产（支持模糊匹配场景名称）
     */
    private AssetReference findAssetByName(String name, Long seriesId) {
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
        return assets.stream().map(this::convertToVideoAssetVO).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rollbackToVersion(Long shotId, Long assetId) {
        Shot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            throw new BusinessException("分镜不存在");
        }

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
        metadata.setModel("seedance-2.0");
        metadata.setPrompt(prompt);
        if (referenceUrls != null && !referenceUrls.isEmpty()) {
            metadata.setReferenceUrls(String.join(",", referenceUrls));
        }
        metadata.setCreatedAt(LocalDateTime.now());
        shotVideoAssetMetadataMapper.insert(metadata);

        log.info("保存视频版本资产: shotId={}, version={}, assetId={}", shot.getId(), newVersion, videoAsset.getId());
    }

    /**
     * 转换为 ShotVideoAssetVO
     */
    private ShotVideoAssetVO convertToVideoAssetVO(ShotVideoAsset asset) {
        ShotVideoAssetVO vo = new ShotVideoAssetVO();
        vo.setId(asset.getId());
        vo.setShotId(asset.getShotId());
        vo.setVersion(asset.getVersion());
        vo.setVideoUrl(asset.getVideoUrl());
        vo.setThumbnailUrl(asset.getThumbnailUrl());
        vo.setIsActive(asset.getIsActive() != null && asset.getIsActive() == 1);
        vo.setCreatedAt(asset.getCreatedAt());

        // 获取元数据
        LambdaQueryWrapper<ShotVideoAssetMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ShotVideoAssetMetadata::getShotVideoAssetId, asset.getId());
        ShotVideoAssetMetadata metadata = shotVideoAssetMetadataMapper.selectOne(wrapper);
        if (metadata != null) {
            vo.setModel(metadata.getModel());
            vo.setPrompt(metadata.getPrompt());
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

        // 自动计算分镜编号（添加到末尾）
        LambdaQueryWrapper<Shot> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Shot::getEpisodeId, episodeId)
               .orderByDesc(Shot::getShotNumber)
               .last("LIMIT 1");
        Shot lastShot = shotMapper.selectOne(wrapper);
        int shotNumber = (lastShot != null && lastShot.getShotNumber() != null)
                    ? lastShot.getShotNumber() + 1
                    : 1;

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
            shotMapper.decrementShotNumbers(episodeId, deletedNumber);
        }

        log.info("分镜删除成功: shotId={}", shotId);
    }

    @Override
    @Transactional
    public void reorderShots(Long episodeId, List<Long> shotIds) {
        log.info("重新排序分镜开始: episodeId={}, shotIds={}", episodeId, shotIds);

        // 验证所有分镜都属于该剧集
        for (Long shotId : shotIds) {
            Shot shot = shotMapper.selectById(shotId);
            if (shot == null || !episodeId.equals(shot.getEpisodeId())) {
                throw new BusinessException("分镜不存在或不属于该剧集: " + shotId);
            }
        }

        // 按新顺序更新分镜编号
        for (int i = 0; i < shotIds.size(); i++) {
            Long shotId = shotIds.get(i);
            int newNumber = i + 1;

            // 使用 LambdaUpdateWrapper 只更新 shotNumber 字段
            LambdaUpdateWrapper<Shot> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(Shot::getId, shotId)
                        .set(Shot::getShotNumber, newNumber)
                        .set(Shot::getUpdatedAt, LocalDateTime.now());
            int updated = shotMapper.update(null, updateWrapper);
            log.info("更新分镜编号: shotId={}, newNumber={}, updated={}", shotId, newNumber, updated);
        }

        log.info("分镜排序完成: episodeId={}", episodeId);
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
}
