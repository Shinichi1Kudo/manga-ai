const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const core = require('./payment-center-core.js');

function createFakeTimers(start) {
    let now = start;
    let nextId = 1;
    const timers = new Map();

    return {
        now: () => now,
        setInterval(callback, delay) {
            const id = nextId++;
            timers.set(id, {callback, delay, nextRun: now + delay});
            return id;
        },
        clearInterval(id) {
            timers.delete(id);
        },
        advance(milliseconds) {
            const target = now + milliseconds;
            while (true) {
                const due = Array.from(timers.entries())
                    .filter(([, timer]) => timer.nextRun <= target)
                    .sort((left, right) => left[1].nextRun - right[1].nextRun)[0];
                if (!due) break;
                const [id, timer] = due;
                now = timer.nextRun;
                if (timers.has(id)) timer.nextRun += timer.delay;
                timer.callback();
            }
            now = target;
        },
        activeDelays() {
            return Array.from(timers.values()).map(timer => timer.delay).sort();
        },
    };
}

function loadPaymentCenter(timers) {
    const source = fs.readFileSync(path.join(__dirname, 'payment-center.js'), 'utf8');
    const bootstrapStart = source.lastIndexOf("    document.addEventListener('DOMContentLoaded'");
    assert.notEqual(bootstrapStart, -1, 'payment center bootstrap must remain discoverable');

    class FakeDate extends Date {}
    FakeDate.now = timers.now;
    const context = {
        PaymentCenterCore: core,
        URL,
        URLSearchParams,
        Date: FakeDate,
        document: {hidden: false},
        setInterval: timers.setInterval,
        clearInterval: timers.clearInterval,
    };
    context.window = context;
    vm.createContext(context);
    vm.runInContext(
        `${source.slice(0, bootstrapStart)}    globalThis.__PaymentCenter = PaymentCenter;\n}());`,
        context,
    );
    return {PaymentCenter: context.__PaymentCenter, context};
}

function fakeElement(initial = {}) {
    const nested = new Map();
    return {
        hidden: true,
        disabled: false,
        textContent: '',
        parentElement: null,
        querySelector(selector) {
            if (!nested.has(selector)) nested.set(selector, fakeElement({hidden: false}));
            return nested.get(selector);
        },
        ...initial,
    };
}

function createCenter(PaymentCenter, ui, order) {
    const qrCanvas = fakeElement({
        width: 232,
        height: 232,
        getContext: () => ({clearRect() {}}),
    });
    qrCanvas.parentElement = fakeElement();
    const elements = {
        close: fakeElement(),
        currentTab: fakeElement(),
        pendingTab: fakeElement(),
        currentEmpty: fakeElement(),
        summary: fakeElement(),
        product: fakeElement(),
        amount: fakeElement(),
        methods: fakeElement(),
        methodQr: fakeElement(),
        methodPage: fakeElement(),
        creating: fakeElement(),
        createError: fakeElement(),
        qrView: fakeElement(),
        qrCanvas,
        qrStatus: fakeElement(),
        qrActions: fakeElement(),
        qrCountdown: fakeElement(),
        qrOrderNo: fakeElement(),
        qrSync: fakeElement(),
        qrRefresh: fakeElement(),
        qrRetryRender: fakeElement(),
        qrOpenLink: fakeElement(),
        qrCashier: fakeElement(),
    };
    const center = Object.create(PaymentCenter.prototype);
    Object.assign(center, {
        root: {hidden: false},
        state: {ui, orderStatus: order && order.status, error: '', renderError: ''},
        currentPurchase: null,
        currentOrder: order,
        refreshInFlight: false,
        syncInFlight: false,
        switchInFlight: false,
        resumeInFlight: new Set(),
        pollTimer: null,
        countdownTimer: null,
        expirySyncRequested: null,
        renderSequence: 0,
        elements,
        updatePendingActionState() {},
    });
    return center;
}

function qrOrder(overrides = {}) {
    return {
        orderNo: 'PAY-COUNTDOWN-1',
        productName: '积分充值',
        amountCents: 10000,
        paymentMethod: 'QR',
        paymentReady: true,
        qrCode: 'https://qr.alipay.com/countdown',
        status: 'CREATED',
        expiresAt: '2026-08-02T01:01:01.000Z',
        ...overrides,
    };
}

test('verified QR renders authoritative HH:MM:SS countdown and keeps query controls hidden', () => {
    const timers = createFakeTimers(Date.parse('2026-08-02T00:00:00.000Z'));
    const {PaymentCenter} = loadPaymentCenter(timers);
    const center = createCenter(PaymentCenter, core.UI.QR_READY, qrOrder());

    center.render();

    assert.equal(center.elements.qrCanvas.parentElement.hidden, false);
    assert.equal(center.elements.qrCountdown.hidden, false);
    assert.equal(center.elements.qrCountdown.textContent, '二维码有效期 01:01:01');
    assert.equal(center.elements.qrOrderNo.textContent, 'PAY-COUNTDOWN-1');
    assert.equal(center.elements.qrStatus.hidden, true);
    assert.equal(center.elements.qrActions.hidden, true);
    for (const action of ['qrSync', 'qrRefresh', 'qrRetryRender', 'qrOpenLink', 'qrCashier']) {
        assert.equal(center.elements[action].hidden, true, action);
    }
});

