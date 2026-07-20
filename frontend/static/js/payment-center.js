(function () {
    'use strict';

    const PAYMENT_HOSTS = new Set([
        'openapi.alipay.com',
        'openapi-sandbox.dl.alipaydev.com',
    ]);
    const FOCUSABLE_SELECTOR = [
        'a[href]',
        'button:not([disabled])',
        'input:not([disabled])',
        'select:not([disabled])',
        'textarea:not([disabled])',
        '[tabindex]:not([tabindex="-1"])',
    ].join(',');

    function getCookie(name) {
        const prefix = `${name}=`;
        return document.cookie.split(';').map(value => value.trim()).reduce((result, cookie) => {
            if (result !== null || !cookie.startsWith(prefix)) return result;
            return decodeURIComponent(cookie.slice(prefix.length));
        }, null);
    }

    function formatCurrency(amountCents) {
        const cents = Number(amountCents);
        const amount = Number.isFinite(cents) ? Math.max(0, cents) / 100 : 0;
        return `¥${amount.toLocaleString('zh-CN', {
            minimumFractionDigits: 2,
            maximumFractionDigits: 2,
        })}`;
    }

    function formatExpiry(expiresAt) {
        const date = new Date(expiresAt);
        if (!Number.isFinite(date.getTime())) return '有效期以支付宝页面为准';
        return `有效至 ${date.toLocaleString('zh-CN', {
            month: '2-digit',
            day: '2-digit',
            hour: '2-digit',
            minute: '2-digit',
            hour12: false,
        })}`;
    }

    function appendText(parent, tagName, text, className) {
        const element = document.createElement(tagName);
        if (className) element.className = className;
        element.textContent = text;
        parent.appendChild(element);
        return element;
    }

    class PaymentCenter {
        constructor(root, options = {}) {
            this.root = root;
            this.options = options;
            this.overlay = document.getElementById('paymentCenterOverlay');
            this.state = {
                ui: PaymentCenterCore.UI.IDLE,
                orderStatus: null,
                error: '',
                renderError: '',
            };
            this.currentPurchase = null;
            this.currentOrder = null;
            this.pendingOrders = new Map();
            this.resumeInFlight = new Set();
            this.resumeButtons = new Map();
            this.pollTimer = null;
            this.countdownTimer = null;
            this.syncInFlight = false;
            this.switchInFlight = false;
            this.resumePollingOnVisibility = false;
            this.expirySyncRequested = null;
            this.focusReturn = null;
            this.renderSequence = 0;
            this.operationSequence = 0;
            this.elements = this.collectElements();
            this.bind();
            this.render();
        }

        collectElements() {
            const byId = id => document.getElementById(id);
            return {
                close: byId('paymentCenterClose'),
                currentTab: byId('paymentCenterCurrentTab'),
                pendingTab: byId('paymentCenterPendingTab'),
                pendingCount: byId('paymentCenterPendingCount'),
                currentView: byId('paymentCenterCurrentView'),
                pendingView: byId('paymentCenterPendingView'),
                currentEmpty: byId('paymentCurrentEmpty'),
                summary: byId('paymentCurrentProductSummary'),
                product: byId('paymentCurrentProduct'),
                amount: byId('paymentCurrentAmount'),
                methods: byId('paymentMethodSelection'),
                methodQr: byId('paymentMethodQr'),
                methodPage: byId('paymentMethodPage'),
                creating: byId('paymentCreatingState'),
                createError: byId('paymentCreateError'),
                qrView: byId('paymentQrView'),
                qrCanvas: byId('paymentQrCanvas'),
                qrStatus: byId('paymentQrStatus'),
                qrCountdown: byId('paymentQrCountdown'),
                qrOrderNo: byId('paymentQrOrderNo'),
                qrSync: byId('paymentQrSync'),
                qrRetryRender: byId('paymentQrRetryRender'),
                qrOpenLink: byId('paymentQrOpenLink'),
                qrCashier: byId('paymentQrCashier'),
                pendingState: byId('paymentPendingState'),
                pendingList: byId('paymentPendingList'),
            };
        }

        bind() {
            this.elements.close.addEventListener('click', () => this.close());
            this.overlay.addEventListener('click', () => this.close());
            this.elements.currentTab.addEventListener('click', () => this.showCurrent());
            this.elements.pendingTab.addEventListener('click', () => this.showPending());
            this.elements.methodQr.addEventListener('click', () => this.chooseMethod('QR'));
            this.elements.methodPage.addEventListener('click', () => this.chooseMethod('PAGE'));
            this.elements.qrSync.addEventListener('click', () => this.syncCurrentOrder(true));
            this.elements.qrRetryRender.addEventListener('click', () => {
                if (this.currentOrder) this.showQrOrder(this.currentOrder);
            });
            this.elements.qrOpenLink.addEventListener('click', () => this.openCurrentQrLink());
            this.elements.qrCashier.addEventListener('click', () => this.switchCurrentQrToPage());
            this.root.addEventListener('keydown', event => this.handleKeydown(event));

            document.addEventListener('click', event => this.handleDocumentClick(event));
            document.addEventListener('visibilitychange', () => this.handleVisibilityChange());
        }

        handleDocumentClick(event) {
            const purchaseButton = event.target.closest('[data-payment-purchase]');
            if (purchaseButton) {
                event.preventDefault();
                this.openPurchase({
                    orderType: purchaseButton.dataset.paymentOrderType,
                    productCode: purchaseButton.dataset.paymentProductCode || '',
                    productName: purchaseButton.dataset.paymentProductName || '支付宝支付',
                    amountCents: Number(purchaseButton.dataset.paymentAmountCents || 0),
                }, purchaseButton);
                return;
            }

            const pendingButton = event.target.closest('#pendingPaymentButton');
            if (!pendingButton || window.location.pathname !== '/membership/') return;
            event.preventDefault();
            if (typeof window.closeMobileNav === 'function') window.closeMobileNav();
            this.openFromEntry(pendingButton);
        }

        ensureAuthenticated() {
            if (typeof IS_AUTHENTICATED === 'undefined' || IS_AUTHENTICATED) return true;
            window.location.href = '/auth/login/';
            return false;
        }

        transition(ui, patch = {}) {
            this.state = {...this.state, ...patch, ui};
            this.render();
        }

        setCurrentOrder(order) {
            const currentOrderNo = this.currentOrder && this.currentOrder.orderNo;
            const nextOrderNo = order && order.orderNo;
            if (!order || currentOrderNo !== nextOrderNo) {
                this.expirySyncRequested = null;
            }
            this.currentOrder = order;
        }

        open(trigger = null) {
            if (this.root.hidden) {
                this.focusReturn = trigger || document.activeElement;
            }
            this.root.hidden = false;
            this.root.removeAttribute('inert');
            this.root.setAttribute('aria-hidden', 'false');
            this.overlay.hidden = false;
            this.overlay.setAttribute('aria-hidden', 'false');
            this.overlay.classList.add('is-open');
            document.body.classList.add('payment-center-open');
            window.requestAnimationFrame(() => this.elements.close.focus());
        }

        close() {
            if (this.root.hidden) return;
            this.operationSequence += 1;
            this.stopTimers();
            this.renderSequence += 1;
            this.root.setAttribute('aria-hidden', 'true');
            this.root.setAttribute('inert', '');
            this.root.hidden = true;
            this.overlay.setAttribute('aria-hidden', 'true');
            this.overlay.classList.remove('is-open');
            this.overlay.hidden = true;
            document.body.classList.remove('payment-center-open');
            if (this.focusReturn && typeof this.focusReturn.focus === 'function') {
                window.requestAnimationFrame(() => this.focusReturn.focus());
            }
        }

        handleKeydown(event) {
            if (event.key === 'Escape') {
                event.preventDefault();
                this.close();
                return;
            }
            if (event.key !== 'Tab') return;

            const focusable = Array.from(this.root.querySelectorAll(FOCUSABLE_SELECTOR))
                .filter(element => !element.closest('[hidden], [inert], [aria-hidden="true"]')
                    && element.getClientRects().length > 0);
            if (!focusable.length) {
                event.preventDefault();
                this.root.focus();
                return;
            }
            const first = focusable[0];
            const last = focusable[focusable.length - 1];
            if (event.shiftKey && document.activeElement === first) {
                event.preventDefault();
                last.focus();
            } else if (!event.shiftKey && document.activeElement === last) {
                event.preventDefault();
                first.focus();
            }
        }

        openFromEntry(trigger = null) {
            if (!this.ensureAuthenticated()) return;
            if (this.currentOrder && this.currentOrder.status === 'CREATED') {
                this.open(trigger);
                this.showCurrent();
                this.restoreCurrentQrLifecycle();
                this.syncCurrentOrder(false);
                return;
            }
            this.openPending(trigger);
        }

        openPurchase(purchase, trigger) {
            if (!this.ensureAuthenticated()) return;
            this.operationSequence += 1;
            this.renderSequence += 1;
            this.stopTimers();
            this.currentPurchase = {
                orderType: purchase.orderType === 'MEMBERSHIP' ? 'MEMBERSHIP' : 'RECHARGE',
                productCode: String(purchase.productCode || ''),
                productName: String(purchase.productName || '支付宝支付'),
                amountCents: Number.isFinite(purchase.amountCents) ? purchase.amountCents : 0,
            };
            this.setCurrentOrder(null);
            this.open(trigger);
            this.clearVisibleQrCanvas();
            this.showCurrent();
            this.transition(PaymentCenterCore.UI.CHOOSING, {
                orderStatus: null,
                error: '',
                renderError: '',
            });
        }

        openPending(trigger = null) {
            if (!this.ensureAuthenticated()) return;
            this.open(trigger);
            this.showPending();
        }

        showCurrent() {
            this.elements.currentTab.setAttribute('aria-selected', 'true');
            this.elements.pendingTab.setAttribute('aria-selected', 'false');
            this.elements.currentView.hidden = false;
            this.elements.pendingView.hidden = true;
        }

        showPending() {
            this.elements.currentTab.setAttribute('aria-selected', 'false');
            this.elements.pendingTab.setAttribute('aria-selected', 'true');
            this.elements.currentView.hidden = true;
            this.elements.pendingView.hidden = false;
            this.loadPending();
        }

        async chooseMethod(method) {
            if (!this.currentPurchase && !this.currentOrder) return;
            if (this.currentOrder) {
                if (this.currentOrder.paymentMethod === 'QR' && method === 'PAGE') {
                    await this.switchCurrentQrToPage();
                    return;
                }
                if (this.currentOrder.paymentMethod === method) {
                    await this.resume(this.currentOrder.orderNo);
                }
                return;
            }

            const operation = ++this.operationSequence;
            this.transition(PaymentCenterCore.UI.CREATING, {error: '', renderError: ''});
            const endpoint = this.currentPurchase.orderType === 'MEMBERSHIP'
                ? '/api/payments/membership/orders/'
                : '/api/payments/recharge/orders/';
            const payload = this.currentPurchase.orderType === 'MEMBERSHIP'
                ? {productCode: this.currentPurchase.productCode, paymentMethod: method}
                : {amountCents: this.currentPurchase.amountCents, paymentMethod: method};

            try {
                const responseOrder = await this.postJson(endpoint, payload);
                if (operation !== this.operationSequence || this.root.hidden) return;
                const order = this.normalizeOrder(responseOrder, {
                    ...this.currentPurchase,
                    paymentMethod: method,
                });
                this.setCurrentOrder(order);
                await this.handleOrderResponse(order);
            } catch (error) {
                if (operation !== this.operationSequence || this.root.hidden) return;
                this.transition(PaymentCenterCore.UI.CREATE_ERROR, {
                    error: error.message || '支付订单创建失败，请稍后重试',
                });
            }
        }

        async handleOrderResponse(order) {
            if (order.status === 'PAID') {
                await this.completePaid(order);
                return;
            }
            if (order.status === 'CLOSED') {
                this.stopTimers();
                this.invalidateQrDisplay();
                this.transition(PaymentCenterCore.UI.EXPIRED, {
                    orderStatus: 'CLOSED',
                    error: order.paymentMessage || '',
                });
                await this.loadPending();
                return;
            }
            if (order.paymentMethod === 'QR') {
                if (order.paymentReady && order.qrCode) {
                    await this.showQrOrder(order);
                } else {
                    this.transition(PaymentCenterCore.UI.CREATE_ERROR, {
                        orderStatus: order.status,
                        error: order.paymentMessage || '二维码暂未生成，请重试预下单或切换支付宝收银台',
                    });
                    this.startCountdownOnly();
                }
                return;
            }
            if (order.paymentMethod === 'PAGE' && order.paymentReady && order.paymentHtml) {
                this.transition(PaymentCenterCore.UI.PAGE_REDIRECTING, {
                    orderStatus: order.status,
                    error: '',
                });
                this.submitAlipayForm(order.paymentHtml);
                return;
            }
            this.transition(PaymentCenterCore.UI.CREATE_ERROR, {
                orderStatus: order.status,
                error: order.paymentMessage || '支付宝收银台暂不可用，请稍后重试',
            });
        }

        clearVisibleQrCanvas() {
            const canvas = this.elements.qrCanvas;
            const context = canvas && typeof canvas.getContext === 'function'
                ? canvas.getContext('2d')
                : null;
            if (context) context.clearRect(0, 0, canvas.width, canvas.height);
        }

        invalidateQrDisplay() {
            this.renderSequence += 1;
            this.clearVisibleQrCanvas();
        }

        async showQrOrder(order) {
            this.setCurrentOrder(this.normalizeOrder(order, this.currentOrder || this.currentPurchase));
            const canvas = this.elements.qrCanvas;
            const sequence = ++this.renderSequence;
            if (!this.root.hidden) this.clearVisibleQrCanvas();
            const offscreenCanvas = document.createElement('canvas');
            offscreenCanvas.width = canvas.width;
            offscreenCanvas.height = canvas.height;
            this.transition(PaymentCenterCore.UI.QR_READY, {
                orderStatus: this.currentOrder.status || 'CREATED',
                error: '',
                renderError: '',
            });
            try {
                await PaymentCenterCore.renderQr(window.QRCode, offscreenCanvas, order.qrCode, {
                    width: 232,
                    margin: 1,
                    errorCorrectionLevel: 'M',
                    color: {dark: '#101114', light: '#ffffff'},
                });
                if (sequence !== this.renderSequence || this.root.hidden) return;
                const context = canvas.getContext('2d');
                if (!context) throw new Error('canvas-context-missing');
                context.clearRect(0, 0, canvas.width, canvas.height);
                context.drawImage(offscreenCanvas, 0, 0);
                this.startPolling();
            } catch (error) {
                if (sequence !== this.renderSequence || this.root.hidden) return;
                this.clearVisibleQrCanvas();
                this.state = PaymentCenterCore.withRenderFailure(this.state, error.message);
                this.render();
                this.startCountdownOnly();
            }
        }

        renderPending(orders) {
            this.pendingOrders.clear();
            this.resumeButtons.clear();
            this.elements.pendingList.replaceChildren();
            this.updatePendingCounts(orders.length);
            this.elements.pendingState.hidden = orders.length > 0;
            this.elements.pendingState.textContent = orders.length ? '' : '暂无待支付订单';

            orders.forEach(rawOrder => {
                const order = this.normalizeOrder(rawOrder);
                this.pendingOrders.set(order.orderNo, order);
                const item = document.createElement('article');
                item.className = 'payment-pending-item';

                appendText(item, 'strong', order.productName || '支付宝订单');
                const summary = document.createElement('div');
                summary.className = 'payment-center-summary';
                appendText(summary, 'span', order.paymentMethod === 'QR' ? '支付宝扫码支付' : '支付宝收银台');
                appendText(summary, 'strong', formatCurrency(order.amountCents));
                item.appendChild(summary);

                const readiness = order.paymentMethod === 'QR' && !order.paymentReady
                    ? '二维码待生成'
                    : '可继续支付';
                appendText(item, 'p', `${readiness} · ${formatExpiry(order.expiresAt)}`, 'payment-center-countdown');

                const button = document.createElement('button');
                button.type = 'button';
                button.className = 'payment-center-action payment-center-action--primary';
                button.textContent = '继续支付';
                button.disabled = this.resumeInFlight.has(order.orderNo);
                button.addEventListener('click', () => this.resume(order.orderNo));
                this.resumeButtons.set(order.orderNo, button);
                item.appendChild(button);
                this.elements.pendingList.appendChild(item);
            });
        }

        async loadPending() {
            this.elements.pendingState.hidden = false;
            this.elements.pendingState.textContent = '正在加载待支付订单';
            try {
                const response = await fetch('/api/payments/orders/pending/');
                const orders = await this.readResponse(response);
                this.renderPending(Array.isArray(orders) ? orders : []);
            } catch (error) {
                this.elements.pendingState.hidden = false;
                this.elements.pendingState.textContent = error.message || '待支付订单加载失败，请稍后重试';
            }
        }

        async resume(orderNo) {
            if (this.resumeInFlight.has(orderNo)) return;
            const pendingOrder = this.pendingOrders.get(orderNo);
            if (pendingOrder) {
                this.setCurrentOrder(pendingOrder);
                this.currentPurchase = null;
            }
            if (!this.currentOrder || this.currentOrder.orderNo !== orderNo) return;
            this.resumeInFlight.add(orderNo);
            let button = this.resumeButtons.get(orderNo);
            if (button) button.disabled = true;
            this.stopPolling();
            const operation = ++this.operationSequence;
            this.showCurrent();
            this.transition(PaymentCenterCore.UI.QUERYING, {error: '', renderError: ''});
            try {
                const responseOrder = await this.postJson(`/api/payments/orders/${encodeURIComponent(orderNo)}/resume/`);
                if (operation !== this.operationSequence || this.root.hidden) return;
                const order = this.normalizeOrder(responseOrder, this.currentOrder);
                this.setCurrentOrder(order);
                await this.handleOrderResponse(order);
            } catch (error) {
                if (operation !== this.operationSequence || this.root.hidden) return;
                this.transition(PaymentCenterCore.UI.CREATE_ERROR, {
                    orderStatus: this.currentOrder.status,
                    error: error.message || '订单恢复失败，请稍后重试',
                });
                this.restoreCurrentQrLifecycle();
            } finally {
                this.resumeInFlight.delete(orderNo);
                button = this.resumeButtons.get(orderNo);
                if (button) button.disabled = false;
            }
        }

        async switchCurrentQrToPage() {
            if (!this.currentOrder || this.currentOrder.paymentMethod !== 'QR' || this.switchInFlight) return;
            this.stopPolling();
            const operation = ++this.operationSequence;
            this.switchInFlight = true;
            this.transition(PaymentCenterCore.UI.QUERYING, {
                error: '',
                renderError: '',
            });
            try {
                const responseOrder = await this.postJson(`/api/payments/orders/${encodeURIComponent(this.currentOrder.orderNo)}/switch-to-page/`);
                if (operation !== this.operationSequence || this.root.hidden) return;
                const order = this.normalizeOrder(responseOrder, this.currentOrder);
                this.setCurrentOrder(order);
                await this.handleOrderResponse(order);
            } catch (error) {
                if (operation !== this.operationSequence || this.root.hidden) return;
                this.transition(PaymentCenterCore.UI.CREATE_ERROR, {
                    orderStatus: this.currentOrder.status,
                    error: error.message || '原订单状态暂不确定，请勿重复支付并稍后查询',
                });
                this.restoreCurrentQrLifecycle();
            } finally {
                this.switchInFlight = false;
                this.render();
            }
        }

        async syncCurrentOrder(manual = false) {
            if (!this.currentOrder || this.currentOrder.status !== 'CREATED'
                    || this.syncInFlight || this.switchInFlight
                    || this.resumeInFlight.has(this.currentOrder.orderNo)) return;
            const orderNo = this.currentOrder.orderNo;
            const previousUi = this.state.ui;
            const operation = this.operationSequence;
            this.syncInFlight = true;
            this.transition(PaymentCenterCore.UI.QUERYING, {error: ''});
            try {
                const responseOrder = await this.postJson(`/api/payments/orders/${encodeURIComponent(orderNo)}/sync/`);
                if (operation !== this.operationSequence || this.root.hidden) return;
                if (!this.currentOrder || this.currentOrder.orderNo !== orderNo) return;
                const order = this.normalizeOrder(responseOrder, this.currentOrder);
                this.setCurrentOrder(order);
                if (order.status === 'PAID') {
                    await this.completePaid(order);
                    return;
                }
                if (order.status === 'CLOSED') {
                    this.stopTimers();
                    this.invalidateQrDisplay();
                    this.transition(PaymentCenterCore.UI.EXPIRED, {orderStatus: 'CLOSED'});
                    await this.loadPending();
                    return;
                }
                const restoredUi = this.restoredUiAfterSync(previousUi);
                this.transition(restoredUi, {orderStatus: order.status, error: ''});
            } catch (error) {
                if (operation !== this.operationSequence || this.root.hidden) return;
                const restoredUi = this.restoredUiAfterSync(previousUi);
                this.transition(restoredUi, {
                    error: manual ? (error.message || '订单状态查询失败') : '',
                });
            } finally {
                this.syncInFlight = false;
                this.render();
                this.resumePollingAfterVisibility();
            }
        }

        restoredUiAfterSync(previousUi) {
            if (previousUi === PaymentCenterCore.UI.QR_RENDER_ERROR) {
                return PaymentCenterCore.UI.QR_RENDER_ERROR;
            }
            if (this.currentOrder && this.currentOrder.paymentMethod === 'QR'
                    && this.currentOrder.paymentReady && this.currentOrder.qrCode) {
                return PaymentCenterCore.UI.QR_READY;
            }
            return PaymentCenterCore.UI.CREATE_ERROR;
        }

        restoreCurrentQrLifecycle() {
            if (this.root.hidden || !this.currentOrder
                    || this.currentOrder.status !== 'CREATED') {
                this.stopTimers();
                return;
            }
            if (this.currentOrder.paymentMethod === 'QR'
                    && this.currentOrder.paymentReady
                    && this.currentOrder.qrCode) {
                this.startPolling();
                return;
            }
            this.startCountdownOnly();
        }

        submitAlipayForm(paymentHtml) {
            if (typeof paymentHtml !== 'string' || !paymentHtml.trim()) {
                throw new Error('支付宝收银台响应为空');
            }
            const sourceDocument = new DOMParser().parseFromString(paymentHtml, 'text/html');
            const sourceForms = sourceDocument.querySelectorAll('form');
            if (sourceForms.length !== 1) throw new Error('支付宝收银台表单数量异常');

            const sourceForm = sourceForms[0];
            if (sourceForm.method.toUpperCase() !== 'POST') {
                throw new Error('支付宝收银台只允许 POST 表单');
            }
            const action = new URL(sourceForm.getAttribute('action') || '', window.location.href);
            if (action.protocol !== 'https:' || action.port || action.username || action.password) {
                throw new Error('支付宝收银台地址不安全');
            }
            if (!PAYMENT_HOSTS.has(action.hostname) || action.pathname !== '/gateway.do') {
                throw new Error('支付宝收银台地址不在允许范围');
            }

            const form = document.createElement('form');
            form.method = 'POST';
            form.action = action.href;
            form.target = '_self';
            form.hidden = true;
            sourceForm.querySelectorAll('input[name]').forEach(sourceInput => {
                const input = document.createElement('input');
                input.type = 'hidden';
                input.name = sourceInput.name;
                input.value = sourceInput.value;
                form.appendChild(input);
            });
            document.body.appendChild(form);
            HTMLFormElement.prototype.submit.call(form);
        }

        normalizeOrder(rawOrder, fallback = {}) {
            const raw = rawOrder || {};
            const previous = fallback || {};
            return {
                orderNo: String(raw.orderNo || previous.orderNo || ''),
                orderType: String(raw.orderType || previous.orderType || ''),
                productCode: String(raw.productCode || previous.productCode || ''),
                productName: String(raw.productName || previous.productName || '支付宝支付'),
                amountCents: Number(raw.amountCents ?? previous.amountCents ?? 0),
                paymentMethod: String(raw.paymentMethod || previous.paymentMethod || 'PAGE').toUpperCase(),
                paymentHtml: raw.paymentHtml ?? previous.paymentHtml ?? null,
                qrCode: raw.qrCode ?? previous.qrCode ?? null,
                paymentReady: raw.paymentReady === undefined
                    ? Boolean(previous.paymentReady)
                    : Boolean(raw.paymentReady),
                paymentMessage: String(raw.paymentMessage || raw.message || previous.paymentMessage || ''),
                status: String(raw.status || previous.status || 'CREATED').toUpperCase(),
                expiresAt: raw.expiresAt || previous.expiresAt || null,
                createdAt: raw.createdAt || previous.createdAt || null,
            };
        }

        async postJson(url, payload) {
            const csrfToken = getCookie('csrftoken');
            const headers = {'X-CSRFToken': csrfToken};
            const options = {
                method: 'POST',
                credentials: 'same-origin',
                headers,
            };
            if (payload !== undefined) {
                headers['Content-Type'] = 'application/json';
                options.body = JSON.stringify(payload);
            }
            const response = await fetch(url, options);
            return this.readResponse(response);
        }

        async readResponse(response) {
            const payload = await response.json().catch(() => null);
            if (response.status === 401 || (payload && payload.code === 401)) {
                window.location.href = '/auth/login/';
                throw new Error('请先登录');
            }
            if (!response.ok || !payload || payload.code !== 200 || payload.data === undefined) {
                throw new Error((payload && payload.message) || '支付服务暂不可用，请稍后重试');
            }
            return payload.data;
        }

        openCurrentQrLink() {
            if (!this.currentOrder || !PaymentCenterCore.isTrustedQrUrl(this.currentOrder.qrCode)) return;
            window.open(this.currentOrder.qrCode, '_blank', 'noopener,noreferrer');
        }

        updatePendingCounts(count) {
            const safeCount = Math.max(0, Number(count) || 0);
            this.elements.pendingCount.textContent = safeCount > 99 ? '99+' : String(safeCount);
            this.elements.pendingCount.hidden = safeCount === 0;

            const navButton = document.getElementById('pendingPaymentButton');
            const navCount = document.getElementById('pendingPaymentCount');
            if (navCount) {
                navCount.textContent = safeCount > 99 ? '99+' : String(safeCount);
                navCount.hidden = safeCount === 0;
            }
            if (navButton) {
                navButton.setAttribute('aria-label', safeCount
                    ? `待支付订单，${safeCount} 笔`
                    : '待支付订单');
            }
        }

        async completePaid(order) {
            this.invalidateQrDisplay();
            this.setCurrentOrder({...order, status: 'PAID'});
            this.stopTimers();
            this.transition(PaymentCenterCore.UI.PAID, {
                orderStatus: 'PAID',
                error: '',
                renderError: '',
            });
            if (typeof window.loadUserCredits === 'function') window.loadUserCredits();
            if (typeof window.loadPendingPaymentCount === 'function') window.loadPendingPaymentCount();
            await this.loadPending();
        }

        startPolling() {
            this.stopPolling();
            if (this.state.ui === PaymentCenterCore.UI.PAID
                    || this.state.ui === PaymentCenterCore.UI.EXPIRED) {
                this.stopCountdown();
                return;
            }
            this.startCountdownOnly();
            if (this.state.ui === PaymentCenterCore.UI.PAID
                    || this.state.ui === PaymentCenterCore.UI.EXPIRED) return;
            this.ensurePollingInterval();
        }

        ensurePollingInterval() {
            if (this.pollTimer || document.hidden || this.root.hidden || !this.currentOrder
                    || this.currentOrder.status !== 'CREATED') return;
            this.pollTimer = setInterval(() => this.syncCurrentOrder(false), 5000);
        }

        startCountdownOnly() {
            this.stopCountdown();
            if (this.state.ui === PaymentCenterCore.UI.PAID
                    || this.state.ui === PaymentCenterCore.UI.EXPIRED) return;
            this.updateCountdown();
            if (this.state.ui === PaymentCenterCore.UI.PAID
                    || this.state.ui === PaymentCenterCore.UI.EXPIRED) return;
            if (document.hidden || this.root.hidden || !this.currentOrder
                    || !this.currentOrder.expiresAt
                    || this.expirySyncRequested === this.currentOrder.orderNo) return;
            this.countdownTimer = setInterval(() => this.updateCountdown(), 1000);
        }

        stopPolling() {
            if (this.pollTimer) window.clearInterval(this.pollTimer);
            this.pollTimer = null;
        }

        stopCountdown() {
            if (this.countdownTimer) window.clearInterval(this.countdownTimer);
            this.countdownTimer = null;
        }

        stopTimers() {
            this.stopPolling();
            this.stopCountdown();
        }

        updateCountdown() {
            if (!this.currentOrder || !this.currentOrder.expiresAt) {
                this.elements.qrCountdown.textContent = '有效期以支付宝页面为准';
                return;
            }
            if (this.state.ui === PaymentCenterCore.UI.PAID) {
                this.elements.qrCountdown.textContent = '支付已完成';
                return;
            }
            if (this.state.ui === PaymentCenterCore.UI.EXPIRED) {
                this.elements.qrCountdown.textContent = '订单已过期';
                return;
            }
            const expiresAt = new Date(this.currentOrder.expiresAt).getTime();
            if (!Number.isFinite(expiresAt)) {
                this.elements.qrCountdown.textContent = '有效期以支付宝页面为准';
                return;
            }
            const remaining = Math.max(0, Math.ceil((expiresAt - Date.now()) / 1000));
            const orderNo = this.currentOrder.orderNo;
            if (remaining <= 0) {
                const shouldSync = this.expirySyncRequested !== orderNo;
                this.expirySyncRequested = orderNo;
                this.stopCountdown();
                this.elements.qrCountdown.textContent = '正在确认订单最终状态，请勿重复支付';
                this.ensurePollingInterval();
                if (shouldSync && !document.hidden && !this.root.hidden) {
                    this.syncCurrentOrder(false);
                }
                return;
            }
            if (this.expirySyncRequested === orderNo) {
                this.expirySyncRequested = null;
                this.updateOrderStatus();
            }
            const hours = Math.floor(remaining / 3600);
            const minutes = Math.floor((remaining % 3600) / 60);
            const seconds = remaining % 60;
            const pad = value => String(value).padStart(2, '0');
            this.elements.qrCountdown.textContent = `二维码有效期 ${pad(hours)}:${pad(minutes)}:${pad(seconds)}`;
        }

        handleVisibilityChange() {
            if (document.hidden) {
                this.stopPolling();
                this.resumePollingOnVisibility = false;
                return;
            }
            if (this.root.hidden || !this.currentOrder || this.currentOrder.status !== 'CREATED') return;
            this.resumePollingOnVisibility = true;
            this.syncCurrentOrder(false);
        }

        resumePollingAfterVisibility() {
            if (!this.resumePollingOnVisibility) return;
            this.resumePollingOnVisibility = false;
            if (document.hidden || this.root.hidden || !this.currentOrder
                    || this.currentOrder.status !== 'CREATED') return;
            const canPollQr = this.currentOrder.paymentMethod === 'QR'
                && this.currentOrder.paymentReady
                && (this.state.ui === PaymentCenterCore.UI.QR_READY
                    || this.state.ui === PaymentCenterCore.UI.QR_RENDER_ERROR);
            if (canPollQr) this.startPolling();
            else this.startCountdownOnly();
        }

        render() {
            const ui = this.state.ui;
            const order = this.currentOrder;
            const summary = order || this.currentPurchase;
            const showQrDetails = Boolean(order) && (
                order.paymentMethod === 'QR'
                || ui === PaymentCenterCore.UI.PAID
                || ui === PaymentCenterCore.UI.EXPIRED
            );

            this.elements.currentEmpty.hidden = ui !== PaymentCenterCore.UI.IDLE;
            this.elements.summary.hidden = !summary;
            this.elements.methods.hidden = !(
                ui === PaymentCenterCore.UI.CHOOSING
                || ui === PaymentCenterCore.UI.CREATE_ERROR
            );
            this.elements.creating.hidden = !(
                ui === PaymentCenterCore.UI.CREATING
                || ui === PaymentCenterCore.UI.PAGE_REDIRECTING
            );
            this.elements.createError.hidden = !(
                ui === PaymentCenterCore.UI.CREATE_ERROR
                || (Boolean(this.state.error) && ui !== PaymentCenterCore.UI.PAID)
            );
            this.elements.qrView.hidden = !showQrDetails;

            if (summary) {
                this.elements.product.textContent = summary.productName || '支付宝支付';
                this.elements.amount.textContent = formatCurrency(summary.amountCents);
            }
            this.elements.createError.textContent = this.state.error || this.state.renderError || '';
            this.elements.creating.querySelector('span').textContent = ui === PaymentCenterCore.UI.PAGE_REDIRECTING
                ? '正在前往支付宝官方收银台'
                : '正在创建支付订单';

            const qrLabel = this.elements.methodQr.querySelector('strong');
            const pageLabel = this.elements.methodPage.querySelector('strong');
            qrLabel.textContent = '扫码支付';
            pageLabel.textContent = '支付宝收银台';
            this.elements.methodQr.hidden = false;
            this.elements.methodPage.hidden = false;
            this.elements.methodQr.disabled = ui === PaymentCenterCore.UI.CREATING;
            this.elements.methodPage.disabled = ui === PaymentCenterCore.UI.CREATING || this.switchInFlight;
            if (order && order.paymentMethod === 'QR') {
                qrLabel.textContent = order.paymentReady ? '继续扫码支付' : '重试预下单';
                pageLabel.textContent = '切换官方收银台';
            } else if (order && order.paymentMethod === 'PAGE') {
                this.elements.methodQr.hidden = true;
                pageLabel.textContent = '继续前往收银台';
            }

            if (order) {
                this.elements.qrOrderNo.textContent = order.orderNo;
                const trustedQr = PaymentCenterCore.isTrustedQrUrl(order.qrCode);
                const qrContainer = this.elements.qrCanvas.parentElement;
                const showQrCanvas = order.status === 'CREATED'
                    && order.paymentMethod === 'QR'
                    && order.paymentReady
                    && order.qrCode
                    && ui !== PaymentCenterCore.UI.QR_RENDER_ERROR
                    && ui !== PaymentCenterCore.UI.PAID
                    && ui !== PaymentCenterCore.UI.EXPIRED;
                qrContainer.hidden = !showQrCanvas;
                this.elements.qrRetryRender.hidden = ui !== PaymentCenterCore.UI.QR_RENDER_ERROR;
                this.elements.qrOpenLink.hidden = !(ui === PaymentCenterCore.UI.QR_RENDER_ERROR && trustedQr);
                this.elements.qrCashier.hidden = !(
                    order.paymentMethod === 'QR'
                    && order.status === 'CREATED'
                    && ui !== PaymentCenterCore.UI.EXPIRED
                );
                this.elements.qrSync.hidden = order.status !== 'CREATED';
                this.elements.qrSync.disabled = this.syncInFlight || ui === PaymentCenterCore.UI.EXPIRED;
                this.elements.qrCashier.disabled = this.switchInFlight || ui === PaymentCenterCore.UI.QUERYING;
                this.elements.qrRetryRender.disabled = ui !== PaymentCenterCore.UI.QR_RENDER_ERROR;
                this.updateOrderStatus();
                this.updateCountdown();
            }
        }

        updateOrderStatus() {
            const ui = this.state.ui;
            let text = '等待扫码支付';
            if (ui === PaymentCenterCore.UI.QUERYING) text = '正在查询支付状态';
            if (ui === PaymentCenterCore.UI.QR_RENDER_ERROR) text = '二维码显示异常，订单仍可继续支付';
            if (ui === PaymentCenterCore.UI.CREATE_ERROR) {
                text = this.currentOrder && this.currentOrder.paymentReady
                    ? '原订单仍待确认，请勿重复支付'
                    : '二维码待生成，订单已保留';
            }
            if (this.currentOrder && this.currentOrder.status === 'CREATED'
                    && this.expirySyncRequested === this.currentOrder.orderNo) {
                text = '正在确认订单最终状态，请勿重复支付';
            }
            if (ui === PaymentCenterCore.UI.PAID) text = '支付成功，权益已到账';
            if (ui === PaymentCenterCore.UI.EXPIRED) text = '订单已过期或关闭';
            if (this.state.error && ui === PaymentCenterCore.UI.QR_READY) text = this.state.error;
            this.elements.qrStatus.textContent = text;
        }
    }

    document.addEventListener('DOMContentLoaded', () => {
        const root = document.getElementById('paymentCenterPanel');
        if (!root || !window.PaymentCenterCore) return;
        window.paymentCenter = new PaymentCenter(root);
        const params = new URLSearchParams(window.location.search);
        if (params.get('paymentCenter') === 'pending') {
            window.paymentCenter.openPending(document.getElementById('pendingPaymentButton'));
        }
    });
}());
