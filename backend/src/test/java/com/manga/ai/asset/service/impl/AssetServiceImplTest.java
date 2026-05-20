package com.manga.ai.asset.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.manga.ai.asset.entity.AssetMetadata;
import com.manga.ai.asset.entity.RoleAsset;
import com.manga.ai.asset.mapper.AssetMetadataMapper;
import com.manga.ai.asset.mapper.RoleAssetMapper;
import com.manga.ai.common.enums.AssetStatus;
import com.manga.ai.common.service.OssService;
import com.manga.ai.role.entity.Role;
import com.manga.ai.role.mapper.RoleMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssetServiceImplTest {

    private static void initLambdaCache(Class<?> entityClass) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace(entityClass.getName());
        TableInfoHelper.initTableInfo(assistant, entityClass);
    }

    @Test
    void getClothingsBySeriesIdReturnsActiveAssetCreationSettingsFromMetadata() {
        initLambdaCache(Role.class);
        initLambdaCache(RoleAsset.class);
        initLambdaCache(AssetMetadata.class);
        RoleAssetMapper roleAssetMapper = mock(RoleAssetMapper.class);
        AssetMetadataMapper assetMetadataMapper = mock(AssetMetadataMapper.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        AssetServiceImpl service = new AssetServiceImpl(
                roleAssetMapper,
                assetMetadataMapper,
                roleMapper,
                mock(OssService.class)
        );

        Role role = new Role();
        role.setId(256L);
        role.setOriginalPrompt("旧角色提示词");
        role.setStyleKey("3d_anime");
        role.setStyleKeywords("old role style");

        RoleAsset asset = new RoleAsset();
        asset.setId(468L);
        asset.setRoleId(256L);
        asset.setClothingId(1);
        asset.setVersion(2);
        asset.setStatus(AssetStatus.PENDING_REVIEW.getCode());
        asset.setIsActive(1);
        asset.setFilePath("https://example.com/asset.png");

        AssetMetadata metadata = new AssetMetadata();
        metadata.setAssetId(468L);
        metadata.setUserPrompt("版本提示词");
        metadata.setAspectRatio("16:9");
        metadata.setQuality("hd");
        metadata.setStyleKey("chinese_style");
        metadata.setStyleKeywords("2D guofeng manhua character sheet");
        metadata.setDetailedView(false);
        metadata.setFaceCloseupView(true);

        when(roleMapper.selectList(any())).thenReturn(List.of(role));
        when(roleAssetMapper.selectList(any())).thenReturn(List.of(asset));
        when(assetMetadataMapper.selectList(any())).thenReturn(List.of(metadata));

        Map<Long, List<RoleAsset>> result = service.getClothingsBySeriesId(121L);

        RoleAsset returned = result.get(256L).get(0);
        assertThat(returned.getClothingPrompt()).isEqualTo("版本提示词");
        assertThat(returned.getAspectRatio()).isEqualTo("16:9");
        assertThat(returned.getQuality()).isEqualTo("hd");
        assertThat(returned.getStyleKey()).isEqualTo("chinese_style");
        assertThat(returned.getStyleKeywords()).isEqualTo("2D guofeng manhua character sheet");
        assertThat(returned.getDetailedView()).isFalse();
        assertThat(returned.getFaceCloseupView()).isTrue();
    }
}
