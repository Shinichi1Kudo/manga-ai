const test = require('node:test');
const assert = require('node:assert/strict');
const core = require('./payment-center-core.js');

test('CommonJS import does not assign the browser global', () => {
    const modulePath = require.resolve('./payment-center-core.js');
    const previousGlobal = globalThis.PaymentCenterCore;

    try {
        delete globalThis.PaymentCenterCore;
        delete require.cache[modulePath];

        const imported = require(modulePath);

        assert.equal(imported.UI.IDLE, 'IDLE');
        assert.equal(
            Object.prototype.hasOwnProperty.call(globalThis, 'PaymentCenterCore'),
            false,
        );
    } finally {
        delete require.cache[modulePath];
        if (previousGlobal === undefined) {
            delete globalThis.PaymentCenterCore;
        } else {
            globalThis.PaymentCenterCore = previousGlobal;
        }
    }
});

test('renderQr calls qrcode 1.5.3 with canvas, text, options', async () => {
    const canvas = {id: 'paymentQrCanvas'};
    const options = {width: 232, margin: 1};
    let received;
    const api = {
        toCanvas: async (...args) => {
            received = args;
        },
    };

    await core.renderQr(api, canvas, 'https://qr.alipay.com/example', options);

    assert.deepEqual(received, [canvas, 'https://qr.alipay.com/example', options]);
});

test('isTrustedQrUrl accepts only the exact HTTPS qr.alipay.com host', () => {
    assert.equal(core.isTrustedQrUrl('https://qr.alipay.com/example'), true);
    assert.equal(core.isTrustedQrUrl('http://qr.alipay.com/example'), false);
    assert.equal(core.isTrustedQrUrl('https://qr.alipay.com.evil.example/x'), false);
    assert.equal(core.isTrustedQrUrl('https://qr.alipay.com@evil.example/x'), false);
    assert.equal(core.isTrustedQrUrl('https://user:pass@qr.alipay.com/x'), false);
    assert.equal(core.isTrustedQrUrl('https://qr.alipay.com:8443/x'), false);
    assert.equal(core.isTrustedQrUrl('not-a-url'), false);
});

test('renderQr rejects missing assets and untrusted URLs before rendering', async () => {
    await assert.rejects(
        core.renderQr(null, {}, 'https://qr.alipay.com/example', {}),
        {message: 'asset-missing'},
    );

    let called = false;
    const api = {
        toCanvas: async () => {
            called = true;
        },
    };
    await assert.rejects(
        core.renderQr(api, {}, 'https://qr.alipay.com.evil.example/x', {}),
        {message: 'untrusted-qr-url'},
    );
    assert.equal(called, false);
});

test('render failure does not change the business order status', () => {
    const original = {ui: 'QR_READY', orderStatus: 'CREATED', orderNo: 'PAY-1'};

    const state = core.withRenderFailure(original, 'asset-missing');

    assert.equal(state.ui, 'QR_RENDER_ERROR');
    assert.equal(state.orderStatus, 'CREATED');
    assert.equal(state.orderNo, 'PAY-1');
    assert.equal(state.renderError, 'asset-missing');
    assert.equal(original.ui, 'QR_READY');
});

test('exports a dedicated QR refresh UI state', () => {
    assert.equal(core.UI.REFRESHING_QR, 'REFRESHING_QR');
});
