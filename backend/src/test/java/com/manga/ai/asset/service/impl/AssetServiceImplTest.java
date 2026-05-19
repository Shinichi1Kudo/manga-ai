package com.manga.ai.asset.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.manga.ai.asset.entity.AssetMetadata;
import com.manga.ai.asset.entity.RoleAsset;
import com.manga.ai.asset.mapper.AssetMetadataMapper;
import com.manga.ai.asset.mapper.RoleAssetMapper;
import com.manga.ai.common.service.OssService;
import com.manga.ai.role.entity.Role;
import com.manga.ai.role.mapper.RoleMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssetServiceImplTest {

    static {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AssetMetadata.class);
    }

    @Test
    void getDefaultAssetPromptUsesRoleOriginalPromptInsteadOfSystemPromptMetadata() {
        RoleAssetMapper roleAssetMapper = mock(RoleAssetMapper.class);
        AssetMetadataMapper assetMetadataMapper = mock(AssetMetadataMapper.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        AssetServiceImpl service = new AssetServiceImpl(
                roleAssetMapper,
                assetMetadataMapper,
                roleMapper,
                mock(OssService.class)
        );

        RoleAsset asset = new RoleAsset();
        asset.setId(88L);
        asset.setRoleId(44L);
        asset.setClothingId(1);
        when(roleAssetMapper.selectById(88L)).thenReturn(asset);

        Role role = new Role();
        role.setId(44L);
        role.setOriginalPrompt("惊艳东方美女");
        role.setCustomPrompt("惊艳东方美女\n\n人物三视图系统模板");
        when(roleMapper.selectById(44L)).thenReturn(role);

        AssetMetadata metadata = new AssetMetadata();
        metadata.setUserPrompt("惊艳东方美女\n\n人物三视图系统模板");
        metadata.setPrompt("完整系统生成提示词");
        when(assetMetadataMapper.selectOne(any())).thenReturn(metadata);

        String prompt = service.getAssetPrompt(88L);

        assertThat(prompt).isEqualTo("惊艳东方美女");
    }
}
