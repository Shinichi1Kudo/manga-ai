package com.manga.ai.series.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.manga.ai.asset.entity.AssetMetadata;
import com.manga.ai.asset.entity.RoleAsset;
import com.manga.ai.asset.mapper.AssetMetadataMapper;
import com.manga.ai.asset.mapper.RoleAssetMapper;
import com.manga.ai.common.enums.AssetStatus;
import com.manga.ai.common.enums.RoleStatus;
import com.manga.ai.common.enums.SeriesStatus;
import com.manga.ai.common.enums.ViewType;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.image.dto.ImageGenerateRequest;
import com.manga.ai.image.dto.ImageGenerateResponse;
import com.manga.ai.image.service.ImageGenerateService;
import com.manga.ai.nlp.model.CharacterProfile;
import com.manga.ai.nlp.service.NLPExtractService;
import com.manga.ai.role.entity.Role;
import com.manga.ai.role.mapper.RoleMapper;
import com.manga.ai.series.dto.SeriesDetailVO;
import com.manga.ai.series.dto.SeriesInitRequest;
import com.manga.ai.series.dto.SeriesProgressMessage;
import com.manga.ai.series.dto.SeriesProgressVO;
import com.manga.ai.series.dto.SeriesVideoAssetsVO;
import com.manga.ai.series.dto.EpisodeVideoAssetsVO;
import com.manga.ai.series.dto.ShotVideoInfoVO;
import com.manga.ai.series.entity.Series;
import com.manga.ai.series.mapper.SeriesMapper;
import com.manga.ai.series.service.SeriesService;
import com.manga.ai.episode.entity.Episode;
import com.manga.ai.episode.mapper.EpisodeMapper;
import com.manga.ai.shot.entity.Shot;
import com.manga.ai.shot.mapper.ShotMapper;
import com.manga.ai.shot.entity.ShotVideoAsset;
import com.manga.ai.shot.mapper.ShotVideoAssetMapper;
import com.manga.ai.user.service.impl.UserServiceImpl.UserContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 系列服务实现
 */
@Slf4j
@Service
public class SeriesServiceImpl implements SeriesService {

    private final SeriesMapper seriesMapper;
    private final RoleMapper roleMapper;
    private final RoleAssetMapper roleAssetMapper;
    private final AssetMetadataMapper assetMetadataMapper;
    private final NLPExtractService nlpExtractService;
    private final ImageGenerateService imageGenerateService;
    private final SimpMessagingTemplate messagingTemplate;
    private final EpisodeMapper episodeMapper;
    private final ShotMapper shotMapper;
    private final ShotVideoAssetMapper shotVideoAssetMapper;

    // 自注入，用于调用 @Async 方法（解决 Spring 代理问题）
    private SeriesService self;

    @Autowired
    public SeriesServiceImpl(SeriesMapper seriesMapper,
                             RoleMapper roleMapper,
                             RoleAssetMapper roleAssetMapper,
                             AssetMetadataMapper assetMetadataMapper,
                             NLPExtractService nlpExtractService,
                             @Lazy ImageGenerateService imageGenerateService,
                             SimpMessagingTemplate messagingTemplate,
                             @Lazy SeriesService self,
                             EpisodeMapper episodeMapper,
                             ShotMapper shotMapper,
                             ShotVideoAssetMapper shotVideoAssetMapper) {
        this.seriesMapper = seriesMapper;
        this.roleMapper = roleMapper;
        this.roleAssetMapper = roleAssetMapper;
        this.assetMetadataMapper = assetMetadataMapper;
        this.nlpExtractService = nlpExtractService;
        this.imageGenerateService = imageGenerateService;
        this.messagingTemplate = messagingTemplate;
        this.self = self;
        this.episodeMapper = episodeMapper;
        this.shotMapper = shotMapper;
        this.shotVideoAssetMapper = shotVideoAssetMapper;
    }

