package com.manga.ai.shot.service.impl;

import com.manga.ai.asset.mapper.RoleAssetMapper;
import com.manga.ai.common.enums.ShotGenerationStatus;
import com.manga.ai.common.enums.ShotStatus;
import com.manga.ai.common.service.OssService;
import com.manga.ai.episode.entity.Episode;
import com.manga.ai.episode.mapper.EpisodeMapper;
import com.manga.ai.role.entity.Role;
import com.manga.ai.prop.mapper.PropAssetMapper;
import com.manga.ai.prop.mapper.PropMapper;
import com.manga.ai.scene.entity.Scene;
import com.manga.ai.scene.entity.SceneAsset;
import com.manga.ai.role.mapper.RoleMapper;
import com.manga.ai.asset.entity.RoleAsset;
import com.manga.ai.scene.mapper.SceneAssetMapper;
import com.manga.ai.scene.mapper.SceneMapper;
import com.manga.ai.series.mapper.SeriesMapper;
import com.manga.ai.shot.dto.ShotUpdateRequest;
import com.manga.ai.shot.entity.Shot;
import com.manga.ai.shot.entity.ShotCharacter;
import com.manga.ai.shot.entity.ShotReferenceImage;
import com.manga.ai.shot.entity.ShotVideoAssetMetadata;
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
import com.manga.ai.video.service.SeedanceService;
import com.manga.ai.video.dto.SeedanceRequest;
import com.manga.ai.video.dto.SeedanceResponse;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShotServiceImplTest {

    static {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Shot.class);
    }

    @Test
    void updateShotDoesNotOverwriteGenerationStateFromStaleFrontendPayload() {
        ShotMapper shotMapper = mock(ShotMapper.class);
        Shot shot = new Shot();
        shot.setId(631L);
        shot.setStatus(ShotStatus.PENDING_REVIEW.getCode());
        shot.setDescription("旧剧情");
        shot.setGenerationStatus(ShotGenerationStatus.GENERATING.getCode());
        shot.setGenerationError(null);
        shot.setGenerationStartTime(LocalDateTime.of(2026, 5, 15, 20, 5));
        shot.setDeductedCredits(150);
        when(shotMapper.selectById(631L)).thenReturn(shot);

        ShotUpdateRequest request = new ShotUpdateRequest();
        request.setDescription("新剧情");
        request.setGenerationStatus(ShotGenerationStatus.FAILED.getCode());

        createService(shotMapper).updateShot(631L, request);

        assertThat(shot.getGenerationStatus()).isEqualTo(ShotGenerationStatus.GENERATING.getCode());
        assertThat(shot.getGenerationError()).isNull();
        assertThat(shot.getDeductedCredits()).isEqualTo(150);
        verify(shotMapper, never()).updateById(any(Shot.class));
        ArgumentCaptor<LambdaUpdateWrapper<Shot>> wrapperCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(shotMapper).update(eq(null), wrapperCaptor.capture());
        String sqlSet = String.valueOf(wrapperCaptor.getValue().getSqlSet());
        assertThat(sqlSet).contains("description");
        assertThat(sqlSet)
                .doesNotContain("generation_status")
                .doesNotContain("generation_error")
                .doesNotContain("deducted_credits")
                .doesNotContain("generation_start_time");
    }

    @Test
    void successfulVideoGenerationClearsPreviousGenerationError() {
        ShotMapper shotMapper = mock(ShotMapper.class);
        Shot shot = new Shot();
        shot.setId(631L);
        shot.setEpisodeId(11L);
        shot.setShotNumber(5);
        shot.setStatus(ShotStatus.PENDING_REVIEW.getCode());
        shot.setUserPrompt("测试提示词");
        shot.setDuration(6);
        shot.setResolution("720p");
        shot.setAspectRatio("16:9");
        shot.setVideoModel("seedance-2.0-fast");
        shot.setGenerationStatus(ShotGenerationStatus.GENERATING.getCode());
        shot.setGenerationError("视频生成失败，请稍后重试");
        when(shotMapper.selectById(631L)).thenReturn(shot);

        SeedanceService seedanceService = mock(SeedanceService.class);
        SeedanceResponse response = new SeedanceResponse();
        response.setStatus("completed");
        response.setVideoUrl("https://example.com/video.mp4");
        when(seedanceService.generateVideo(any(SeedanceRequest.class))).thenReturn(response);

        createService(shotMapper, seedanceService).doGenerateVideo(631L);

        verify(shotMapper, never()).updateById(any(Shot.class));
        ArgumentCaptor<LambdaUpdateWrapper<Shot>> wrapperCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(shotMapper).update(eq(null), wrapperCaptor.capture());
        String sqlSet = String.valueOf(wrapperCaptor.getValue().getSqlSet());
        assertThat(sqlSet)
                .contains("generation_status")
                .contains("generation_error")
                .contains("video_url")
                .doesNotContain("description")
                .doesNotContain("shot_number");
    }

    @Test
    void klingOmniShotModelIsPassedToVideoService() {
        ShotMapper shotMapper = mock(ShotMapper.class);
        Shot shot = new Shot();
        shot.setId(632L);
        shot.setEpisodeId(11L);
        shot.setShotNumber(6);
        shot.setStatus(ShotStatus.PENDING_REVIEW.getCode());
        shot.setUserPrompt("Kling 测试提示词");
        shot.setDuration(8);
        shot.setResolution("1080p");
        shot.setAspectRatio("9:16");
        shot.setVideoModel("kling-v3-omni");
        shot.setGenerationStatus(ShotGenerationStatus.GENERATING.getCode());
        when(shotMapper.selectById(632L)).thenReturn(shot);

        SeedanceService seedanceService = mock(SeedanceService.class);
        SeedanceResponse response = new SeedanceResponse();
        response.setStatus("completed");
        response.setVideoUrl("https://example.com/kling.mp4");
        when(seedanceService.generateVideo(any(SeedanceRequest.class))).thenReturn(response);

        createService(shotMapper, seedanceService).doGenerateVideo(632L);

        ArgumentCaptor<SeedanceRequest> requestCaptor = ArgumentCaptor.forClass(SeedanceRequest.class);
        verify(seedanceService).generateVideo(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getModel()).isEqualTo("kling-v3-omni");
        assertThat(requestCaptor.getValue().getWidth()).isEqualTo(1080);
        assertThat(requestCaptor.getValue().getHeight()).isEqualTo(1920);
        assertThat(requestCaptor.getValue().getRatio()).isEqualTo("9:16");
        assertThat(requestCaptor.getValue().getGenerateAudio()).isTrue();
    }

    @Test
    void completedVideoGenerationWithoutVideoUrlIsTreatedAsFailure() {
        ShotMapper shotMapper = mock(ShotMapper.class);
        Shot shot = new Shot();
        shot.setId(631L);
        shot.setEpisodeId(11L);
        shot.setShotNumber(5);
        shot.setStatus(ShotStatus.PENDING_REVIEW.getCode());
        shot.setUserPrompt("测试提示词");
        shot.setDuration(6);
        shot.setResolution("720p");
        shot.setAspectRatio("16:9");
        shot.setVideoModel("seedance-2.0-fast");
        shot.setGenerationStatus(ShotGenerationStatus.GENERATING.getCode());
        when(shotMapper.selectById(631L)).thenReturn(shot);

        SeedanceService seedanceService = mock(SeedanceService.class);
        SeedanceResponse response = new SeedanceResponse();
        response.setStatus("completed");
        when(seedanceService.generateVideo(any(SeedanceRequest.class))).thenReturn(response);

        createService(shotMapper, seedanceService).doGenerateVideo(631L);

        ArgumentCaptor<Shot> shotCaptor = ArgumentCaptor.forClass(Shot.class);
        verify(shotMapper).updateById(shotCaptor.capture());
        assertThat(shotCaptor.getValue().getGenerationStatus()).isEqualTo(ShotGenerationStatus.FAILED.getCode());
        assertThat(shotCaptor.getValue().getGenerationError()).isEqualTo("视频生成完成但未返回视频地址，请重新生成");
    }

    @Test
    void failedVideoGenerationKeepsFriendlyProviderMessage() {
        ShotMapper shotMapper = mock(ShotMapper.class);
        Shot shot = new Shot();
        shot.setId(633L);
        shot.setEpisodeId(11L);
        shot.setShotNumber(7);
        shot.setStatus(ShotStatus.PENDING_REVIEW.getCode());
        shot.setUserPrompt("Kling 失败提示词");
        shot.setDuration(6);
        shot.setResolution("480p");
        shot.setAspectRatio("16:9");
        shot.setVideoModel("kling-v3-omni");
        shot.setGenerationStatus(ShotGenerationStatus.GENERATING.getCode());
        when(shotMapper.selectById(633L)).thenReturn(shot);

        SeedanceService seedanceService = mock(SeedanceService.class);
        SeedanceResponse response = new SeedanceResponse();
        response.setStatus("failed");
        response.setErrorMessage("未指定模型名称，模型名称不能为空");
        when(seedanceService.generateVideo(any(SeedanceRequest.class))).thenReturn(response);

        createService(shotMapper, seedanceService).doGenerateVideo(633L);

        ArgumentCaptor<Shot> shotCaptor = ArgumentCaptor.forClass(Shot.class);
        verify(shotMapper).updateById(shotCaptor.capture());
        assertThat(shotCaptor.getValue().getGenerationStatus()).isEqualTo(ShotGenerationStatus.FAILED.getCode());
        assertThat(shotCaptor.getValue().getGenerationError()).isEqualTo("未指定模型名称，模型名称不能为空");
    }

    @Test
    void generateWithReferencesUsesClientStartTimeAndSavesShotUpdateBeforeAsyncWork() {
        ShotMapper shotMapper = mock(ShotMapper.class);
        ShotReferenceImageMapper shotReferenceImageMapper = mock(ShotReferenceImageMapper.class);
        UserService userService = mock(UserService.class);
        ShotService self = mock(ShotService.class);

        Shot shot = new Shot();
        shot.setId(635L);
        shot.setEpisodeId(11L);
        shot.setShotNumber(9);
        shot.setStatus(ShotStatus.PENDING_REVIEW.getCode());
        shot.setDuration(5);
        shot.setResolution("480p");
        shot.setAspectRatio("16:9");
        shot.setVideoModel("seedance-2.0-fast");
        shot.setGenerationStatus(ShotGenerationStatus.PENDING.getCode());
        when(shotMapper.selectById(635L)).thenReturn(shot);
        when(shotMapper.update(eq(null), any(LambdaUpdateWrapper.class))).thenReturn(1);

        ShotUpdateRequest update = new ShotUpdateRequest();
        update.setDescription("用户点击生成前刚修改的剧情");
        update.setDescriptionEdited(true);
        update.setDuration(8);
        update.setResolution("720p");
        update.setAspectRatio("9:16");
        update.setVideoModel("kling-v3-omni");

        LocalDateTime clickedAt = LocalDateTime.of(2026, 5, 16, 5, 30, 12);

        UserContextHolder.setUserId(7L);
        try {
            createService(
                    shotMapper,
                    mock(ShotCharacterMapper.class),
                    mock(SceneMapper.class),
                    mock(SceneAssetMapper.class),
                    mock(RoleMapper.class),
                    mock(RoleAssetMapper.class),
                    mock(EpisodeMapper.class),
                    mock(SeedanceService.class),
                    shotReferenceImageMapper,
                    mock(ShotVideoAssetMapper.class),
                    mock(ShotVideoAssetMetadataMapper.class),
                    userService,
                    self
            ).generateVideoWithReferences(635L, List.of(), List.of(), update, clickedAt);
        } finally {
            UserContextHolder.clear();
        }

        ArgumentCaptor<LambdaUpdateWrapper<Shot>> wrapperCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(shotMapper).update(eq(null), wrapperCaptor.capture());
        String sqlSet = String.valueOf(wrapperCaptor.getValue().getSqlSet());
        assertThat(sqlSet)
                .contains("generation_status")
                .contains("generation_start_time")
                .contains("description")
                .contains("duration")
                .contains("resolution")
                .contains("aspect_ratio")
                .contains("video_model");
        assertThat(shot.getDuration()).isEqualTo(8);
        assertThat(shot.getResolution()).isEqualTo("720p");
        assertThat(shot.getAspectRatio()).isEqualTo("9:16");
        assertThat(shot.getVideoModel()).isEqualTo("kling-v3-omni");
        verify(userService).deductCredits(eq(7L), eq(256), any(), any(), eq(635L), eq("SHOT"));
        verify(self).doGenerateVideoWithReferences(635L, List.of());
    }

    @Test
    void klingOmniReferencePromptBindsSceneAndRoleImagesWithImageTokens() {
        ShotMapper shotMapper = mock(ShotMapper.class);
        ShotCharacterMapper shotCharacterMapper = mock(ShotCharacterMapper.class);
        SceneMapper sceneMapper = mock(SceneMapper.class);
        SceneAssetMapper sceneAssetMapper = mock(SceneAssetMapper.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        RoleAssetMapper roleAssetMapper = mock(RoleAssetMapper.class);
        EpisodeMapper episodeMapper = mock(EpisodeMapper.class);
        ShotReferenceImageMapper shotReferenceImageMapper = mock(ShotReferenceImageMapper.class);
        SeedanceService seedanceService = mock(SeedanceService.class);

        Shot shot = new Shot();
        shot.setId(636L);
        shot.setEpisodeId(11L);
        shot.setShotNumber(10);
        shot.setSceneId(22L);
        shot.setSceneName("沈府回廊");
        shot.setDescription("时间【00:00-00:10】\n镜头【全景+拉镜头】\n剧情【沈清欢扶着柱子，眼神疯狂而快意。】\n音效【心跳声加剧】");
        shot.setDuration(6);
        shot.setResolution("480p");
        shot.setAspectRatio("16:9");
        shot.setVideoModel("kling-v3-omni");
        shot.setGenerationStatus(ShotGenerationStatus.GENERATING.getCode());
        when(shotMapper.selectById(636L)).thenReturn(shot);

        Episode episode = new Episode();
        episode.setId(11L);
        episode.setSeriesId(80L);
        when(episodeMapper.selectById(11L)).thenReturn(episode);

        Scene scene = new Scene();
        scene.setId(22L);
        scene.setSceneName("沈府回廊");
        when(sceneMapper.selectList(any())).thenReturn(List.of(scene));
        when(sceneMapper.selectById(22L)).thenReturn(scene);

        SceneAsset sceneAsset = new SceneAsset();
        sceneAsset.setFilePath("https://example.com/scene.png");
        when(sceneAssetMapper.selectOne(any())).thenReturn(sceneAsset);

        ShotCharacter shotCharacter = new ShotCharacter();
        shotCharacter.setShotId(636L);
        shotCharacter.setRoleId(148L);
        shotCharacter.setClothingId(5);
        when(shotCharacterMapper.selectList(any())).thenReturn(List.of(shotCharacter));

        Role role = new Role();
        role.setId(148L);
        role.setRoleName("沈清欢");
        when(roleMapper.selectById(148L)).thenReturn(role);

        RoleAsset roleAsset = new RoleAsset();
        roleAsset.setFilePath("https://example.com/role.png");
        roleAsset.setClothingName("红衣");
        when(roleAssetMapper.selectOne(any())).thenReturn(roleAsset);

        ShotReferenceImage savedReference = new ShotReferenceImage();
        savedReference.setImageType("role");
        savedReference.setReferenceId(148L);
        savedReference.setReferenceName("沈清欢-红衣");
        savedReference.setImageUrl("https://example.com/role.png");
        when(shotReferenceImageMapper.selectList(any())).thenReturn(List.of(savedReference));

        SeedanceResponse response = new SeedanceResponse();
        response.setStatus("completed");
        response.setVideoUrl("https://example.com/output.mp4");
        when(seedanceService.generateVideo(any(SeedanceRequest.class))).thenReturn(response);

        createService(
                shotMapper,
                shotCharacterMapper,
                sceneMapper,
                sceneAssetMapper,
                roleMapper,
                roleAssetMapper,
                episodeMapper,
                seedanceService,
                shotReferenceImageMapper
        ).doGenerateVideoWithReferences(636L, List.of("https://example.com/role.png"));

        ArgumentCaptor<SeedanceRequest> requestCaptor = ArgumentCaptor.forClass(SeedanceRequest.class);
        verify(seedanceService).generateVideo(requestCaptor.capture());
        SeedanceRequest request = requestCaptor.getValue();
        assertThat(request.getModel()).isEqualTo("kling-v3-omni");
        assertThat(request.getContents()).hasSize(2);
        assertThat(request.getRatio()).isEqualTo("16:9");
        assertThat(request.getGenerateAudio()).isTrue();
        assertThat(request.getPrompt())
                .startsWith("Omni 输入绑定：\n- 场景【沈府回廊】必须使用 <<<image_1>>>")
                .contains("<<<image_1>>>")
                .contains("场景参考图：沈府回廊")
                .contains("<<<image_2>>>")
                .contains("人物参考图：沈清欢-红衣")
                .contains("人物【沈清欢-红衣】必须使用 <<<image_2>>>")
                .contains("画面中的沈清欢-红衣必须以 <<<image_2>>> 为人物外观参考")
                .doesNotContain("frontend：页面参考图");
    }

    @Test
    void seedanceReferencePromptKeepsLegacyImageLabels() {
        ShotMapper shotMapper = mock(ShotMapper.class);
        ShotCharacterMapper shotCharacterMapper = mock(ShotCharacterMapper.class);
        SceneMapper sceneMapper = mock(SceneMapper.class);
        SceneAssetMapper sceneAssetMapper = mock(SceneAssetMapper.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        RoleAssetMapper roleAssetMapper = mock(RoleAssetMapper.class);
        EpisodeMapper episodeMapper = mock(EpisodeMapper.class);
        SeedanceService seedanceService = mock(SeedanceService.class);

        Shot shot = new Shot();
        shot.setId(637L);
        shot.setEpisodeId(11L);
        shot.setShotNumber(11);
        shot.setSceneId(22L);
        shot.setSceneName("沈府回廊");
        shot.setDescription("时间【00:00-00:10】\n镜头【全景+拉镜头】\n剧情【沈清欢扶着柱子。】\n音效【心跳声】");
        shot.setDuration(6);
        shot.setResolution("480p");
        shot.setAspectRatio("16:9");
        shot.setVideoModel("seedance-2.0-fast");
        shot.setGenerationStatus(ShotGenerationStatus.GENERATING.getCode());
        when(shotMapper.selectById(637L)).thenReturn(shot);

        Episode episode = new Episode();
        episode.setId(11L);
        episode.setSeriesId(80L);
        when(episodeMapper.selectById(11L)).thenReturn(episode);

        Scene scene = new Scene();
        scene.setId(22L);
        scene.setSceneName("沈府回廊");
        when(sceneMapper.selectList(any())).thenReturn(List.of(scene));
        when(sceneMapper.selectById(22L)).thenReturn(scene);

        SceneAsset sceneAsset = new SceneAsset();
        sceneAsset.setFilePath("https://example.com/scene.png");
        when(sceneAssetMapper.selectOne(any())).thenReturn(sceneAsset);

        ShotCharacter shotCharacter = new ShotCharacter();
        shotCharacter.setShotId(637L);
        shotCharacter.setRoleId(148L);
        shotCharacter.setClothingId(5);
        when(shotCharacterMapper.selectList(any())).thenReturn(List.of(shotCharacter));

        Role role = new Role();
        role.setId(148L);
        role.setRoleName("沈清欢");
        when(roleMapper.selectById(148L)).thenReturn(role);

        RoleAsset roleAsset = new RoleAsset();
        roleAsset.setFilePath("https://example.com/role.png");
        roleAsset.setClothingName("红衣");
        when(roleAssetMapper.selectOne(any())).thenReturn(roleAsset);

        SeedanceResponse response = new SeedanceResponse();
        response.setStatus("completed");
        response.setVideoUrl("https://example.com/output.mp4");
        when(seedanceService.generateVideo(any(SeedanceRequest.class))).thenReturn(response);

        createService(
                shotMapper,
                shotCharacterMapper,
                sceneMapper,
                sceneAssetMapper,
                roleMapper,
                roleAssetMapper,
                episodeMapper,
                seedanceService,
                mock(ShotReferenceImageMapper.class)
        ).doGenerateVideoWithReferences(637L, List.of("https://example.com/role.png"));

        ArgumentCaptor<SeedanceRequest> requestCaptor = ArgumentCaptor.forClass(SeedanceRequest.class);
        verify(seedanceService).generateVideo(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getModel()).isEqualTo("doubao-seedance-2-0-fast-260128");
        assertThat(requestCaptor.getValue().getPrompt())
                .contains("[图1] scene：沈府回廊")
                .contains("[图2] role：沈清欢-红衣")
                .doesNotContain("<<<image_1>>>");
    }

    @Test
    void videoAssetMetadataUsesActualModelAndStoresProviderRequestParams() {
        ShotMapper shotMapper = mock(ShotMapper.class);
        ShotCharacterMapper shotCharacterMapper = mock(ShotCharacterMapper.class);
        SceneMapper sceneMapper = mock(SceneMapper.class);
        SceneAssetMapper sceneAssetMapper = mock(SceneAssetMapper.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        RoleAssetMapper roleAssetMapper = mock(RoleAssetMapper.class);
        EpisodeMapper episodeMapper = mock(EpisodeMapper.class);
        SeedanceService seedanceService = mock(SeedanceService.class);
        ShotVideoAssetMapper shotVideoAssetMapper = mock(ShotVideoAssetMapper.class);
        ShotVideoAssetMetadataMapper metadataMapper = mock(ShotVideoAssetMetadataMapper.class);

        Shot shot = new Shot();
        shot.setId(638L);
        shot.setEpisodeId(11L);
        shot.setShotNumber(12);
        shot.setSceneId(22L);
        shot.setSceneName("沈府回廊");
        shot.setDescription("时间【00:00-00:05】\n镜头【近景】\n剧情【沈清欢抬头。】\n音效【风声】");
        shot.setDuration(5);
        shot.setResolution("480p");
        shot.setAspectRatio("16:9");
        shot.setVideoModel("kling-v3-omni");
        shot.setGenerationStatus(ShotGenerationStatus.GENERATING.getCode());
        when(shotMapper.selectById(638L)).thenReturn(shot);

        Episode episode = new Episode();
        episode.setId(11L);
        episode.setSeriesId(80L);
        when(episodeMapper.selectById(11L)).thenReturn(episode);

        Scene scene = new Scene();
        scene.setId(22L);
        scene.setSceneName("沈府回廊");
        when(sceneMapper.selectList(any())).thenReturn(List.of(scene));
        when(sceneMapper.selectById(22L)).thenReturn(scene);

        SceneAsset sceneAsset = new SceneAsset();
        sceneAsset.setFilePath("https://example.com/scene.png");
        when(sceneAssetMapper.selectOne(any())).thenReturn(sceneAsset);

        ShotCharacter shotCharacter = new ShotCharacter();
        shotCharacter.setShotId(638L);
        shotCharacter.setRoleId(148L);
        shotCharacter.setClothingId(5);
        when(shotCharacterMapper.selectList(any())).thenReturn(List.of(shotCharacter));

        Role role = new Role();
        role.setId(148L);
        role.setRoleName("沈清欢");
        when(roleMapper.selectById(148L)).thenReturn(role);

        RoleAsset roleAsset = new RoleAsset();
        roleAsset.setFilePath("https://example.com/role.png");
        roleAsset.setClothingName("红衣");
        when(roleAssetMapper.selectOne(any())).thenReturn(roleAsset);

        when(shotVideoAssetMapper.selectMaxVersion(638L)).thenReturn(3);

        SeedanceResponse response = new SeedanceResponse();
        response.setStatus("completed");
        response.setTaskId("tsk_query");
        response.setVideoUrl("https://example.com/output.mp4");
        response.setSubmitModel("kling-v3-omni");
        response.setSubmitRequestUrl("https://toapis.com/v1/videos/generations");
        response.setSubmitRequestBody("{\"model_name\":\"kling-v3-omni\",\"sound\":\"on\",\"image_list\":[{\"image_url\":\"https://example.com/scene.png\"}]}");
        when(seedanceService.generateVideo(any(SeedanceRequest.class))).thenReturn(response);

        createService(
                shotMapper,
                shotCharacterMapper,
                sceneMapper,
                sceneAssetMapper,
                roleMapper,
                roleAssetMapper,
                episodeMapper,
                seedanceService,
                mock(ShotReferenceImageMapper.class),
                shotVideoAssetMapper,
                metadataMapper
        ).doGenerateVideoWithReferences(638L, List.of("https://example.com/role.png"));

        ArgumentCaptor<ShotVideoAssetMetadata> metadataCaptor = ArgumentCaptor.forClass(ShotVideoAssetMetadata.class);
        verify(metadataMapper).insert(metadataCaptor.capture());
        ShotVideoAssetMetadata metadata = metadataCaptor.getValue();
        assertThat(metadata.getModel()).isEqualTo("kling-v3-omni");
        assertThat(metadata.getReferenceUrls()).contains("https://example.com/scene.png", "https://example.com/role.png");
        assertThat(metadata.getGenerationParams())
                .contains("\"requestUrl\":\"https://toapis.com/v1/videos/generations\"")
                .contains("\"requestBody\"")
                .contains("\"model_name\":\"kling-v3-omni\"")
                .contains("\"sound\":\"on\"")
                .contains("\"providerTaskId\":\"tsk_query\"");
    }

    private ShotServiceImpl createService(ShotMapper shotMapper) {
        return createService(shotMapper, mock(SeedanceService.class));
    }

    private ShotServiceImpl createService(ShotMapper shotMapper, SeedanceService seedanceService) {
        return createService(
                shotMapper,
                mock(ShotCharacterMapper.class),
                mock(SceneMapper.class),
                mock(SceneAssetMapper.class),
                mock(RoleMapper.class),
                mock(RoleAssetMapper.class),
                mock(EpisodeMapper.class),
                seedanceService,
                mock(ShotReferenceImageMapper.class)
        );
    }

    private ShotServiceImpl createService(ShotMapper shotMapper,
                                          ShotCharacterMapper shotCharacterMapper,
                                          SceneMapper sceneMapper,
                                          SceneAssetMapper sceneAssetMapper,
                                          RoleMapper roleMapper,
                                          RoleAssetMapper roleAssetMapper,
                                          EpisodeMapper episodeMapper,
                                          SeedanceService seedanceService) {
        return createService(
                shotMapper,
                shotCharacterMapper,
                sceneMapper,
                sceneAssetMapper,
                roleMapper,
                roleAssetMapper,
                episodeMapper,
                seedanceService,
                mock(ShotReferenceImageMapper.class)
        );
    }

    private ShotServiceImpl createService(ShotMapper shotMapper,
                                          ShotCharacterMapper shotCharacterMapper,
                                          SceneMapper sceneMapper,
                                          SceneAssetMapper sceneAssetMapper,
                                          RoleMapper roleMapper,
                                          RoleAssetMapper roleAssetMapper,
                                          EpisodeMapper episodeMapper,
                                          SeedanceService seedanceService,
                                          ShotReferenceImageMapper shotReferenceImageMapper) {
        return new ShotServiceImpl(
                shotMapper,
                shotCharacterMapper,
                mock(ShotPropMapper.class),
                sceneMapper,
                sceneAssetMapper,
                roleMapper,
                roleAssetMapper,
                mock(PropAssetMapper.class),
                mock(PropMapper.class),
                episodeMapper,
                mock(SeriesMapper.class),
                mock(VideoMetadataMapper.class),
                seedanceService,
                shotReferenceImageMapper,
                mock(ShotVideoAssetMapper.class),
                mock(ShotVideoAssetMetadataMapper.class),
                mock(OssService.class),
                mock(UserService.class),
                mock(ShotService.class)
        );
    }

    private ShotServiceImpl createService(ShotMapper shotMapper,
                                          ShotCharacterMapper shotCharacterMapper,
                                          SceneMapper sceneMapper,
                                          SceneAssetMapper sceneAssetMapper,
                                          RoleMapper roleMapper,
                                          RoleAssetMapper roleAssetMapper,
                                          EpisodeMapper episodeMapper,
                                          SeedanceService seedanceService,
                                          ShotReferenceImageMapper shotReferenceImageMapper,
                                          ShotVideoAssetMapper shotVideoAssetMapper,
                                          ShotVideoAssetMetadataMapper shotVideoAssetMetadataMapper,
                                          UserService userService,
                                          ShotService self) {
        return new ShotServiceImpl(
                shotMapper,
                shotCharacterMapper,
                mock(ShotPropMapper.class),
                sceneMapper,
                sceneAssetMapper,
                roleMapper,
                roleAssetMapper,
                mock(PropAssetMapper.class),
                mock(PropMapper.class),
                episodeMapper,
                mock(SeriesMapper.class),
                mock(VideoMetadataMapper.class),
                seedanceService,
                shotReferenceImageMapper,
                shotVideoAssetMapper,
                shotVideoAssetMetadataMapper,
                mock(OssService.class),
                userService,
                self
        );
    }

    private ShotServiceImpl createService(ShotMapper shotMapper,
                                          ShotCharacterMapper shotCharacterMapper,
                                          SceneMapper sceneMapper,
                                          SceneAssetMapper sceneAssetMapper,
                                          RoleMapper roleMapper,
                                          RoleAssetMapper roleAssetMapper,
                                          EpisodeMapper episodeMapper,
                                          SeedanceService seedanceService,
                                          ShotReferenceImageMapper shotReferenceImageMapper,
                                          ShotVideoAssetMapper shotVideoAssetMapper,
                                          ShotVideoAssetMetadataMapper shotVideoAssetMetadataMapper) {
        return new ShotServiceImpl(
                shotMapper,
                shotCharacterMapper,
                mock(ShotPropMapper.class),
                sceneMapper,
                sceneAssetMapper,
                roleMapper,
                roleAssetMapper,
                mock(PropAssetMapper.class),
                mock(PropMapper.class),
                episodeMapper,
                mock(SeriesMapper.class),
                mock(VideoMetadataMapper.class),
                seedanceService,
                shotReferenceImageMapper,
                shotVideoAssetMapper,
                shotVideoAssetMetadataMapper,
                mock(OssService.class),
                mock(UserService.class),
                mock(ShotService.class)
        );
    }
}
