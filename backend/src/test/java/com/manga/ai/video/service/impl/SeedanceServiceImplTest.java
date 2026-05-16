package com.manga.ai.video.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.manga.ai.common.service.OssService;
import com.manga.ai.role.mapper.RoleMapper;
import com.manga.ai.scene.mapper.SceneMapper;
import com.manga.ai.shot.mapper.ShotCharacterMapper;
import com.manga.ai.shot.mapper.ShotMapper;
import com.manga.ai.video.dto.SeedanceRequest;
import com.manga.ai.video.dto.SeedanceResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeedanceServiceImplTest {

    @Test
    void submitFastVipUsesToapisVideoGenerationEndpointAndPayload() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SeedanceServiceImpl service = newService(restTemplate);
        SeedanceRequest request = new SeedanceRequest();
        request.setModel("doubao-seedance-2-0-fast-260128");
        request.setPrompt("古风少女转身");
        request.setDuration(8);
        request.setRatio("9:16");
        request.setGenerateAudio(true);
        request.setWatermark(false);
        request.setSeed(123L);
        SeedanceRequest.ReferenceContent image = new SeedanceRequest.ReferenceContent();
        image.setType("image_url");
        image.setRole("reference_image");
        SeedanceRequest.ImageUrl imageUrl = new SeedanceRequest.ImageUrl();
        imageUrl.setUrl("https://example.com/ref.png");
        image.setImageUrl(imageUrl);
        request.setContents(List.of(image));

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"task-fast\",\"status\":\"queued\"}"));

        SeedanceResponse response = service.submitVideoGeneration(request);

        assertThat(response.getTaskId()).isEqualTo("task-fast");
        verify(restTemplate).exchange(
                eq("https://toapis.com/v1/videos/generations"),
                eq(HttpMethod.POST),
                org.mockito.ArgumentMatchers.<HttpEntity<String>>argThat(entity -> {
                    String body = entity.getBody();
                    String authorization = entity.getHeaders().getFirst("Authorization");
                    return "Bearer test-toapis-key".equals(authorization)
                            && body != null
                            && body.contains("\"model\":\"doubao-seedance-2-0-fast-260128\"")
                            && body.contains("\"prompt\":\"古风少女转身\"")
                            && body.contains("\"aspect_ratio\":\"9:16\"")
                            && body.contains("\"metadata\"")
                            && body.contains("\"generate_audio\":true")
                            && body.contains("\"image_with_roles\"")
                            && body.contains("\"role\":\"reference_image\"");
                }),
                eq(String.class)
        );
    }

    @Test
    void submitVipUsesToapisVideoGenerationEndpointAndPayload() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SeedanceServiceImpl service = newService(restTemplate);
        SeedanceRequest request = new SeedanceRequest();
        request.setModel("doubao-seedance-2-0-260128");
        request.setPrompt("古风少女转身");
        request.setDuration(5);
        request.setRatio("16:9");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"task-vip\",\"status\":\"pending\"}"));

        SeedanceResponse response = service.submitVideoGeneration(request);

        assertThat(response.getTaskId()).isEqualTo("task-vip");
        verify(restTemplate).exchange(
                eq("https://toapis.com/v1/videos/generations"),
                eq(HttpMethod.POST),
                org.mockito.ArgumentMatchers.<HttpEntity<String>>argThat(entity -> {
                    String body = entity.getBody();
                    String authorization = entity.getHeaders().getFirst("Authorization");
                    return "Bearer test-toapis-key".equals(authorization)
                            && body != null
                            && body.contains("\"model\":\"doubao-seedance-2-0-260128\"")
                            && body.contains("\"prompt\":\"古风少女转身\"")
                            && body.contains("\"aspect_ratio\":\"16:9\"")
                            && body.contains("\"metadata\"");
                }),
                eq(String.class)
        );
    }

    @Test
    void submitSeedance2Vip4kUsesOnlyMetadataResolution() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SeedanceServiceImpl service = newService(restTemplate);
        SeedanceRequest request = new SeedanceRequest();
        request.setModel("seedance-2");
        request.setPrompt("古风少女转身 4K");
        request.setDuration(8);
        request.setRatio("16:9");
        request.setResolution("1080p");
        request.setWidth(1920);
        request.setHeight(1080);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"task-seedance2-4k\",\"status\":\"pending\"}"));

        SeedanceResponse response = service.submitVideoGeneration(request);

        assertThat(response.getTaskId()).isEqualTo("task-seedance2-4k");
        verify(restTemplate).exchange(
                eq("https://toapis.com/v1/videos/generations"),
                eq(HttpMethod.POST),
                org.mockito.ArgumentMatchers.<HttpEntity<String>>argThat(entity -> {
                    String body = entity.getBody();
                    String authorization = entity.getHeaders().getFirst("Authorization");
                    if (!"Bearer test-toapis-key".equals(authorization) || body == null) {
                        return false;
                    }
                    JSONObject json = JSON.parseObject(body);
                    JSONObject metadata = json.getJSONObject("metadata");
                    return "seedance-2".equals(json.getString("model"))
                            && !json.containsKey("resolution")
                            && metadata != null
                            && "1080p".equals(metadata.getString("resolution"));
                }),
                eq(String.class)
        );
    }

    @Test
    void submitLegacyVipModelWith1080pNormalizesToSeedance2Model() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SeedanceServiceImpl service = newService(restTemplate);
        SeedanceRequest request = new SeedanceRequest();
        request.setModel("seedance-2.0");
        request.setPrompt("古风少女转身 4K");
        request.setDuration(8);
        request.setRatio("16:9");
        request.setResolution("1080p");
        request.setWidth(1920);
        request.setHeight(1080);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"task-legacy-vip-4k\",\"status\":\"pending\"}"));

        SeedanceResponse response = service.submitVideoGeneration(request);

        assertThat(response.getTaskId()).isEqualTo("task-legacy-vip-4k");
        assertThat(response.getSubmitModel()).isEqualTo("seedance-2");
        verify(restTemplate).exchange(
                eq("https://toapis.com/v1/videos/generations"),
                eq(HttpMethod.POST),
                org.mockito.ArgumentMatchers.<HttpEntity<String>>argThat(entity -> {
                    String body = entity.getBody();
                    if (body == null) {
                        return false;
                    }
                    JSONObject json = JSON.parseObject(body);
                    JSONObject metadata = json.getJSONObject("metadata");
                    return "seedance-2".equals(json.getString("model"))
                            && metadata != null
                            && "1080p".equals(metadata.getString("resolution"));
                }),
                eq(String.class)
        );
    }

    @Test
    void submitSubjectReplacementVipModelUsesToapisReferenceVideoAndImages() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SeedanceServiceImpl service = newService(restTemplate);
        SeedanceRequest request = new SeedanceRequest();
        request.setModel("doubao-seedance-2-0-260128");
        request.setPrompt("请对@视频1执行视频对象替换");
        request.setDuration(8);
        request.setRatio("9:16");
        request.setResolution("720p");
        request.setGenerateAudio(true);
        request.setWatermark(false);

        SeedanceRequest.ReferenceContent image = new SeedanceRequest.ReferenceContent();
        image.setType("image_url");
        image.setRole("reference_image");
        SeedanceRequest.ImageUrl imageUrl = new SeedanceRequest.ImageUrl();
        imageUrl.setUrl("https://example.com/ref.png");
        image.setImageUrl(imageUrl);

        SeedanceRequest.ReferenceContent video = new SeedanceRequest.ReferenceContent();
        video.setType("video_url");
        video.setRole("reference_video");
        SeedanceRequest.VideoUrl videoUrl = new SeedanceRequest.VideoUrl();
        videoUrl.setUrl("https://example.com/input.mp4");
        video.setVideoUrl(videoUrl);
        request.setContents(List.of(image, video));

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"subject-task\",\"status\":\"queued\"}"));

        SeedanceResponse response = service.submitVideoGeneration(request);

        assertThat(response.getTaskId()).isEqualTo("subject-task");
        assertThat(response.getSubmitRequestUrl()).isEqualTo("https://toapis.com/v1/videos/generations");
        assertThat(response.getSubmitRequestBody())
                .contains("\"model\":\"doubao-seedance-2-0-260128\"")
                .contains("\"image_with_roles\"")
                .contains("\"video_with_roles\"")
                .contains("\"role\":\"reference_video\"")
                .contains("\"url\":\"https://example.com/input.mp4\"")
                .doesNotContain("contents/generations/tasks");
        verify(restTemplate).exchange(
                eq("https://toapis.com/v1/videos/generations"),
                eq(HttpMethod.POST),
                org.mockito.ArgumentMatchers.<HttpEntity<String>>argThat(entity -> {
                    String body = entity.getBody();
                    String authorization = entity.getHeaders().getFirst("Authorization");
                    return "Bearer test-toapis-key".equals(authorization)
                            && body != null
                            && body.contains("\"model\":\"doubao-seedance-2-0-260128\"")
                            && body.contains("\"resolution\":\"720p\"")
                            && body.contains("\"image_with_roles\":[{\"role\":\"reference_image\",\"url\":\"https://example.com/ref.png\"}]")
                            && body.contains("\"video_with_roles\":[{\"role\":\"reference_video\",\"url\":\"https://example.com/input.mp4\"}]");
                }),
                eq(String.class)
        );
    }

    @Test
    void queryFastTaskKeepsUsingToapisEvenAfterVipSubmit() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SeedanceServiceImpl service = newService(restTemplate);
        SeedanceRequest fastRequest = new SeedanceRequest();
        fastRequest.setModel("doubao-seedance-2-0-fast-260128");
        fastRequest.setPrompt("Fast 任务");
        fastRequest.setDuration(5);
        fastRequest.setRatio("16:9");
        SeedanceRequest vipRequest = new SeedanceRequest();
        vipRequest.setModel("doubao-seedance-2-0-260128");
        vipRequest.setPrompt("VIP 任务");
        vipRequest.setDuration(5);
        vipRequest.setRatio("16:9");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"fast-task\",\"status\":\"queued\"}"))
                .thenReturn(ResponseEntity.ok("{\"id\":\"vip-task\",\"status\":\"pending\"}"));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"fast-task\",\"status\":\"succeeded\",\"video_url\":\"https://example.com/fast.mp4\"}"));

        service.submitVideoGeneration(fastRequest);
        service.submitVideoGeneration(vipRequest);
        SeedanceResponse response = service.queryTaskStatus("fast-task");

        assertThat(response.getVideoUrl()).isEqualTo("https://example.com/fast.mp4");
        verify(restTemplate).exchange(
                eq("https://toapis.com/v1/videos/generations/fast-task"),
                eq(HttpMethod.GET),
                org.mockito.ArgumentMatchers.<HttpEntity<String>>argThat(entity ->
                        "Bearer test-toapis-key".equals(entity.getHeaders().getFirst("Authorization"))
                ),
                eq(String.class)
        );
    }

    @Test
    void queryFastTaskReadsCompletedVideoUrlFromToapisMetadata() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SeedanceServiceImpl service = newService(restTemplate);
        SeedanceRequest fastRequest = new SeedanceRequest();
        fastRequest.setModel("doubao-seedance-2-0-fast-260128");
        fastRequest.setPrompt("Fast 任务");
        fastRequest.setDuration(6);
        fastRequest.setRatio("16:9");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"fast-task\",\"status\":\"\"}"));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"fast-task\",\"status\":\"completed\",\"metadata\":{\"url\":\"https://files.toapis.com/result.mp4\"}}"));

        service.submitVideoGeneration(fastRequest);
        SeedanceResponse response = service.queryTaskStatus("fast-task");

        assertThat(response.getStatus()).isEqualTo("completed");
        assertThat(response.getVideoUrl()).isEqualTo("https://files.toapis.com/result.mp4");
    }

    @Test
    void generateVideoKeepsPollingWhenStatusQueryTemporarilyTimesOut() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SeedanceServiceImpl service = newService(restTemplate);
        ReflectionTestUtils.setField(service, "timeout", 50);
        ReflectionTestUtils.setField(service, "pollInterval", 1);
        SeedanceRequest request = new SeedanceRequest();
        request.setModel("doubao-seedance-2-0-fast-260128");
        request.setPrompt("Fast 任务");
        request.setDuration(6);
        request.setRatio("16:9");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"fast-timeout-task\",\"status\":\"queued\"}"));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("I/O error on GET request for \"https://toapis.com/v1/videos/generations/fast-timeout-task\": Connect timed out"))
                .thenReturn(ResponseEntity.ok("{\"id\":\"fast-timeout-task\",\"status\":\"completed\",\"metadata\":{\"url\":\"https://files.toapis.com/result.mp4\"}}"));

        SeedanceResponse response = service.generateVideo(request);

        assertThat(response.getStatus()).isEqualTo("completed");
        assertThat(response.getVideoUrl()).isEqualTo("https://files.toapis.com/result.mp4");
    }

    @Test
    void submitKlingV3OmniWithAudioDropsBaseVideoBecauseProviderRejectsBoth() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SeedanceServiceImpl service = newService(restTemplate);
        SeedanceRequest request = new SeedanceRequest();
        request.setModel("kling-v3-omni");
        request.setPrompt("古风少女转身");
        request.setDuration(8);
        request.setRatio("9:16");
        request.setWidth(2160);
        request.setHeight(3840);
        request.setWatermark(false);
        request.setGenerateAudio(true);

        SeedanceRequest.ReferenceContent image = new SeedanceRequest.ReferenceContent();
        image.setType("image_url");
        SeedanceRequest.ImageUrl imageUrl = new SeedanceRequest.ImageUrl();
        imageUrl.setUrl("https://example.com/ref.png");
        image.setImageUrl(imageUrl);
        SeedanceRequest.ReferenceContent video = new SeedanceRequest.ReferenceContent();
        video.setType("video_url");
        SeedanceRequest.VideoUrl videoUrl = new SeedanceRequest.VideoUrl();
        videoUrl.setUrl("https://example.com/ref.mp4");
        video.setVideoUrl(videoUrl);
        request.setContents(List.of(image, video));

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"kling-task\",\"status\":\"queued\"}"));

        SeedanceResponse response = service.submitVideoGeneration(request);

        assertThat(response.getTaskId()).isEqualTo("kling-task");
        verify(restTemplate).exchange(
                eq("https://toapis.com/v1/videos/generations"),
                eq(HttpMethod.POST),
                org.mockito.ArgumentMatchers.<HttpEntity<String>>argThat(entity -> {
                    String body = entity.getBody();
                    String authorization = entity.getHeaders().getFirst("Authorization");
                    if (!"Bearer test-toapis-key".equals(authorization) || body == null) {
                        return false;
                    }
                    JSONObject json = JSON.parseObject(body);
                    JSONObject metadata = json.getJSONObject("metadata");
                    return "kling-v3-omni".equals(json.getString("model"))
                            && "kling-v3-omni".equals(json.getString("model_name"))
                            && Boolean.FALSE.equals(json.getBoolean("multi_shot"))
                            && "古风少女转身".equals(json.getString("prompt"))
                            && "9:16".equals(json.getString("aspect_ratio"))
                            && Integer.valueOf(8).equals(json.getInteger("duration"))
                            && "pro".equals(json.getString("mode"))
                            && "on".equals(json.getString("sound"))
                            && Boolean.TRUE.equals(json.getBoolean("audio"))
                            && metadata != null
                            && metadata.getJSONArray("image_list") != null
                            && metadata.getJSONArray("image_list").size() == 1
                            && "https://example.com/ref.png".equals(metadata.getJSONArray("image_list").getJSONObject(0).getString("image_url"))
                            && !metadata.containsKey("mode")
                            && !metadata.containsKey("duration")
                            && !json.containsKey("video_list")
                            && !json.containsKey("image_with_roles");
                }),
                eq(String.class)
        );
    }

    @Test
    void submitKlingV3OmniAlwaysEnablesAudioAndDropsBaseVideoReference() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SeedanceServiceImpl service = newService(restTemplate);
        SeedanceRequest request = new SeedanceRequest();
        request.setModel("kling-v3-omni");
        request.setPrompt("古风少女转身");
        request.setDuration(8);
        request.setRatio("9:16");
        request.setWidth(2160);
        request.setHeight(3840);
        request.setWatermark(false);
        request.setGenerateAudio(false);

        SeedanceRequest.ReferenceContent video = new SeedanceRequest.ReferenceContent();
        video.setType("video_url");
        SeedanceRequest.VideoUrl videoUrl = new SeedanceRequest.VideoUrl();
        videoUrl.setUrl("https://example.com/ref.mp4");
        video.setVideoUrl(videoUrl);
        request.setContents(List.of(video));

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"kling-task\",\"status\":\"queued\"}"));

        service.submitVideoGeneration(request);

        verify(restTemplate).exchange(
                eq("https://toapis.com/v1/videos/generations"),
                eq(HttpMethod.POST),
                org.mockito.ArgumentMatchers.<HttpEntity<String>>argThat(entity -> {
                    String body = entity.getBody();
                    return body != null
                            && body.contains("\"sound\":\"on\"")
                            && body.contains("\"audio\":true")
                            && !body.contains("\"video_list\"");
                }),
                eq(String.class)
        );
    }

    @Test
    void submitKlingV3OmniImageOnlyCanGenerateSound() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SeedanceServiceImpl service = newService(restTemplate);
        SeedanceRequest request = new SeedanceRequest();
        request.setModel("kling-v3-omni");
        request.setPrompt("古风少女转身，环境中有风声");
        request.setDuration(5);
        request.setRatio("16:9");
        request.setWidth(1280);
        request.setHeight(720);
        request.setWatermark(false);
        request.setGenerateAudio(true);

        SeedanceRequest.ReferenceContent image = new SeedanceRequest.ReferenceContent();
        image.setType("image_url");
        SeedanceRequest.ImageUrl imageUrl = new SeedanceRequest.ImageUrl();
        imageUrl.setUrl("https://example.com/ref.png");
        image.setImageUrl(imageUrl);
        request.setContents(List.of(image));

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"kling-sound-task\",\"status\":\"queued\"}"));

        service.submitVideoGeneration(request);

        verify(restTemplate).exchange(
                eq("https://toapis.com/v1/videos/generations"),
                eq(HttpMethod.POST),
                org.mockito.ArgumentMatchers.<HttpEntity<String>>argThat(entity -> {
                    String body = entity.getBody();
                    return body != null
                            && body.contains("\"model_name\":\"kling-v3-omni\"")
                            && body.contains("\"sound\":\"on\"")
                            && body.contains("\"audio\":true")
                            && body.contains("\"metadata\":{\"image_list\":[{\"image_url\":\"https://example.com/ref.png\"}]}")
                            && !body.contains("\"video_list\"");
                }),
                eq(String.class)
        );
    }

    @Test
    void submitKlingV3OmniConvertsReferenceUrlsToHttps() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SeedanceServiceImpl service = newService(restTemplate);
        SeedanceRequest request = new SeedanceRequest();
        request.setModel("kling-v3-omni");
        request.setPrompt("Kling 参考图任务");
        request.setDuration(6);
        request.setRatio("16:9");

        SeedanceRequest.ReferenceContent image = new SeedanceRequest.ReferenceContent();
        image.setType("image_url");
        SeedanceRequest.ImageUrl imageUrl = new SeedanceRequest.ImageUrl();
        imageUrl.setUrl("http://movie-agent.oss-cn-beijing.aliyuncs.com/scenes/ref.png?Expires=1");
        image.setImageUrl(imageUrl);
        request.setContents(List.of(image));

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"kling-task\",\"status\":\"queued\"}"));

        service.submitVideoGeneration(request);

        verify(restTemplate).exchange(
                eq("https://toapis.com/v1/videos/generations"),
                eq(HttpMethod.POST),
                org.mockito.ArgumentMatchers.<HttpEntity<String>>argThat(entity -> {
                    String body = entity.getBody();
                    return body != null
                            && body.contains("\"metadata\":{\"image_list\":[{\"image_url\":\"https://movie-agent.oss-cn-beijing.aliyuncs.com/scenes/ref.png?Expires=1\"}]}")
                            && !body.contains("\"image_url\":\"http://movie-agent.oss-cn-beijing.aliyuncs.com");
                }),
                eq(String.class)
        );
    }

    @Test
    void submitKlingV3OmniUsesMetadataImageListForOfficialImagePlaceholders() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SeedanceServiceImpl service = newService(restTemplate);
        SeedanceRequest request = new SeedanceRequest();
        request.setModel("kling-v3-omni");
        request.setPrompt("场景参考 <<<image_1>>>，人物参考 <<<image_2>>>，古风少女转身");
        request.setDuration(8);
        request.setRatio("9:16");
        request.setGenerateAudio(true);

        SeedanceRequest.ReferenceContent scene = new SeedanceRequest.ReferenceContent();
        scene.setType("image_url");
        SeedanceRequest.ImageUrl sceneUrl = new SeedanceRequest.ImageUrl();
        sceneUrl.setUrl("https://example.com/scene.png");
        scene.setImageUrl(sceneUrl);

        SeedanceRequest.ReferenceContent role = new SeedanceRequest.ReferenceContent();
        role.setType("image_url");
        SeedanceRequest.ImageUrl roleUrl = new SeedanceRequest.ImageUrl();
        roleUrl.setUrl("https://example.com/role.png");
        role.setImageUrl(roleUrl);

        request.setContents(List.of(scene, role));

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"kling-task\",\"status\":\"queued\"}"));

        service.submitVideoGeneration(request);

        verify(restTemplate).exchange(
                eq("https://toapis.com/v1/videos/generations"),
                eq(HttpMethod.POST),
                org.mockito.ArgumentMatchers.<HttpEntity<String>>argThat(entity -> {
                    String body = entity.getBody();
                    return body != null
                            && body.contains("\"prompt\":\"场景参考 <<<image_1>>>，人物参考 <<<image_2>>>，古风少女转身\"")
                            && body.contains("\"metadata\":{\"image_list\":[{\"image_url\":\"https://example.com/scene.png\"},{\"image_url\":\"https://example.com/role.png\"}]}")
                            && !body.contains("\"image_list\":[{\"image_url\":\"https://example.com/scene.png\"},{\"image_url\":\"https://example.com/role.png\"}],\"metadata\"");
                }),
                eq(String.class)
        );
    }

    @Test
    void submitFailureKeepsProviderMessageAndStripsRequestId() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SeedanceServiceImpl service = newService(restTemplate);
        SeedanceRequest request = new SeedanceRequest();
        request.setModel("kling-v3-omni");
        request.setPrompt("Kling 失败任务");
        request.setDuration(6);
        request.setRatio("16:9");

        String errorBody = "{\"error\":{\"code\":\"\",\"message\":\"未指定模型名称，模型名称不能为空 (request id: 20260516031225417536878EXdLjYR0)\",\"type\":\"new_api_error\"}}";
        HttpClientErrorException exception = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                null,
                errorBody.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(exception);

        SeedanceResponse response = service.submitVideoGeneration(request);

        assertThat(response.getStatus()).isEqualTo("failed");
        assertThat(response.getErrorMessage()).isEqualTo("未指定模型名称，模型名称不能为空");
    }

    @Test
    void submitFailureUsesNeutralProviderWordingForNewApiErrors() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SeedanceServiceImpl service = newService(restTemplate);
        SeedanceRequest request = new SeedanceRequest();
        request.setModel("doubao-seedance-2-0-260128");
        request.setPrompt("主体替换任务");
        request.setDuration(6);
        request.setRatio("16:9");

        String errorBody = "{\"error\":{\"code\":\"AuthenticationError\",\"message\":\"The API key is invalid. Request ID: 123\"}}";
        HttpClientErrorException exception = HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED,
                "Unauthorized",
                null,
                errorBody.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(exception);

        SeedanceResponse response = service.submitVideoGeneration(request);

        assertThat(response.getStatus()).isEqualTo("failed");
        assertThat(response.getErrorMessage()).isEqualTo("视频生成服务鉴权失败，请检查 API Key 配置。");
        assertThat(response.getErrorMessage()).doesNotContain("火山");
    }

    @Test
    void submitFailureMapsNewApiQuotaMessageToRechargeHint() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SeedanceServiceImpl service = newService(restTemplate);
        SeedanceRequest request = new SeedanceRequest();
        request.setModel("doubao-seedance-2-0-260128");
        request.setPrompt("主体替换任务");
        request.setDuration(8);
        request.setRatio("16:9");

        String errorBody = "{\"error\":{\"code\":\"BadRequest\",\"message\":\"user quota is not enough for doubao-seedance-2-0-260128 deposit, user quota: $2.24, required deposit: $2.30\"}}";
        HttpClientErrorException exception = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                null,
                errorBody.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(exception);

        SeedanceResponse response = service.submitVideoGeneration(request);

        assertThat(response.getStatus()).isEqualTo("failed");
        assertThat(response.getErrorMessage()).isEqualTo("视频生成服务账号欠费或余额不足，请充值后重试。");
        assertThat(response.getErrorMessage()).doesNotContain("火山");
    }

    @Test
    void submitToapisHttpFailureKeepsSubmitRequestMetadataForDiagnostics() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SeedanceServiceImpl service = newService(restTemplate);
        SeedanceRequest request = new SeedanceRequest();
        request.setModel("doubao-seedance-2-0-260128");
        request.setPrompt("主体替换任务");
        request.setDuration(8);
        request.setRatio("16:9");

        String errorBody = "{\"error\":{\"code\":\"InputVideoSensitiveContentDetected.PrivacyInformation\",\"message\":\"The request failed because the input video may contain real person. Request id: abc\"}}";
        HttpClientErrorException exception = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                null,
                errorBody.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(exception);

        SeedanceResponse response = service.submitVideoGeneration(request);

        assertThat(response.getStatus()).isEqualTo("failed");
        assertThat(response.getSubmitRequestUrl()).isEqualTo("https://toapis.com/v1/videos/generations");
        assertThat(response.getSubmitModel()).isEqualTo("doubao-seedance-2-0-260128");
        assertThat(response.getSubmitRequestBody())
                .contains("\"model\":\"doubao-seedance-2-0-260128\"")
                .contains("\"prompt\":\"主体替换任务\"");
    }

    @Test
    void queryKlingTaskReadsVideoUrlFromTaskResultVideos() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SeedanceServiceImpl service = newService(restTemplate);
        SeedanceRequest request = new SeedanceRequest();
        request.setModel("kling-v3-omni");
        request.setPrompt("Kling 任务");
        request.setDuration(5);
        request.setRatio("16:9");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"kling-task\",\"status\":\"queued\"}"));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"kling-task\",\"status\":\"completed\",\"data\":{\"task_result\":{\"videos\":[{\"url\":\"https://files.toapis.com/kling.mp4\",\"cover_url\":\"https://files.toapis.com/kling.jpg\"}]}}}"));

        service.submitVideoGeneration(request);
        SeedanceResponse response = service.queryTaskStatus("kling-task");

        assertThat(response.getStatus()).isEqualTo("completed");
        assertThat(response.getVideoUrl()).isEqualTo("https://files.toapis.com/kling.mp4");
        assertThat(response.getThumbnailUrl()).isEqualTo("https://files.toapis.com/kling.jpg");
        verify(restTemplate).exchange(
                eq("https://toapis.com/v1/videos/generations/kling-task"),
                eq(HttpMethod.GET),
                org.mockito.ArgumentMatchers.<HttpEntity<String>>argThat(entity ->
                        "Bearer test-toapis-key".equals(entity.getHeaders().getFirst("Authorization"))
                ),
                eq(String.class)
        );
    }

    @Test
    void queryKlingTaskReadsVideoUrlFromResultDataArray() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SeedanceServiceImpl service = newService(restTemplate);
        SeedanceRequest request = new SeedanceRequest();
        request.setModel("kling-v3-omni");
        request.setPrompt("Kling 任务");
        request.setDuration(6);
        request.setRatio("16:9");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"kling-result-task\",\"status\":\"queued\"}"));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"kling-result-task\",\"status\":\"completed\",\"progress\":100,\"result\":{\"data\":[{\"format\":\"mp4\",\"url\":\"https://files.toapis.com/images/kling-result-task/output.mp4\"}],\"type\":\"video\"}}"));

        service.submitVideoGeneration(request);
        SeedanceResponse response = service.queryTaskStatus("kling-result-task");

        assertThat(response.getStatus()).isEqualTo("completed");
        assertThat(response.getVideoUrl()).isEqualTo("https://files.toapis.com/images/kling-result-task/output.mp4");
    }

    @Test
    void submitKlingV3OmniUltraUsesStdModeAndRecordsProviderRequest() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SeedanceServiceImpl service = newService(restTemplate);
        SeedanceRequest request = new SeedanceRequest();
        request.setModel("kling-v3-omni");
        request.setPrompt("720p Kling 任务");
        request.setDuration(5);
        request.setRatio("16:9");
        request.setWidth(1280);
        request.setHeight(720);
        request.setGenerateAudio(true);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"kling-720p\",\"status\":\"queued\"}"));

        SeedanceResponse response = service.submitVideoGeneration(request);

        assertThat(response.getSubmitModel()).isEqualTo("kling-v3-omni");
        assertThat(response.getSubmitRequestUrl()).isEqualTo("https://toapis.com/v1/videos/generations");
        assertThat(response.getSubmitRequestBody())
                .contains("\"sound\":\"on\"")
                .contains("\"audio\":true")
                .contains("\"mode\":\"std\"")
                .doesNotContain("\"mode\":\"pro\"");

        verify(restTemplate).exchange(
                eq("https://toapis.com/v1/videos/generations"),
                eq(HttpMethod.POST),
                org.mockito.ArgumentMatchers.<HttpEntity<String>>argThat(entity -> {
                    String body = entity.getBody();
                    return body != null
                            && body.contains("\"sound\":\"on\"")
                            && body.contains("\"audio\":true")
                            && body.contains("\"mode\":\"std\"")
                            && !body.contains("\"mode\":\"pro\"");
                }),
                eq(String.class)
        );
    }

    @Test
    void submitKlingV3Omni4kUsesProModeBecauseProvider4kIsExposedAsPro() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SeedanceServiceImpl service = newService(restTemplate);
        SeedanceRequest request = new SeedanceRequest();
        request.setModel("kling-v3-omni");
        request.setPrompt("4K Kling 默认音频任务");
        request.setDuration(5);
        request.setRatio("16:9");
        request.setWidth(1920);
        request.setHeight(1080);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"kling-4k-default-audio\",\"status\":\"queued\"}"));

        SeedanceResponse response = service.submitVideoGeneration(request);

        assertThat(response.getSubmitRequestBody())
                .contains("\"sound\":\"on\"")
                .contains("\"audio\":true")
                .contains("\"mode\":\"pro\"")
                .doesNotContain("\"mode\":\"4k\"");
    }

    @Test
    void generateVideoKeepsSubmitRequestMetadataAfterPollingCompletes() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SeedanceServiceImpl service = newService(restTemplate);
        SeedanceRequest request = new SeedanceRequest();
        request.setModel("kling-v3-omni");
        request.setPrompt("Kling 完整入参任务");
        request.setDuration(5);
        request.setRatio("16:9");
        request.setGenerateAudio(true);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"kling-full-params\",\"status\":\"queued\"}"));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"kling-full-params\",\"status\":\"completed\",\"video_url\":\"https://files.example.com/out.mp4\"}"));

        SeedanceResponse response = service.generateVideo(request);

        assertThat(response.getStatus()).isEqualTo("completed");
        assertThat(response.getSubmitModel()).isEqualTo("kling-v3-omni");
        assertThat(response.getSubmitRequestUrl()).isEqualTo("https://toapis.com/v1/videos/generations");
        assertThat(response.getSubmitRequestBody())
                .contains("\"model_name\":\"kling-v3-omni\"")
                .contains("\"sound\":\"on\"");
    }

    private SeedanceServiceImpl newService(RestTemplate restTemplate) {
        SeedanceServiceImpl service = new SeedanceServiceImpl(
                mock(ShotMapper.class),
                mock(ShotCharacterMapper.class),
                mock(SceneMapper.class),
                mock(RoleMapper.class),
                mock(OssService.class)
        );
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(service, "apiKey", "test-volc-key");
        ReflectionTestUtils.setField(service, "baseUrl", "https://ark.example.com/api/v3");
        ReflectionTestUtils.setField(service, "fastApiKey", "test-toapis-key");
        ReflectionTestUtils.setField(service, "fastBaseUrl", "https://toapis.com/v1");
        ReflectionTestUtils.setField(service, "timeout", 1000);
        ReflectionTestUtils.setField(service, "pollInterval", 1000);
        return service;
    }
}