test('expired countdown stops all timers and requests exactly one safe sync', () => {
    const timers = createFakeTimers(Date.parse('2026-08-02T00:00:00.000Z'));
    const {PaymentCenter} = loadPaymentCenter(timers);
    const center = createCenter(PaymentCenter, core.UI.QR_READY, qrOrder({
        expiresAt: '2026-08-02T00:00:01.000Z',
    }));
    let syncCount = 0;
    center.syncCurrentOrder = () => { syncCount += 1; };

    center.startPolling();
    timers.advance(1000);
    timers.advance(10000);
    center.updateCountdown();
    center.startPolling();
    timers.advance(10000);

    assert.equal(syncCount, 1);
    assert.deepEqual(timers.activeDelays(), []);
    assert.equal(center.countdownTimer, null);
});

test('unready QR preserves order and exposes only the official cashier fallback', () => {
    const timers = createFakeTimers(Date.parse('2026-08-02T00:00:00.000Z'));
    const {PaymentCenter} = loadPaymentCenter(timers);
    const center = createCenter(PaymentCenter, core.UI.CREATE_ERROR, qrOrder({
        paymentReady: false,
        qrCode: null,
        paymentMessage: '订单状态暂不确定，请勿重复支付',
    }));

    center.render();

    assert.equal(center.elements.qrCanvas.parentElement.hidden, true);
    assert.equal(center.elements.qrCountdown.hidden, true);
    assert.equal(center.elements.qrStatus.hidden, true);
    assert.equal(center.elements.methods.hidden, true);
    assert.equal(center.elements.qrActions.hidden, false);
    assert.equal(center.elements.qrCashier.hidden, false);
    for (const action of ['qrSync', 'qrRefresh', 'qrRetryRender', 'qrOpenLink']) {
        assert.equal(center.elements[action].hidden, true, action);
    }
    assert.equal(center.elements.qrOrderNo.textContent, 'PAY-COUNTDOWN-1');
});

test('QR without an authoritative expiry is not displayed and uses the safe cashier fallback', () => {
    const timers = createFakeTimers(Date.parse('2026-08-02T00:00:00.000Z'));
    const {PaymentCenter} = loadPaymentCenter(timers);
    const center = createCenter(PaymentCenter, core.UI.QR_READY, qrOrder({expiresAt: null}));

    center.render();

    assert.equal(center.elements.qrCanvas.parentElement.hidden, true);
    assert.equal(center.elements.qrCountdown.hidden, true);
    assert.equal(center.elements.methods.hidden, true);
    assert.equal(center.elements.qrActions.hidden, false);
    assert.equal(center.elements.qrCashier.hidden, false);
});

test('paid and closed orders clear timers, hide QR, and retain the order number', async () => {
    const timers = createFakeTimers(Date.parse('2026-08-02T00:00:00.000Z'));
    const {PaymentCenter} = loadPaymentCenter(timers);
    const paidCenter = createCenter(PaymentCenter, core.UI.QR_READY, qrOrder());
    paidCenter.pollTimer = timers.setInterval(() => {}, 5000);
    paidCenter.countdownTimer = timers.setInterval(() => {}, 1000);
    paidCenter.loadPending = async () => {};

    await paidCenter.completePaid(qrOrder({status: 'PAID'}));

    assert.deepEqual(timers.activeDelays(), []);
    assert.equal(paidCenter.elements.qrCanvas.parentElement.hidden, true);
    assert.equal(paidCenter.elements.qrOrderNo.textContent, 'PAY-COUNTDOWN-1');
    assert.ok(
        paidCenter.elements.qrCountdown.hidden
            || paidCenter.elements.qrCountdown.textContent === '支付已完成',
    );

    const closedCenter = createCenter(PaymentCenter, core.UI.QR_READY, qrOrder({status: 'CLOSED'}));
    closedCenter.pollTimer = timers.setInterval(() => {}, 5000);
    closedCenter.countdownTimer = timers.setInterval(() => {}, 1000);
    closedCenter.loadPending = async () => {};
    await closedCenter.handleOrderResponse(closedCenter.currentOrder);

    assert.deepEqual(timers.activeDelays(), []);
    assert.equal(closedCenter.elements.qrCanvas.parentElement.hidden, true);
    assert.equal(closedCenter.elements.qrOrderNo.textContent, 'PAY-COUNTDOWN-1');
    assert.ok(
        closedCenter.elements.qrCountdown.hidden
            || closedCenter.elements.qrCountdown.textContent === '订单已过期',
    );
});
