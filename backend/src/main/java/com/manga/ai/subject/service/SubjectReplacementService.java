package com.manga.ai.subject.service;

import com.manga.ai.subject.dto.SubjectReplacementCreateRequest;
import com.manga.ai.subject.dto.SubjectReplacementTaskVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 主体替换服务
 */
public interface SubjectReplacementService {

    /**
     * 创建任务并异步提交到火山
     */
    SubjectReplacementTaskVO createTask(SubjectReplacementCreateRequest request);

    /**
     * 执行主体替换任务
     */
    void executeTask(Long taskId);

    /**
     * 查询任务详情
     */
    SubjectReplacementTaskVO getTask(Long taskId);

    /**
     * 查询当前用户最近任务
     */
    List<SubjectReplacementTaskVO> listMyTasks(Integer limit);

    /**
     * 修改任务名称
     */
    SubjectReplacementTaskVO renameTask(Long taskId, String taskName);

    /**
     * 删除任务
     */
    void deleteTask(Long taskId);

    /**
     * 上传原视频
     */
    String uploadVideo(MultipartFile file);

    /**
     * 上传参考图
     */
    String uploadReferenceImage(MultipartFile file);
}