    @Value("${storage.project-path:./storage/projects}")
    private String projectPath;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SeriesDetailVO initSeries(SeriesInitRequest request) {
        // 获取当前用户ID
        Long userId = UserContextHolder.getUserId();

        // 1. 创建系列记录
        Series series = new Series();
        series.setUserId(userId);
        series.setSeriesName(request.getSeriesName());
        series.setOutline(request.getOutline());
        series.setBackground(request.getBackground());
        series.setCharacterIntro(request.getCharacterIntro());
        // 系列风格优先使用 seriesStyle，兼容旧格式 styleKeywords
        if (request.getSeriesStyle() != null && !request.getSeriesStyle().isEmpty()) {
            series.setStyleKeywords(request.getSeriesStyle());
        } else {
            series.setStyleKeywords(request.getStyleKeywords());
        }
        series.setColorPreference(request.getColorPreference());
        series.setArtStyleRef(request.getArtStyleRef());
        series.setAspectRatio(request.getAspectRatio());
        series.setQuality(request.getQuality());
        series.setStatus(SeriesStatus.INITIALIZING.getCode());
        series.setCreatedAt(LocalDateTime.now());
        series.setUpdatedAt(LocalDateTime.now());
        seriesMapper.insert(series);

        // 2. 创建目录结构
        String projectDir = createProjectDirectory(series.getId());
        series.setProjectPath(projectDir);
        seriesMapper.updateById(series);

        // 3. 异步处理角色提取和图片生成（通过 self 调用，确保 @Async 生效）
        if (request.getCharactersJson() != null && !request.getCharactersJson().isEmpty()) {
            // 新格式：直接使用前端传来的角色数据
            self.asyncProcessCharacters(series.getId(), request.getCharactersJson());
        } else if (request.getCharacterIntro() != null && !request.getCharacterIntro().isEmpty()) {
            // 旧格式：NLP 提取
            self.asyncProcessRoleExtract(series.getId(), request.getCharacterIntro());
        } else {
            throw new BusinessException("请添加至少一个角色");
        }

        return convertToVO(series);
    }

    @Override
    public SeriesDetailVO getSeriesDetail(Long seriesId) {
        Series series = seriesMapper.selectById(seriesId);
        if (series == null) {
            throw new BusinessException("系列不存在");
        }

        SeriesDetailVO vo = convertToVO(series);

        // 查询角色列表
        LambdaQueryWrapper<Role> roleWrapper = new LambdaQueryWrapper<>();
        roleWrapper.eq(Role::getSeriesId, seriesId)
                .orderByAsc(Role::getRoleCode);
        List<Role> roles = roleMapper.selectList(roleWrapper);

        // 统计角色数量
        vo.setRoleCount(roles.size());

        // 设置角色名称列表
        List<String> roleNames = roles.stream()
                .map(Role::getRoleName)
                .collect(java.util.stream.Collectors.toList());
        vo.setRoles(roleNames);

        // 统计已确认角色数量
        Long confirmedRoles = roles.stream()
                .filter(r -> r.getStatus() >= 2)
                .count();
        vo.setConfirmedRoleCount(confirmedRoles.intValue());

        return vo;
    }

    @Override
    public List<SeriesDetailVO> getSeriesList(Integer page, Integer pageSize) {
        Long userId = UserContextHolder.getUserId();
        LambdaQueryWrapper<Series> wrapper = new LambdaQueryWrapper<>();
        if (userId != null) {
            wrapper.eq(Series::getUserId, userId);
        }
        wrapper.orderByDesc(Series::getCreatedAt);

        // 分页查询
        int offset = (page - 1) * pageSize;
        wrapper.last("LIMIT " + pageSize + " OFFSET " + offset);
        List<Series> seriesList = seriesMapper.selectList(wrapper);

        // 批量查询所有系列的角色数量，避免 N+1 查询
        java.util.Map<Long, Integer> roleCountMap = new java.util.HashMap<>();
        if (!seriesList.isEmpty()) {
            List<Long> seriesIds = seriesList.stream().map(Series::getId).collect(java.util.stream.Collectors.toList());
            LambdaQueryWrapper<Role> roleWrapper = new LambdaQueryWrapper<>();
            roleWrapper.in(Role::getSeriesId, seriesIds);
            List<Role> allRoles = roleMapper.selectList(roleWrapper);

            // 统计每个系列的角色数量
            for (Role role : allRoles) {
                roleCountMap.merge(role.getSeriesId(), 1, Integer::sum);
            }
        }

        return seriesList.stream().map(series -> {
            SeriesDetailVO vo = convertToVO(series);
            vo.setRoleCount(roleCountMap.getOrDefault(series.getId(), 0));
            return vo;
        }).collect(java.util.stream.Collectors.toList());
    }

    @Override
    public Integer getSeriesCount() {
        Long userId = UserContextHolder.getUserId();
        LambdaQueryWrapper<Series> wrapper = new LambdaQueryWrapper<>();
        if (userId != null) {
            wrapper.eq(Series::getUserId, userId);
        }
        return Math.toIntExact(seriesMapper.selectCount(wrapper));
    }

