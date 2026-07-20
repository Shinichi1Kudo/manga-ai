(function (root, factory) {
    const api = factory();
    if (typeof module === 'object' && module.exports) {
        module.exports = api;
    } else {
        root.PaymentCenterCore = api;
    }
}(typeof globalThis !== 'undefined' ? globalThis : window, function () {
    const UI = Object.freeze({
        IDLE: 'IDLE',
        CHOOSING: 'CHOOSING',
        CREATING: 'CREATING',
        CREATE_ERROR: 'CREATE_ERROR',
        QR_READY: 'QR_READY',
        QR_RENDER_ERROR: 'QR_RENDER_ERROR',
        REFRESHING_QR: 'REFRESHING_QR',
        QUERYING: 'QUERYING',
        PAID: 'PAID',
        EXPIRED: 'EXPIRED',
        PAGE_REDIRECTING: 'PAGE_REDIRECTING',
    });

    function isTrustedQrUrl(value) {
        if (typeof value !== 'string') {
            return false;
        }

        try {
            const url = new URL(value);
            return url.protocol === 'https:'
                && url.hostname === 'qr.alipay.com'
                && url.port === ''
                && !url.username
                && !url.password;
        } catch (error) {
            return false;
        }
    }

    async function renderQr(qrApi, canvas, text, options) {
        if (!qrApi || typeof qrApi.toCanvas !== 'function') {
            throw new Error('asset-missing');
        }
        if (!isTrustedQrUrl(text)) {
            throw new Error('untrusted-qr-url');
        }
        await qrApi.toCanvas(canvas, text, options);
    }

    function withRenderFailure(state, message) {
        return {...state, ui: UI.QR_RENDER_ERROR, renderError: message};
    }

    return {UI, isTrustedQrUrl, renderQr, withRenderFailure};
}));
