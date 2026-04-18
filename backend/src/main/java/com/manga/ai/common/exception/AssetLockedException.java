package com.manga.ai.common.exception;

/**
 * 资产锁定异常
 */
public class AssetLockedException extends BusinessException {

    public AssetLockedException(String message) {
        super(400, message);
    }

    public AssetLockedException() {
        super(400, "资产已锁定，无法修改");
    }
}