    @Override
    public List<SeriesDetailVO> getAllSeries() {
        Long userId = UserContextHolder.getUserId();
        LambdaQueryWrapper<Series> wrapper = new LambdaQueryWrapper<>();
        if (userId != null) {
            wrapper.eq(Series::getUserId, userId);
        }
        wrapper.orderByDesc(Series::getCreatedAt);
        List<Series> seriesList = seriesMapper.selectList(wrapper);

        // 批量查询所有系列的角色数量，避免 N+1 查询
        java.util.Map<Long, Integer> roleCountMap = new java.util.HashMap<>();
        if (!seriesList.isEmpty()) {
            List<Long> seriesIds = seriesList.stream().map(Series::getId).collect(java.util.stream.Collectors.toList());
            LambdaQueryWrapper<Role> roleWrapper = new LambdaQueryWrapper<>();
            roleWrapper.in(Role::getSeriesId, seriesIds);
            List<Role> allRoles = roleMapper.selectList(roleWrapper);

            // 统计每个系列的角色数量
            for (Role role : allRoles) {
                roleCountMap.merge(role.getSeriesId(), 1, Integer::sum);
            }
        }

        return seriesList.stream().map(series -> {
            SeriesDetailVO vo = convertToVO(series);
            vo.setRoleCount(roleCountMap.getOrDefault(series.getId(), 0));
            return vo;
        }).collect(java.util.stream.Collectors.toList());
    }

    @Override
    public SeriesProgressVO getSeriesProgress(Long seriesId) {
        Series series = seriesMapper.selectById(seriesId);
        if (series == null) {
            throw new BusinessException("系列不存在");
        }

        SeriesProgressVO progress = new SeriesProgressVO();
        progress.setSeriesId(seriesId);
        progress.setSeriesName(series.getSeriesName());
        progress.setSeriesStatus(series.getStatus());

        // 统计角色
        LambdaQueryWrapper<Role> roleWrapper = new LambdaQueryWrapper<>();
        roleWrapper.eq(Role::getSeriesId, seriesId);
        Long totalRoles = roleMapper.selectCount(roleWrapper);
        progress.setTotalRoles(totalRoles.intValue());

        LambdaQueryWrapper<Role> confirmedWrapper = new LambdaQueryWrapper<>();
        confirmedWrapper.eq(Role::getSeriesId, seriesId)
                .ge(Role::getStatus, 2);
        Long confirmedRoles = roleMapper.selectCount(confirmedWrapper);
        progress.setConfirmedRoles(confirmedRoles.intValue());

        // 计算进度
        if (totalRoles > 0) {
            // 状态为待审核（1）时显示100%
            if (series.getStatus() == 1) {
                progress.setProgressPercent(100);
            } else {
                progress.setProgressPercent((int) (confirmedRoles * 100 / totalRoles));
            }
        }

        return progress;
    }

