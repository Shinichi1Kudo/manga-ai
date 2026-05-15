package com.manga.ai.subject.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.manga.ai.common.constants.CreditConstants;
import com.manga.ai.common.enums.CreditUsageType;
import com.manga.ai.common.exception.BusinessException;
import com.manga.ai.common.service.OssService;
import com.manga.ai.subject.dto.SubjectReplacementCreateRequest;
import com.manga.ai.subject.dto.SubjectReplacementItemDTO;
import com.manga.ai.subject.dto.SubjectReplacementTaskVO;
import com.manga.ai.subject.entity.SubjectReplacementTask;
import com.manga.ai.subject.mapper.SubjectReplacementTaskMapper;
import com.manga.ai.subject.service.SubjectReplacementService;
import com.manga.ai.user.service.UserService;
import com.manga.ai.user.service.impl.UserServiceImpl.UserContextHolder;
import com.manga.ai.video.dto.SeedanceRequest;
import com.manga.ai.video.dto.SeedanceResponse;
import com.manga.ai.video.service.SeedanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 主体替换服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubjectReplacementServiceImpl implements SubjectReplacementService {

    private static final String SUBJECT_REPLACEMENT_TOAPIS_MODEL = "doubao-seedance-2-0-260128";
    private static final List<String> SUBJECT_REPLACEMENT_TOAPIS_MODELS = Arrays.asList(
            "doubao-seedance-2-0-260128",
            "seedance-2.0",
            "doubao-seedance-2-0-fast-260128",
            "seedance-2.0-fast"
    );
    private static final List<String> ALLOWED_VIDEO_TYPES = Arrays.asList(
            "video/mp4", "video/webm", "video/quicktime", "video/x-m4v"
    );
    private static final List<String> ALLOWED_IMAGE_TYPES = Arrays.asList(
            "image/jpeg", "image/png", "image/webp"
    );
    private static final List<String> ALLOWED_ASPECT_RATIOS = Arrays.asList(
            "16:9", "9:16", "1:1", "4:3", "3:4", "21:9"
    );
    private static final List<String> ALLOWED_REPLACEMENT_TYPES = Arrays.asList(
            "person", "object"
    );
    private static final long MAX_VIDEO_SIZE = 200L * 1024 * 1024;
    private static final long MAX_IMAGE_SIZE = 10L * 1024 * 1024;
    private static final int MAX_TASK_NAME_LENGTH = 80;

    private final SubjectReplacementTaskMapper taskMapper;
    private final OssService ossService;
    private final SeedanceService seedanceService;
    private final UserService userService;
    @Qualifier("videoGenerateExecutor")
    private final Executor videoGenerateExecutor;

    @Value("${volcengine.seedance.subject-replacement-model:doubao-seedance-2-0-260128}")
    private String subjectReplacementModel;

    @Override
    public SubjectReplacementTaskVO createTask(SubjectReplacementCreateRequest request) {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }

        validateCreateRequest(request);

        String prompt = buildPrompt(request.getReplacements());
        SubjectReplacementTask task = new SubjectReplacementTask();
        task.setUserId(userId);
        task.setTaskName(normalizeText(request.getTaskName()));
        task.setOriginalVideoUrl(request.getOriginalVideoUrl().trim());
        task.setAspectRatio(normalizeAspectRatio(request.getAspectRatio()));
        task.setDuration(normalizeDuration(request.getDuration()));
        task.setGenerateAudio(request.getGenerateAudio() == null ? true : request.getGenerateAudio());
        task.setWatermark(request.getWatermark() == null ? false : request.getWatermark());
        task.setModel(normalizeSubjectReplacementModel(subjectReplacementModel));
        task.setPrompt(prompt);
        task.setReplacementsJson(JSON.toJSONString(request.getReplacements()));
        task.setStatus("pending");
        int requiredCredits = CreditConstants.calculateSubjectReplacementCredits(task.getDuration());
        task.setDeductedCredits(requiredCredits);
        task.setCreditsRefunded(false);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(task);

        try {
            userService.deductCredits(userId, requiredCredits, CreditUsageType.SUBJECT_REPLACEMENT.getCode(),
                    "主体替换-任务" + task.getId(), task.getId(), "SUBJECT_REPLACEMENT");
        } catch (RuntimeException e) {
            deleteInsertedTask(task.getId(), userId);
            throw e;
        }
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        log.info("主体替换扣费: userId={}, taskId={}, duration={}s, credits={}",
                userId, task.getId(), task.getDuration(), requiredCredits);

        videoGenerateExecutor.execute(() -> executeTask(task.getId()));
        return toVO(task);
    }

    @Override
    public void executeTask(Long taskId) {
        SubjectReplacementTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("主体替换任务不存在: taskId={}", taskId);
            return;
        }

        long startTime = System.currentTimeMillis();
        try {
            task.setStatus("running");
            task.setSubmittedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);

            List<SubjectReplacementItemDTO> replacements = parseReplacements(task.getReplacementsJson());

            SeedanceRequest request = new SeedanceRequest();
            request.setPrompt(buildPrompt(replacements));
            request.setDuration(task.getDuration());
            request.setRatio(task.getAspectRatio());
            request.setGenerateAudio(task.getGenerateAudio());
            request.setWatermark(task.getWatermark());
            String useModel = normalizeSubjectReplacementModel(task.getModel());
            task.setModel(useModel);
            request.setModel(useModel);

            List<SeedanceRequest.ReferenceContent> contents = new ArrayList<>();
            int imageIndex = 1;
            for (SubjectReplacementItemDTO item : replacements) {
                SeedanceRequest.ReferenceContent imageContent = new SeedanceRequest.ReferenceContent();
                imageContent.setType("image_url");
                imageContent.setRole("reference_image");

                SeedanceRequest.ImageUrl imageUrl = new SeedanceRequest.ImageUrl();
                imageUrl.setUrl(item.getReferenceImageUrl());
                imageContent.setImageUrl(imageUrl);
                contents.add(imageContent);
                log.info("主体替换参考图映射: taskId={}, 图{} -> type={}, source={}, target={}, url={}",
                        taskId, imageIndex, replacementTypeLabel(item.getReplacementType()),
                        item.getSourceObject(), item.getTargetDescription(), item.getReferenceImageUrl());
                imageIndex++;
            }

            SeedanceRequest.ReferenceContent videoContent = new SeedanceRequest.ReferenceContent();
            videoContent.setType("video_url");
            videoContent.setRole("reference_video");
            SeedanceRequest.VideoUrl videoUrl = new SeedanceRequest.VideoUrl();
            videoUrl.setUrl(task.getOriginalVideoUrl());
            videoContent.setVideoUrl(videoUrl);
            contents.add(videoContent);
            request.setContents(contents);
            log.info("主体替换提交新视频API: taskId={}, model={}, duration={}s, ratio={}, images={}, hasVideo={}",
                    taskId, request.getModel(), request.getDuration(), request.getRatio(), replacements.size(), !isBlank(task.getOriginalVideoUrl()));

            SeedanceResponse response = seedanceService.generateVideo(request);
            task.setVolcengineTaskId(response.getTaskId());
            task.setSeed(response.getSeed());

            if ("completed".equalsIgnoreCase(response.getStatus()) || "succeeded".equalsIgnoreCase(response.getStatus())) {
                task.setStatus("succeeded");
                task.setOutputVideoUrl(response.getVideoUrl());
                task.setThumbnailUrl(response.getThumbnailUrl());
                task.setGenerationDuration((int) ((System.currentTimeMillis() - startTime) / 1000));
                task.setCompletedAt(LocalDateTime.now());
                log.info("主体替换任务完成: taskId={}", taskId);
            } else {
                task.setStatus("failed");
                task.setErrorMessage(toFriendlyError(response.getErrorMessage() != null ? response.getErrorMessage() : "主体替换失败"));
                task.setCompletedAt(LocalDateTime.now());
                refundCreditsIfNeeded(task);
                log.warn("主体替换任务失败: taskId={}, status={}, providerUrl={}, providerModel={}, error={}",
                        taskId, response.getStatus(), response.getSubmitRequestUrl(), response.getSubmitModel(), response.getErrorMessage());
            }
        } catch (Exception e) {
            log.error("主体替换任务异常: taskId={}", taskId, e);
            task.setStatus("failed");
            task.setErrorMessage(toFriendlyError(e.getMessage()));
            task.setCompletedAt(LocalDateTime.now());
            refundCreditsIfNeeded(task);
        } finally {
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);
        }
    }

    @Override
    public SubjectReplacementTaskVO getTask(Long taskId) {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }

        SubjectReplacementTask task = taskMapper.selectById(taskId);
        if (task == null || !userId.equals(task.getUserId())) {
            throw new BusinessException(404, "任务不存在");
        }
        return toVO(task);
    }

    @Override
    public List<SubjectReplacementTaskVO> listMyTasks(Integer limit) {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }

        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 50));
        LambdaQueryWrapper<SubjectReplacementTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SubjectReplacementTask::getUserId, userId)
                .orderByDesc(SubjectReplacementTask::getCreatedAt)
                .last("LIMIT " + safeLimit);

        List<SubjectReplacementTask> tasks = taskMapper.selectList(wrapper);
        List<SubjectReplacementTaskVO> result = new ArrayList<>();
        for (SubjectReplacementTask task : tasks) {
            result.add(toVO(task));
        }
        return result;
    }

    @Override
    public SubjectReplacementTaskVO renameTask(Long taskId, String taskName) {
        SubjectReplacementTask task = getOwnedTask(taskId);
        String normalizedName = normalizeTaskName(taskName);
        task.setTaskName(normalizedName);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        return toVO(task);
    }

    @Override
    public void deleteTask(Long taskId) {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        if (taskId == null) {
            throw new BusinessException(400, "任务ID不能为空");
        }

        LambdaQueryWrapper<SubjectReplacementTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SubjectReplacementTask::getId, taskId)
                .eq(SubjectReplacementTask::getUserId, userId);
        taskMapper.delete(wrapper);
    }

    @Override
    public String uploadVideo(MultipartFile file) {
        validateFile(file, ALLOWED_VIDEO_TYPES, Arrays.asList(".mp4", ".webm", ".mov", ".m4v"),
                MAX_VIDEO_SIZE, "只支持 MP4、WEBM、MOV、M4V 格式的视频");
        try {
            String contentType = normalizeVideoContentType(file.getContentType(), file.getOriginalFilename());
            String extension = resolveVideoExtension(contentType, file.getOriginalFilename());
            String url = ossService.uploadVideo(file.getBytes(), "subject-replacement/videos", contentType, extension);
            if (url == null) {
                throw new BusinessException("视频上传失败");
            }
            return url;
        } catch (IOException e) {
            throw new BusinessException("读取视频失败");
        }
    }

    @Override
    public String uploadReferenceImage(MultipartFile file) {
        validateFile(file, ALLOWED_IMAGE_TYPES, Arrays.asList(".jpg", ".jpeg", ".png", ".webp"),
                MAX_IMAGE_SIZE, "只支持 JPG、PNG、WEBP 格式的图片");
        try {
            String contentType = normalizeImageContentType(file.getContentType(), file.getOriginalFilename());
            String extension = resolveImageExtension(contentType, file.getOriginalFilename());
            String url = ossService.uploadImage(file.getBytes(), "subject-replacement/references", contentType, extension);
            if (url == null) {
                throw new BusinessException("参考图上传失败");
            }
            return url;
        } catch (IOException e) {
            throw new BusinessException("读取参考图失败");
        }
    }

    private void validateCreateRequest(SubjectReplacementCreateRequest request) {
        if (request == null) {
            throw new BusinessException(400, "请求不能为空");
        }
        if (request.getOriginalVideoUrl() == null || request.getOriginalVideoUrl().trim().isEmpty()) {
            throw new BusinessException(400, "请先上传原视频");
        }
        if (request.getReplacements() == null || request.getReplacements().isEmpty()) {
            throw new BusinessException(400, "请至少添加一组替换对象");
        }
        if (request.getReplacements().size() > 9) {
            throw new BusinessException(400, "最多支持9组替换对象");
        }
        String ratio = normalizeAspectRatio(request.getAspectRatio());
        if (!ALLOWED_ASPECT_RATIOS.contains(ratio)) {
            throw new BusinessException(400, "不支持的视频比例");
        }
        normalizeDuration(request.getDuration());

        int index = 1;
        for (SubjectReplacementItemDTO item : request.getReplacements()) {
            if (item == null) {
                throw new BusinessException(400, "替换对象" + index + "不能为空");
            }
            if (isBlank(item.getSourceObject())) {
                throw new BusinessException(400, "请填写替换对象" + index + "的原视频对象描述");
            }
            if (isBlank(item.getTargetDescription())) {
                throw new BusinessException(400, "请填写替换对象" + index + "的替换后描述");
            }
            if (isBlank(item.getReferenceImageUrl())) {
                throw new BusinessException(400, "请上传替换对象" + index + "的参考图");
            }
            trimItem(item);
            if (!ALLOWED_REPLACEMENT_TYPES.contains(item.getReplacementType())) {
                throw new BusinessException(400, "替换对象" + index + "的替换类型不支持");
            }
            index++;
        }
    }

    private String buildPrompt(List<SubjectReplacementItemDTO> replacements) {
        StringBuilder prompt = new StringBuilder();
        boolean hasPerson = false;
        boolean hasObject = false;
        prompt.append("请对@视频1执行视频对象替换。\n\n");
        prompt.append("保持原视频的镜头运动、构图、时长、节奏、背景、光线、动作轨迹、遮挡关系、交互关系和未指定内容不变。");
        prompt.append("只替换下方映射表中明确指定的对象，不替换其他人物、物品或背景元素。\n\n");
        prompt.append("对象替换映射：\n");

        int index = 1;
        for (SubjectReplacementItemDTO item : replacements) {
            String replacementType = normalizeReplacementType(item.getReplacementType());
            boolean personType = "person".equals(replacementType);
            hasPerson = hasPerson || personType;
            hasObject = hasObject || !personType;

            prompt.append(index).append(". 替换类型：").append(replacementTypeLabel(replacementType))
                    .append("。将@视频1中【").append(item.getSourceObject());
            List<String> hints = new ArrayList<>();
            if (!isBlank(item.getAppearTime())) {
                hints.add("出现时间：" + item.getAppearTime());
            }
            if (!isBlank(item.getScreenPosition())) {
                hints.add("画面位置：" + item.getScreenPosition());
            }
            if (!isBlank(item.getAppearanceHint())) {
                hints.add("语言改成" + item.getAppearanceHint());
            }
            if (!hints.isEmpty()) {
                prompt.append("；").append(String.join("；", hints));
            }
            prompt.append("】替换为【").append(replacementTargetPrompt(index, item)).append("】。\n");
            index++;
        }

        prompt.append("\n替换要求：\n");
        prompt.append("- 参考图按 content 数组中的 image_url 顺序编号：第1个 image_url 是图1，第2个 image_url 是图2，以此类推。\n");
        prompt.append("- 每个映射只允许使用对应编号的参考图，不要把图1、图2或其他参考图串用。\n");
        prompt.append("- 每个替换对象必须与原视频中的目标对象一一对应，不要串换。\n");
        prompt.append("- 如果映射中填写了“语言改成xxx”，则将替换对象涉及的口播、字幕、可见文字或语言风格改成xxx；没有填写语言时，不额外改变语言。\n");
        if (hasPerson) {
            prompt.append("- 替换类型为人物时：必须替换为参考图中的人物身份，不得只替换服装、颜色或发型，不得保留原视频人物的脸、五官、脸型或年龄感。\n");
            prompt.append("- 人物可见脸部时，以对应参考图的人脸和身份特征为准；保留原视频动作、姿态、表情节奏、走位、手势和运镜，但不要保留原人物长相。\n");
            prompt.append("- 人物补充描述较短时，仍以参考图的完整人物身份为主，不要只执行文字里提到的局部特征。\n");
        }
        if (hasObject) {
            prompt.append("- 替换类型为物品时：不要执行人物换脸、人物身份迁移或人脸约束，只替换指定物品本身。\n");
            prompt.append("- 物品以对应参考图的外观为准，包括形状、材质、颜色、纹理、结构和可见文字/图案；保留原物品的位置、大小关系、运动轨迹、遮挡关系和被手持/触碰方式。\n");
        }
        prompt.append("- 未列入映射表的内容全部保持原样。\n");
        prompt.append("- 多个对象同时出现、遮挡或交叉时，严格按照用户描述进行替换。\n");
        return prompt.toString();
    }

    private String replacementTargetPrompt(int index, SubjectReplacementItemDTO item) {
        String replacementType = normalizeReplacementType(item.getReplacementType());
        String targetDescription = item.getTargetDescription();
        if ("object".equals(replacementType)) {
            return "第" + index + "张参考图/图" + index
                    + "中的同一物品外观。只替换该物品本身，形状、材质、颜色、纹理、结构和可见文字/图案以参考图为准；替换后物品补充描述为："
                    + targetDescription;
        }
        return "第" + index + "张参考图/图" + index
                + "中的同一人物/角色身份。必须完整迁移参考图人物身份，包括脸型、五官、发型、年龄感、肤色、妆容、服装和整体气质；替换后人物补充描述为："
                + targetDescription;
    }

    private SubjectReplacementTaskVO toVO(SubjectReplacementTask task) {
        SubjectReplacementTaskVO vo = new SubjectReplacementTaskVO();
        BeanUtils.copyProperties(task, vo);
        vo.setReplacements(parseReplacements(task.getReplacementsJson()));
        vo.setStatusDesc(statusDesc(task.getStatus()));
        vo.setProgressPercent(progressPercent(task.getStatus()));
        vo.setErrorMessage(toFriendlyError(task.getErrorMessage()));
        return vo;
    }

    private List<SubjectReplacementItemDTO> parseReplacements(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return JSON.parseArray(json, SubjectReplacementItemDTO.class);
        } catch (Exception e) {
            log.warn("解析主体替换配置失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private Integer progressPercent(String status) {
        if ("succeeded".equalsIgnoreCase(status)) {
            return 100;
        }
        if ("running".equalsIgnoreCase(status)) {
            return 60;
        }
        if ("failed".equalsIgnoreCase(status)) {
            return 100;
        }
        return 10;
    }

    private String statusDesc(String status) {
        if ("succeeded".equalsIgnoreCase(status)) {
            return "已完成";
        }
        if ("running".equalsIgnoreCase(status)) {
            return "生成中";
        }
        if ("failed".equalsIgnoreCase(status)) {
            return "生成失败";
        }
        return "排队中";
    }

    private String toFriendlyError(String message) {
        if (message == null || message.isBlank()) {
            return "主体替换失败，请稍后重试";
        }
        String normalized = message.toLowerCase();
        if (normalized.contains("input video") && normalized.contains("real person")) {
            return "输入视频可能包含真人画面，平台审核未通过，请更换视频后重试。";
        }
        if (normalized.contains("input image") && normalized.contains("real person")) {
            return "输入参考图可能包含真人画面，平台审核未通过，请更换参考图后重试。";
        }
        if (normalized.contains("privacyinformation") || normalized.contains("real person")) {
            return "输入素材可能包含真人画面，平台审核未通过，请更换视频或参考图后重试。";
        }
        if (normalized.contains("inputvideosensitivecontentdetected")) {
            return "输入视频可能包含敏感内容，请更换视频后重试。";
        }
        if (normalized.contains("inputimagesensitivecontentdetected")) {
            return "输入参考图可能包含敏感内容，请更换参考图后重试。";
        }
        if (normalized.contains("inputtextsensitivecontentdetected")) {
            return "输入描述可能包含敏感内容，请调整替换描述后重试。";
        }
        if (normalized.contains("outputvideosensitivecontentdetected")
                || normalized.contains("outputimagesensitivecontentdetected")
                || normalized.contains("outputtextsensitivecontentdetected")) {
            return "生成结果可能触发内容审核，请调整视频、参考图或替换描述后重试。";
        }
        if (normalized.contains("policyviolation") || normalized.contains("copyright")) {
            return "输入或生成内容可能涉及版权限制，请更换素材或调整描述后重试。";
        }
        if (normalized.contains("sensitivecontent") || normalized.contains("riskdetection")
                || normalized.contains("contentviolation") || normalized.contains("contentsecurity")) {
            return "内容可能触发平台审核，请调整视频、参考图或替换描述后重试。";
        }
        if (normalized.contains("missingparameter")) {
            return "请求缺少必要参数，请检查视频、参考图、比例、时长和替换描述后重试。";
        }
        if (normalized.contains("invalidparameter") || normalized.contains("invalidargumenterror")
                || normalized.contains("invalidimageurl")) {
            return "请求参数不合法，请检查视频、参考图格式或替换描述后重试。";
        }
        if (normalized.contains("outofcontexterror")) {
            return "输入内容过长，已超过模型上下文限制，请减少参考图数量或缩短替换描述后重试。";
        }
        if (normalized.contains("authenticationerror") || normalized.contains("invalidapikey")) {
            return "视频生成服务鉴权失败，请检查 API Key 配置。";
        }
        if (normalized.contains("modelnotopen") || normalized.contains("servicenotopen")) {
            return "模型服务未开通，请开通对应模型后重试。";
        }
        if (normalized.contains("accountoverdueerror") || normalized.contains("serviceoverdue")
                || normalized.contains("insufficientbalance") || normalized.contains("overdue")) {
            return "视频生成服务账号欠费或余额不足，请充值后重试。";
        }
        if (normalized.contains("accessdenied") || normalized.contains("permissiondenied")) {
            return "当前账号没有访问该模型或资源的权限，请检查权限或白名单后重试。";
        }
        if (normalized.contains("ratelimit") || normalized.contains("quotaexceeded")
                || normalized.contains("serveroverloaded") || normalized.contains("requestbursttoofast")
                || normalized.contains("inflightbatchsizeexceeded")) {
            return "当前请求过多或额度已达上限，请稍后重试，或检查视频生成服务额度和并发限制。";
        }
        if (normalized.contains("invalidendpointormodel") || normalized.contains("unsupportedmodel")
                || normalized.contains("modelnotfound")) {
            return "模型或推理接入点不可用，请检查模型配置后重试。";
        }
        if (normalized.contains("internalserviceerror") || normalized.contains("internalservererror")
                || normalized.contains("internal error")) {
            return "视频生成服务内部异常，请稍后重试。";
        }
        if (message.contains("超时") || normalized.contains("timeout") || normalized.contains("timed out")) {
            return "视频生成超时，请稍后重试。";
        }
        return stripRequestId(message);
    }

    private String stripRequestId(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("(?i)\\s*Request\\s*ID\\s*:\\s*[^;\\n。]*", "")
                .replaceAll("(?i)\\s*Request\\s*id\\s*:\\s*[^;\\n。]*", "")
                .trim();
    }

    private void validateFile(MultipartFile file, List<String> allowedTypes, List<String> allowedExtensions,
                              long maxSize, String typeMessage) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请选择文件");
        }
        String contentType = file.getContentType();
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        boolean contentTypeAllowed = contentType != null && allowedTypes.contains(contentType);
        boolean extensionAllowed = allowedExtensions.stream().anyMatch(filename::endsWith);
        if (!contentTypeAllowed && !extensionAllowed) {
            throw new BusinessException(400, typeMessage);
        }
        if (file.getSize() > maxSize) {
            throw new BusinessException(400, "文件大小超过限制");
        }
    }

    private Integer normalizeDuration(Integer duration) {
        if (duration == null) {
            return 5;
        }
        if (duration < 1 || duration > 15) {
            throw new BusinessException(400, "视频时长需在1-15秒之间");
        }
        return duration;
    }

    private String normalizeAspectRatio(String ratio) {
        return isBlank(ratio) ? "16:9" : ratio.trim();
    }

    private String normalizeText(String value) {
        return value == null ? null : value.trim();
    }

    private String normalizeTaskName(String value) {
        String normalized = normalizeText(value);
        if (isBlank(normalized)) {
            throw new BusinessException(400, "任务名称不能为空");
        }
        if (normalized.length() > MAX_TASK_NAME_LENGTH) {
            throw new BusinessException(400, "任务名称不能超过" + MAX_TASK_NAME_LENGTH + "个字符");
        }
        return normalized;
    }

    private SubjectReplacementTask getOwnedTask(Long taskId) {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        if (taskId == null) {
            throw new BusinessException(400, "任务ID不能为空");
        }
        SubjectReplacementTask task = taskMapper.selectById(taskId);
        if (task == null || !userId.equals(task.getUserId())) {
            throw new BusinessException(404, "任务不存在");
        }
        return task;
    }

    private void deleteInsertedTask(Long taskId, Long userId) {
        if (taskId == null || userId == null) {
            return;
        }
        LambdaQueryWrapper<SubjectReplacementTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SubjectReplacementTask::getId, taskId)
                .eq(SubjectReplacementTask::getUserId, userId);
        taskMapper.delete(wrapper);
    }

    private void trimItem(SubjectReplacementItemDTO item) {
        item.setSourceObject(normalizeText(item.getSourceObject()));
        item.setReplacementType(normalizeReplacementType(item.getReplacementType()));
        item.setTargetDescription(normalizeText(item.getTargetDescription()));
        item.setReferenceImageUrl(normalizeText(item.getReferenceImageUrl()));
        item.setAppearTime(normalizeText(item.getAppearTime()));
        item.setScreenPosition(normalizeText(item.getScreenPosition()));
        item.setAppearanceHint(normalizeText(item.getAppearanceHint()));
    }

    private String normalizeReplacementType(String type) {
        if (isBlank(type)) {
            return "person";
        }
        String normalized = type.trim().toLowerCase();
        if ("role".equals(normalized) || "character".equals(normalized)) {
            return "person";
        }
        if ("object".equals(normalized) || "tool".equals(normalized)
                || "prop".equals(normalized) || "product".equals(normalized)) {
            return "object";
        }
        return normalized;
    }

    private String normalizeSubjectReplacementModel(String configuredModel) {
        if (isBlank(configuredModel)) {
            return SUBJECT_REPLACEMENT_TOAPIS_MODEL;
        }
        String normalized = configuredModel.trim();
        if (SUBJECT_REPLACEMENT_TOAPIS_MODELS.contains(normalized)) {
            return normalized;
        }
        log.warn("主体替换模型不支持新视频API，已切换为默认模型: configuredModel={}, fallback={}",
                normalized, SUBJECT_REPLACEMENT_TOAPIS_MODEL);
        return SUBJECT_REPLACEMENT_TOAPIS_MODEL;
    }

    private String replacementTypeLabel(String type) {
        String normalized = normalizeReplacementType(type);
        if ("object".equals(normalized)) {
            return "物品";
        }
        return "人物";
    }

    private void refundCreditsIfNeeded(SubjectReplacementTask task) {
        if (task == null || userService == null) {
            return;
        }
        Integer deductedCredits = task.getDeductedCredits();
        if (deductedCredits == null || deductedCredits <= 0 || Boolean.TRUE.equals(task.getCreditsRefunded())) {
            return;
        }
        userService.refundCredits(task.getUserId(), deductedCredits,
                "主体替换失败返还-任务" + task.getId(), task.getId(), "SUBJECT_REPLACEMENT");
        task.setCreditsRefunded(true);
        log.info("主体替换失败返还积分: userId={}, taskId={}, credits={}",
                task.getUserId(), task.getId(), deductedCredits);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String normalizeVideoContentType(String contentType, String filename) {
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
        return contentType;
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

    private String normalizeImageContentType(String contentType, String filename) {
        String lowerFilename = filename != null ? filename.toLowerCase() : "";
        if (lowerFilename.endsWith(".jpg") || lowerFilename.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lowerFilename.endsWith(".webp")) {
            return "image/webp";
        }
        if (lowerFilename.endsWith(".png")) {
            return "image/png";
        }
        return contentType;
    }

    private String resolveImageExtension(String contentType, String filename) {
        String lowerFilename = filename != null ? filename.toLowerCase() : "";
        if (lowerFilename.endsWith(".jpg") || lowerFilename.endsWith(".jpeg")) {
            return "jpg";
        }
        if (lowerFilename.endsWith(".webp")) {
            return "webp";
        }
        if ("image/jpeg".equals(contentType)) {
            return "jpg";
        }
        if ("image/webp".equals(contentType)) {
            return "webp";
        }
        return "png";
    }
}
