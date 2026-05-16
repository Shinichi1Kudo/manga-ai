package com.manga.ai.shot.service;

import com.manga.ai.shot.dto.ReferenceImageDTO;
import com.manga.ai.shot.dto.ShotDetailVO;
import com.manga.ai.shot.dto.ShotReviewRequest;
import com.manga.ai.shot.dto.ShotUpdateRequest;
import com.manga.ai.shot.dto.ShotVideoAssetVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 分镜服务接口
 */
public interface ShotService {

    /**
     * 获取分镜详情
     * @param shotId 分镜ID
     * @return 分镜详情
     */
    ShotDetailVO getShotDetail(Long shotId);

    /**
     * 获取剧集下的所有分镜
     * @param episodeId 剧集ID
     * @return 分镜列表
     */
    List<ShotDetailVO> getShotsByEpisodeId(Long episodeId);

    /**
     * 更新分镜
     * @param shotId 分镜ID
     * @param request 更新请求
     */
    void updateShot(Long shotId, ShotUpdateRequest request);

    /**
     * 审核分镜
     * @param shotId 分镜ID
     * @param request 审核请求
     */
    void reviewShot(Long shotId, ShotReviewRequest request);

    /**
     * 解锁分镜，恢复为待审核状态
     * @param shotId 分镜ID
     */
    void unlockShot(Long shotId);

    /**
     * 异步生成视频
     * @param shotId 分镜ID
     */
    void generateVideo(Long shotId);

    /**
     * 手动上传分镜视频
     * @param shotId 分镜ID
     * @param aspectRatio 视频比例
     * @param file 视频文件
     * @return 更新后的分镜详情
     */
    ShotDetailVO uploadVideo(Long shotId, String aspectRatio, MultipartFile file);

    /**
     * 批量生成视频
     * @param episodeId 剧集ID
     */
    void generateVideosForEpisode(Long episodeId);

    /**
     * 获取分镜参考图列表
     * @param shotId 分镜ID
     * @return 参考图列表
     */
    List<ReferenceImageDTO> getReferenceImages(Long shotId);

    /**
     * 更新分镜参考图列表
     * @param shotId 分镜ID
     * @param referenceImages 参考图列表
     */
    void updateReferenceImages(Long shotId, List<ReferenceImageDTO> referenceImages);

    /**
     * 自动匹配分镜文案中的资产
     * @param shotId 分镜ID
     * @return 匹配到的参考图列表
     */
    List<ReferenceImageDTO> matchAssetsFromDescription(Long shotId);

    /**
     * 带参考图生成视频
     * @param shotId 分镜ID
     * @param referenceUrls 参考图URL列表（前端传入）
     */
    void generateVideoWithReferences(Long shotId, java.util.List<String> referenceUrls);

    /**
     * 带参考图生成视频，并同步当前页面选择的参考图
     * @param shotId 分镜ID
     * @param referenceUrls 参考图URL列表（前端传入）
     * @param referenceImages 当前页面选择的参考图明细
     */
    void generateVideoWithReferences(Long shotId, java.util.List<String> referenceUrls, java.util.List<ReferenceImageDTO> referenceImages);

    /**
     * 带参考图生成视频，并在同一事务里保存分镜设置和生成开始时间
     * @param shotId 分镜ID
     * @param referenceUrls 参考图URL列表（前端传入）
     * @param referenceImages 当前页面选择的参考图明细
     * @param shotUpdate 当前页面分镜设置
     * @param generationStartTime 用户点击生成的时间
     */
    void generateVideoWithReferences(Long shotId, java.util.List<String> referenceUrls, java.util.List<ReferenceImageDTO> referenceImages,
                                     ShotUpdateRequest shotUpdate, java.time.LocalDateTime generationStartTime);

    /**
     * 准备带参考图的视频生成任务：同步保存分镜设置、参考图、扣费和生成中状态。
     * @param shotId 分镜ID
     * @param referenceUrls 参考图URL列表（前端传入）
     * @param referenceImages 当前页面选择的参考图明细
     * @param shotUpdate 当前页面分镜设置
     * @param generationStartTime 用户点击生成的时间
     * @return 已落库的分镜状态
     */
    ShotDetailVO prepareVideoGenerationWithReferences(Long shotId, java.util.List<String> referenceUrls, java.util.List<ReferenceImageDTO> referenceImages,
                                                      ShotUpdateRequest shotUpdate, java.time.LocalDateTime generationStartTime);

    /**
     * 启动已经准备好的带参考图视频生成任务。
     * @param shotId 分镜ID
     * @param referenceUrls 参考图URL列表（前端传入）
     */
    void startPreparedVideoGenerationWithReferences(Long shotId, java.util.List<String> referenceUrls);

    /**
     * 异步执行视频生成（内部方法）
     * @param shotId 分镜ID
     */
    void doGenerateVideo(Long shotId);

    /**
     * 异步执行带参考图的视频生成（内部方法）
     * @param shotId 分镜ID
     * @param referenceUrls 参考图URL列表
     */
    void doGenerateVideoWithReferences(Long shotId, java.util.List<String> referenceUrls);

    /**
     * 获取分镜视频版本历史
     * @param shotId 分镜ID
     * @return 视频版本列表
     */
    List<ShotVideoAssetVO> getVideoHistory(Long shotId);

    /**
     * 回滚到指定视频版本
     * @param shotId 分镜ID
     * @param assetId 视频资产ID
     */
    ShotDetailVO rollbackToVersion(Long shotId, Long assetId);

    /**
     * 创建分镜
     * @param episodeId 剧集ID
     * @param request 分镜数据（可选）
     * @return 新创建的分镜详情
     */
    ShotDetailVO createShot(Long episodeId, ShotUpdateRequest request);

    /**
     * 删除分镜
     * @param shotId 分镜ID
     */
    void deleteShot(Long shotId);

    /**
     * 重新排序分镜
     * @param episodeId 剧集ID
     * @param shotIds 分镜ID列表（按新顺序）
     * @param reviewStatus 分镜审核状态，待审核和已锁定列表分别排序
     */
    void reorderShots(Long episodeId, List<Long> shotIds, Integer reviewStatus);

    /**
     * 获取视频生成积分预览
     * @param shotId 分镜ID
     * @return 积分预览信息
     */
    Map<String, Object> getVideoCreditPreview(Long shotId);
}