    @Override
    public void updateSeriesStatus(Long seriesId, Integer status) {
        Series series = seriesMapper.selectById(seriesId);
        if (series == null) {
            throw new BusinessException("系列不存在");
        }
        series.setStatus(status);
        series.setUpdatedAt(LocalDateTime.now());
        seriesMapper.updateById(series);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void lockSeries(Long seriesId) {
        Series series = seriesMapper.selectById(seriesId);
        if (series == null) {
            throw new BusinessException("系列不存在");
        }

        if (SeriesStatus.LOCKED.getCode().equals(series.getStatus())) {
            throw new BusinessException("系列已锁定");
        }

        // 检查所有角色是否已确认
        LambdaQueryWrapper<Role> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Role::getSeriesId, seriesId)
                .lt(Role::getStatus, 2);
        Long unconfirmedCount = roleMapper.selectCount(wrapper);
        if (unconfirmedCount > 0) {
            throw new BusinessException("存在 " + unconfirmedCount + " 个未确认的角色，无法锁定");
        }

        // 锁定所有角色
        Role updateRole = new Role();
        updateRole.setStatus(RoleStatus.LOCKED.getCode());
        roleMapper.update(updateRole, new LambdaQueryWrapper<Role>()
                .eq(Role::getSeriesId, seriesId));

        // 锁定系列
        series.setStatus(SeriesStatus.LOCKED.getCode());
        series.setUpdatedAt(LocalDateTime.now());
        seriesMapper.updateById(series);

        log.info("系列已锁定: seriesId={}", seriesId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateSeries(Long seriesId, String seriesName, String outline, String background, String styleKeywords) {
        Series series = seriesMapper.selectById(seriesId);
        if (series == null) {
            throw new BusinessException("系列不存在");
        }

        // 标题、背景设定、剧本大纲不受锁定限制，始终可修改
        if (seriesName != null && !seriesName.trim().isEmpty()) {
            series.setSeriesName(seriesName.trim());
        }
        if (outline != null) {
            series.setOutline(outline);
        }
        if (background != null) {
            series.setBackground(background);
        }
        if (styleKeywords != null) {
            series.setStyleKeywords(styleKeywords);
        }
        series.setUpdatedAt(LocalDateTime.now());
        seriesMapper.updateById(series);

        log.info("更新系列信息: seriesId={}, styleKeywords={}", seriesId, styleKeywords);
    }

    /**
     * 创建项目目录结构
     */
    private String createProjectDirectory(Long seriesId) {
        String projectDir = Paths.get(projectPath, String.valueOf(seriesId)).toString();

        try {
            Files.createDirectories(Paths.get(projectDir));
            Files.createDirectories(Paths.get(projectDir, "roles"));
            Files.createDirectories(Paths.get(projectDir, "assets"));
            Files.createDirectories(Paths.get(projectDir, "temp"));

            Path configFile = Paths.get(projectDir, "series.json");
            if (!Files.exists(configFile)) {
                Files.writeString(configFile, "{}");
            }

            log.info("创建项目目录: {}", projectDir);
        } catch (IOException e) {
            log.error("创建项目目录失败", e);
            throw new BusinessException("创建项目目录失败: " + e.getMessage());
        }

        return projectDir;
    }

    /**
     * 异步处理角色提取和图片生成
     */
    @Override
    @Async("taskExecutor")
    public void asyncProcessRoleExtract(Long seriesId, String characterIntro) {
        log.info("开始异步处理角色提取: seriesId={}", seriesId);

        try {
            // 1. 调用 NLP 服务提取角色
            List<CharacterProfile> profiles = nlpExtractService.extractCharacters(characterIntro);
            log.info("NLP提取到 {} 个角色", profiles.size());

            int roleIndex = 1;
            for (CharacterProfile profile : profiles) {
                // 2. 创建角色记录
                Role role = new Role();
                role.setSeriesId(seriesId);
                role.setRoleName(profile.getName() != null ? profile.getName() : "角色" + roleIndex);
                role.setRoleCode(String.format("ROLE_%03d", roleIndex));
                role.setStatus(RoleStatus.PENDING_REVIEW.getCode()); // 直接设为待审核
                role.setAge(profile.getAge());
                role.setGender(profile.getGender());
                role.setAppearance(profile.getAppearance());
                role.setPersonality(profile.getPersonality());
                role.setClothing(profile.getClothing());
                role.setOriginalText(profile.getOriginalText());
                role.setExtractConfidence(profile.getOverallConfidence());
                role.setCreatedAt(LocalDateTime.now());
                role.setUpdatedAt(LocalDateTime.now());
                roleMapper.insert(role);

                log.info("创建角色: id={}, name={}", role.getId(), role.getRoleName());

                // 3. 生成 Mock 图片资产
                generateMockAssets(role);

                roleIndex++;
            }

            // 4. 更新系列状态为待审核
            updateSeriesStatus(seriesId, SeriesStatus.PENDING_REVIEW.getCode());

            log.info("角色提取和图片生成完成: seriesId={}, count={}", seriesId, roleIndex - 1);
        } catch (Exception e) {
            log.error("角色提取失败: seriesId={}", seriesId, e);
            // 即使失败也更新状态，避免卡住
            updateSeriesStatus(seriesId, SeriesStatus.PENDING_REVIEW.getCode());
        }
    }

    /**
     * 异步处理前端传来的角色数据（并行处理）
     */
    @Override
    @Async("taskExecutor")
    public void asyncProcessCharacters(Long seriesId, String charactersJson) {
        log.info("开始异步处理角色数据: seriesId={}", seriesId);

        try {
            JSONArray characters = JSON.parseArray(charactersJson);
            int totalRoles = characters.size();
            log.info("解析到 {} 个角色", totalRoles);

            // 发送开始处理消息
            sendProgress(seriesId, "PROCESSING", 0, totalRoles, 0, "开始处理角色...");

            // 使用 AtomicInteger 线程安全计数
            AtomicInteger completedRoles = new AtomicInteger(0);
            AtomicInteger roleIndex = new AtomicInteger(1);
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (int i = 0; i < characters.size(); i++) {
                JSONObject charData = characters.getJSONObject(i);

                futures.add(CompletableFuture.runAsync(() -> {
                    processSingleCharacter(seriesId, charData, roleIndex, completedRoles, totalRoles);
                }));
            }

            // 等待所有角色处理完成
            log.info("等待所有角色处理完成: seriesId={}, totalRoles={}", seriesId, totalRoles);
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            log.info("所有角色处理完成: seriesId={}, completedRoles={}", seriesId, completedRoles.get());

            // 更新系列状态为待审核
            updateSeriesStatus(seriesId, SeriesStatus.PENDING_REVIEW.getCode());

            // 发送完成消息
            sendProgress(seriesId, "COMPLETED", totalRoles, totalRoles, 100, "全部完成！");

            log.info("角色处理和图片生成完成: seriesId={}, count={}", seriesId, totalRoles);
        } catch (Exception e) {
            log.error("角色处理失败: seriesId={}", seriesId, e);
            sendProgress(seriesId, "FAILED", 0, 0, 0, "处理失败: " + e.getMessage());
            updateSeriesStatus(seriesId, SeriesStatus.PENDING_REVIEW.getCode());
        }
    }

    /**
     * 处理单个角色
     */
    private void processSingleCharacter(Long seriesId, JSONObject charData,
                                         AtomicInteger roleIndex, AtomicInteger completedRoles, int totalRoles) {
        String roleName = charData.getString("roleName");
        int currentIndex = roleIndex.getAndIncrement();

        try {
            // 发送当前处理角色
            sendProgress(seriesId, "PROCESSING", completedRoles.get(), totalRoles, 0, "正在生成: " + roleName);

            // 获取系列的默认风格
            Series series = seriesMapper.selectById(seriesId);
            String seriesStyle = series != null ? series.getStyleKeywords() : null;

            // 角色风格：优先使用角色指定风格，否则使用系列默认风格
            String charStyle = charData.getString("styleKeywords");
            if (charStyle == null || charStyle.isEmpty()) {
                charStyle = seriesStyle;
            }

            // 创建角色记录
            Role role = new Role();
            role.setSeriesId(seriesId);
            role.setRoleName(roleName);
            role.setRoleCode(String.format("ROLE_%03d", currentIndex));
            role.setStatus(RoleStatus.EXTRACTING.getCode());
            role.setCustomPrompt(charData.getString("customPrompt"));
            role.setOriginalPrompt(charData.getString("originalPrompt"));
            role.setStyleKeywords(charStyle);
            role.setExtractConfidence(new BigDecimal("1.0"));
            role.setCreatedAt(LocalDateTime.now());
            role.setUpdatedAt(LocalDateTime.now());
            roleMapper.insert(role);

            log.info("创建角色: id={}, name={}, style={}", role.getId(), role.getRoleName(), charStyle);

            // 构建请求
            ImageGenerateRequest imageRequest = ImageGenerateRequest.builder()
                    .roleName(role.getRoleName())
                    .styleKeywords(charStyle)
                    .aspectRatio(charData.getString("aspectRatio"))
                    .quality(charData.getString("quality"))
                    .customPrompt(role.getCustomPrompt())
                    .originalPrompt(charData.getString("originalPrompt"))
                    .seriesId(seriesId)
                    .roleId(role.getId())
                    .build();

            // 生成图片
            ImageGenerateResponse imageResponse = imageGenerateService.generateCharacterSheet(imageRequest);

            if ("success".equals(imageResponse.getStatus())) {
                log.info("角色图片生成成功: roleId={}", role.getId());
                saveAsset(role, imageResponse, imageRequest);
                role.setStatus(RoleStatus.PENDING_REVIEW.getCode());
            } else {
                log.error("角色图片生成失败: roleId={}, error={}", role.getId(), imageResponse.getErrorMessage());
                // 即使失败也要保存一个失败状态的资产，方便用户重试
                role.setStatus(RoleStatus.PENDING_REVIEW.getCode());
            }

            role.setUpdatedAt(LocalDateTime.now());
            roleMapper.updateById(role);

            // 更新完成计数（无论成功或失败都算完成一个角色）
            int completed = completedRoles.incrementAndGet();
            int percent = (int) (completed * 100.0 / totalRoles);
            sendProgress(seriesId, "PROCESSING", completed, totalRoles, percent,
                    "完成: " + roleName + " (" + completed + "/" + totalRoles + ")");

        } catch (Exception e) {
            log.error("处理角色失败: {}", roleName, e);
            // 异常时也要更新计数，避免总数不匹配
            int completed = completedRoles.incrementAndGet();
            int percent = (int) (completed * 100.0 / totalRoles);
            sendProgress(seriesId, "PROCESSING", completed, totalRoles, percent,
                    "角色处理异常: " + roleName + " (" + completed + "/" + totalRoles + ")");
        }
    }

    /**
     * 发送进度消息到 WebSocket
     */
    private void sendProgress(Long seriesId, String status, int completed, int total, int percent, String message) {
        SeriesProgressMessage progress = new SeriesProgressMessage();
        progress.setSeriesId(seriesId);
        progress.setStatus(status);
        progress.setCompletedRoles(completed);
        progress.setTotalRoles(total);
        progress.setProgressPercent(percent);
        progress.setMessage(message);

        messagingTemplate.convertAndSend("/topic/series/" + seriesId, progress);
        log.debug("发送进度: seriesId={}, status={}, completed={}, total={}, percent={}%",
                seriesId, status, completed, total, percent);
    }

    /**
     * 保存角色资产
     */
    private void saveAsset(Role role, ImageGenerateResponse response, ImageGenerateRequest request) {
        try {
            // 创建资产记录
            RoleAsset asset = new RoleAsset();
            asset.setRoleId(role.getId());
            asset.setAssetType("CHARACTER_SHEET"); // 三视图合并在一张图
            asset.setViewType("ALL"); // 包含所有视图
            asset.setClothingId(1);
            asset.setVersion(1);
            asset.setFileName(role.getRoleName() + "_charactersheet_v1.png");
            asset.setStatus(AssetStatus.PENDING_REVIEW.getCode());
            asset.setIsActive(1);
            asset.setValidationPassed(1);
            asset.setCreatedAt(LocalDateTime.now());
            asset.setUpdatedAt(LocalDateTime.now());

            // 保存图片URL
            if (response.getImageUrl() != null) {
                asset.setFilePath(response.getImageUrl());
                asset.setThumbnailPath(response.getImageUrl());
                asset.setTransparentPath(response.getImageUrl());
            }

            roleAssetMapper.insert(asset);

            // 保存元数据
            AssetMetadata metadata = new AssetMetadata();
            metadata.setAssetId(asset.getId());
            metadata.setSeed(response.getSeed());
            metadata.setModelVersion("volcengine-ark");
            metadata.setImageWidth(response.getWidth());
            metadata.setImageHeight(response.getHeight());
            metadata.setAspectRatio(request.getAspectRatio());
            metadata.setGenerationTimeMs(0L);
            // 保存提示词
            metadata.setUserPrompt(request.getOriginalPrompt());
            metadata.setPrompt(request.getCustomPrompt());
            metadata.setCreatedAt(LocalDateTime.now());
            assetMetadataMapper.insert(metadata);

            log.info("资产保存成功: assetId={}, roleId={}", asset.getId(), role.getId());
        } catch (Exception e) {
            log.error("保存资产失败: roleId={}", role.getId(), e);
        }
    }

    /**
     * 生成 Mock 图片资产（模拟 Seedream API）
     */
    private void generateMockAssets(Role role) {
        ViewType[] viewTypes = {ViewType.FRONT, ViewType.SIDE, ViewType.BACK, ViewType.THREE_QUARTER};

        // 使用 picsum.photos 在线占位图服务
        String baseUrl = "https://picsum.photos/seed/" + role.getId() + "/";

        for (ViewType viewType : viewTypes) {
            // 创建资产记录
            RoleAsset asset = new RoleAsset();
            asset.setRoleId(role.getId());
            asset.setAssetType("MULTI_VIEW");
            asset.setViewType(viewType.getCode());
            asset.setClothingId(1);
            asset.setVersion(1);
            asset.setFileName(generateFileName(role.getRoleName(), viewType, 1, false));
            asset.setStatus(AssetStatus.PENDING_REVIEW.getCode());
            asset.setIsActive(1);
            asset.setValidationPassed(1);
            asset.setCreatedAt(LocalDateTime.now());
            asset.setUpdatedAt(LocalDateTime.now());

            // 使用在线占位图 - 每个视图不同
            int seed = (int) (role.getId() * 10 + viewType.ordinal());
            String mockImagePath = baseUrl + viewType.ordinal() + "/768/1024";
            asset.setFilePath(mockImagePath);
            asset.setThumbnailPath(baseUrl + viewType.ordinal() + "/200/267");
            asset.setTransparentPath(mockImagePath);

            roleAssetMapper.insert(asset);

            // 创建元数据
            AssetMetadata metadata = new AssetMetadata();
            metadata.setAssetId(asset.getId());
            metadata.setPrompt("Mock prompt for " + role.getRoleName() + " " + viewType.getDesc());
            metadata.setNegativePrompt("mock negative prompt");
            metadata.setSeed((long) seed);
            metadata.setModelVersion("mock-v1.0");
            metadata.setImageWidth(768);
            metadata.setImageHeight(1024);
            metadata.setAspectRatio("3:4");
            metadata.setGenerationTimeMs(1000L);
            metadata.setCreatedAt(LocalDateTime.now());
            assetMetadataMapper.insert(metadata);

            log.info("创建Mock资产: roleId={}, viewType={}, assetId={}", role.getId(), viewType, asset.getId());
        }
    }

    private String generateFileName(String roleName, ViewType viewType, int version, boolean transparent) {
        String sanitized = roleName.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
        String suffix = transparent ? "_transparent" : "";
        return String.format("%s_%s_C01_V%02d%s.png", sanitized, viewType.getShortName(), version, suffix);
    }

    private SeriesDetailVO convertToVO(Series series) {
        SeriesDetailVO vo = new SeriesDetailVO();
        BeanUtils.copyProperties(series, vo);
        return vo;
    }

    @Override
    public List<SeriesDetailVO> getLockedSeries() {
        Long userId = UserContextHolder.getUserId();
        LambdaQueryWrapper<Series> wrapper = new LambdaQueryWrapper<>();
        if (userId != null) {
            wrapper.eq(Series::getUserId, userId);
        }
        // 状态为 LOCKED(2) 或 LOCKED(3，角色锁定后系列状态)
        wrapper.in(Series::getStatus, SeriesStatus.LOCKED.getCode(), 3)
                .orderByDesc(Series::getCreatedAt);
        List<Series> seriesList = seriesMapper.selectList(wrapper);

        // 批量查询角色数量
        java.util.Map<Long, Integer> roleCountMap = new java.util.HashMap<>();
        if (!seriesList.isEmpty()) {
            List<Long> seriesIds = seriesList.stream().map(Series::getId).collect(java.util.stream.Collectors.toList());
            LambdaQueryWrapper<Role> roleWrapper = new LambdaQueryWrapper<>();
            roleWrapper.in(Role::getSeriesId, seriesIds);
            List<Role> allRoles = roleMapper.selectList(roleWrapper);

            for (Role role : allRoles) {
                roleCountMap.merge(role.getSeriesId(), 1, Integer::sum);
            }
        }

        return seriesList.stream().map(series -> {
            SeriesDetailVO vo = convertToVO(series);
            vo.setRoleCount(roleCountMap.getOrDefault(series.getId(), 0));
            return vo;
        }).collect(java.util.stream.Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSeries(Long seriesId) {
        Series series = seriesMapper.selectById(seriesId);
        if (series == null) {
            throw new BusinessException("系列不存在");
        }
        // 使用自定义SQL绕过@TableLogic拦截
        seriesMapper.softDeleteById(seriesId, LocalDateTime.now());
        log.info("系列已移入回收站: seriesId={}", seriesId);
    }

    @Override
    public List<SeriesDetailVO> getTrashList() {
        Long userId = UserContextHolder.getUserId();
        List<Series> seriesList;
        if (userId != null) {
            seriesList = seriesMapper.selectTrashListByUserId(userId);
        } else {
            seriesList = seriesMapper.selectTrashList();
        }

        // 批量查询角色数量
        java.util.Map<Long, Integer> roleCountMap = new java.util.HashMap<>();
        if (!seriesList.isEmpty()) {
            List<Long> seriesIds = seriesList.stream().map(Series::getId).collect(java.util.stream.Collectors.toList());
            LambdaQueryWrapper<Role> roleWrapper = new LambdaQueryWrapper<>();
            roleWrapper.in(Role::getSeriesId, seriesIds);
            List<Role> allRoles = roleMapper.selectList(roleWrapper);

            for (Role role : allRoles) {
                roleCountMap.merge(role.getSeriesId(), 1, Integer::sum);
            }
        }

        return seriesList.stream().map(series -> {
            SeriesDetailVO vo = convertToVO(series);
            vo.setRoleCount(roleCountMap.getOrDefault(series.getId(), 0));
            return vo;
        }).collect(java.util.stream.Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restoreSeries(Long seriesId) {
        Series series = seriesMapper.selectByIdIncludeDeleted(seriesId);
        if (series == null) {
            throw new BusinessException("系列不存在");
        }
        // 使用自定义SQL绕过@TableLogic拦截
        seriesMapper.restoreById(seriesId);
        log.info("系列已恢复: seriesId={}", seriesId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void permanentDeleteSeries(Long seriesId) {
        seriesMapper.realDeleteById(seriesId);
        log.info("系列已彻底删除: seriesId={}", seriesId);
    }

    @Override
    public SeriesVideoAssetsVO getSeriesVideoAssets(Long seriesId) {
        // 1. 获取系列信息
        Series series = seriesMapper.selectById(seriesId);
        if (series == null) {
            throw new BusinessException("系列不存在");
        }

        // 2. 获取该系列下所有剧集（@TableLogic会自动过滤已删除的）
        LambdaQueryWrapper<Episode> episodeWrapper = new LambdaQueryWrapper<>();
        episodeWrapper.eq(Episode::getSeriesId, seriesId)
                .orderByAsc(Episode::getEpisodeNumber);
        List<Episode> episodes = episodeMapper.selectList(episodeWrapper);

        // 3. 遍历剧集，获取分镜视频
        List<EpisodeVideoAssetsVO> episodeVOs = new ArrayList<>();
        for (Episode episode : episodes) {
            // 获取剧集下所有分镜（@TableLogic会自动过滤已删除的）
            LambdaQueryWrapper<Shot> shotWrapper = new LambdaQueryWrapper<>();
            shotWrapper.eq(Shot::getEpisodeId, episode.getId())
                    .orderByAsc(Shot::getShotNumber);
            List<Shot> shots = shotMapper.selectList(shotWrapper);

            List<ShotVideoInfoVO> shotVOs = new ArrayList<>();
            int completedCount = 0;

            for (Shot shot : shots) {
                // 优先从 ShotVideoAsset 获取激活视频
                ShotVideoAsset activeAsset = shotVideoAssetMapper.selectActiveByShotId(shot.getId());

                String videoUrl = null;
                String thumbnailUrl = null;

                if (activeAsset != null) {
                    videoUrl = activeAsset.getVideoUrl();
                    thumbnailUrl = activeAsset.getThumbnailUrl();
                } else {
                    // 兼容旧逻辑：直接从 Shot 获取
                    videoUrl = shot.getVideoUrl();
                    thumbnailUrl = shot.getThumbnailUrl();
                }

                // 只返回有视频的分镜
                if (videoUrl != null && !videoUrl.isEmpty()) {
                    ShotVideoInfoVO shotVO = new ShotVideoInfoVO();
                    shotVO.setShotId(shot.getId());
                    shotVO.setShotNumber(shot.getShotNumber());
                    shotVO.setShotName(shot.getShotName());
                    shotVO.setDescription(shot.getDescription());
                    shotVO.setDuration(shot.getDuration());
                    shotVO.setVideoUrl(videoUrl);
                    shotVO.setThumbnailUrl(thumbnailUrl);
                    shotVO.setGenerationStatus(shot.getGenerationStatus());
                    shotVO.setCreatedAt(shot.getCreatedAt());
                    shotVOs.add(shotVO);

                    if (shot.getGenerationStatus() != null && shot.getGenerationStatus() == 2) {
                        completedCount++;
                    }
                }
            }

            EpisodeVideoAssetsVO episodeVO = new EpisodeVideoAssetsVO();
            episodeVO.setEpisodeId(episode.getId());
            episodeVO.setEpisodeNumber(episode.getEpisodeNumber());
            episodeVO.setEpisodeName(episode.getEpisodeName());
            episodeVO.setTotalShots(shots.size());
            episodeVO.setCompletedShots(completedCount);
            episodeVO.setShots(shotVOs);
            episodeVOs.add(episodeVO);
        }

        SeriesVideoAssetsVO result = new SeriesVideoAssetsVO();
        result.setSeriesId(seriesId);
        result.setSeriesName(series.getSeriesName());
        result.setEpisodes(episodeVOs);
        return result;
    }
}
