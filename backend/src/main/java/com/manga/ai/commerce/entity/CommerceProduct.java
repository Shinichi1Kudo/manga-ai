package com.manga.ai.commerce.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("commerce_product")
public class CommerceProduct implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long modelId;
    private Long seriesId;
    private Long userId;
    private String name;
    private String imageUrl;
    private String imagesJson;
    private String productType;
    private String sellingPoints;
    private String fabricFitColorSize;
    private String targetAudience;
    private String priceOffer;
    private String tryOnRequirements;
    private String status;

    @TableField(exist = false)
    private Integer projectDurationSeconds;

    @TableField(exist = false)
    private String projectResolution;

    @TableField(exist = false)
    private String projectVideoModel;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer isDeleted;
}
