package com.manga.ai.common.utils;

import com.manga.ai.common.enums.ViewType;

/**
 * 文件命名工具
 */
public class NamingUtil {

    /**
     * 清理文件名中的特殊字符
     */
    public static String sanitizeFileName(String name) {
        if (name == null) {
            return "unknown";
        }
        // 替换特殊字符为下划线
        return name.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
    }

    /**
     * 生成角色编码
     * 格式: ROLE_系列ID_角色名拼音首字母
     */
    public static String generateRoleCode(String roleName, Long seriesId) {
        if (roleName == null || roleName.isEmpty()) {
            return "ROLE_" + seriesId + "_UNK";
        }

        // 获取角色名首字母
        String initials = getInitials(roleName);

        return String.format("ROLE_%d_%s", seriesId, initials.toUpperCase());
    }

    /**
     * 获取字符串首字母（支持中文）
     */
    private static String getInitials(String str) {
        if (str == null || str.isEmpty()) {
            return "X";
        }

        StringBuilder initials = new StringBuilder();
        for (int i = 0; i < Math.min(str.length(), 3); i++) {
            char c = str.charAt(i);
            if (Character.isLetter(c)) {
                initials.append(Character.toUpperCase(c));
            }
        }

        if (initials.length() == 0) {
            initials.append("X");
        }

        return initials.toString();
    }

    /**
     * 生成资产文件名
     * 角色名_视图类型_服装编号_版本_透明.png
     * 示例: 张小明_正面_01_v1.png / 张小明_正面_01_v1_transparent.png
     */
    public static String generateAssetFileName(String roleName, ViewType viewType,
                                               int clothingId, int version, boolean transparent) {
        String sanitized = sanitizeFileName(roleName);
        String transparentSuffix = transparent ? "_透明" : "";
        return String.format("%s_%s_C%02d_V%02d%s.png",
                sanitized,
                viewType.getShortName(),
                clothingId,
                version,
                transparentSuffix
        );
    }

    /**
     * 生成缩略图文件名
     */
    public static String generateThumbnailFileName(String originalFileName) {
        if (originalFileName == null) {
            return "thumb_unknown.png";
        }
        int dotIndex = originalFileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return "thumb_" + originalFileName.substring(0, dotIndex) + ".png";
        }
        return "thumb_" + originalFileName + ".png";
    }
}
