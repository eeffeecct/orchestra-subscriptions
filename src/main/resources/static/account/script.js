// ====================================================================
//  Orchestra — web-личный-кабинет (с покупкой через YooKassa)
// ====================================================================
//
// Грузит /api/account/me (cookie-based auth). На 401 → редирект на /account/login.
// На успех — рендерит email, статус подписки, ссылку, форму оплаты с тарифами.
// Оплата создаётся через POST /api/account/payments/yookassa → confirmationUrl
// YooKassa → редирект юзера на платёжку → возврат на /account/?return=yk&paymentId=<id>.
// Маркера «оплачено» в URL нет намеренно: YooKassa возвращает сюда и при отмене тоже,
// поэтому статус спрашиваем у бэка (см. checkYooKassaReturn ниже).

const API_BASE = '';

const loadingSection = document.getElementById('loading-section');
const contentSection = document.getElementById('content-section');

let __me = null;            // последний ответ /api/account/me
let __tariffs = [];         // список тарифов от /api/account/tariffs
let __selectedPlanId = null;
let __appliedPromo = null;  // { code, finalAmountKopeks, discountKopeks, originalAmountKopeks } | null

// ====================================================================
//  Начальная загрузка: баннер возврата из YooKassa + /api/account/me + тарифы
// ====================================================================

async function loadAccount() {
    try {
        const r = await fetch(`${API_BASE}/api/account/me`, {
            credentials: 'same-origin',
            headers: { 'Accept': 'application/json' },
        });
        if (r.status === 401) {
            window.location.href = '/account/login';
            return;
        }
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        __me = await r.json();
        render(__me);
        await loadTariffs();
        // После рендера проверяем — может быть юзер только что вернулся с YK.
        // НЕ доверяем URL-маркеру: YK редиректит на returnUrl при любом
        // исходе. Реальный статус берём через GET /api/account/payments/<id>/status.
        await handleYkReturn();
    } catch (e) {
        console.error('account load failed:', e);
        loadingSection.innerHTML = '<p>Загрузить данные не удалось. <a href="/account/login">Войти заново</a></p>';
    }
}

/**
 * Если в URL ?return=yk&paymentId=<id>, спрашиваем бэк о реальном статусе
 * платежа и показываем баннер по факту, а не по URL.
 */
async function handleYkReturn() {
    const params = new URLSearchParams(window.location.search);
    if (params.get('return') !== 'yk') return;
    const paymentId = params.get('paymentId');
    if (!paymentId) return;

    // Сразу чистим query чтобы при F5 не дёргать статус повторно
    const url = new URL(window.location.href);
    url.searchParams.delete('return');
    url.searchParams.delete('paymentId');
    window.history.replaceState({}, '', url.toString());

    const banner = document.getElementById('paid-banner');
    const titleEl = document.getElementById('paid-banner-title');
    const textEl = document.getElementById('paid-banner-text');
    if (!banner || !titleEl || !textEl) return;

    try {
        const r = await fetch(`${API_BASE}/api/account/payments/${encodeURIComponent(paymentId)}/status`, {
            credentials: 'same-origin',
            headers: { 'Accept': 'application/json' },
        });
        if (!r.ok) {
            // 404 / 401 — молча игнорим, баннер не показываем (это не атака на UX)
            return;
        }
        const data = await r.json();
        const amountRub = (data.amountKopeks / 100).toFixed(0);

        banner.classList.remove('paid-banner--success', 'paid-banner--pending', 'paid-banner--failed');

        if (data.status === 'SUCCEEDED') {
            banner.classList.add('paid-banner--success');
            titleEl.textContent = 'Оплата принята';
            textEl.textContent = `Тариф «${data.planName}» (${amountRub} ₽) активирован. Если данные подписки выше не обновились, обновите страницу через минуту.`;
        } else if (data.status === 'PENDING') {
            banner.classList.add('paid-banner--pending');
            titleEl.textContent = 'Оплата обрабатывается';
            textEl.textContent = `Платёж за «${data.planName}» (${amountRub} ₽) подтверждается банком. Обычно это занимает до минуты — обновите страницу.`;
        } else {
            // CANCELED / FAILED — оплата не прошла. Не нагнетаем: просто говорим что денег не сняли.
            banner.classList.add('paid-banner--failed');
            titleEl.textContent = 'Оплата не прошла';
            textEl.textContent = 'Платёж не подтверждён или отклонён банком. Средства не списаны. Повторите попытку.';
        }
        banner.hidden = false;
    } catch (e) {
        console.warn('payment status fetch failed:', e);
        // Не показываем баннер если не смогли проверить — лучше тишина, чем ложное «оплачено»
    }
}

function render(data) {
    document.getElementById('user-email').textContent = data.email || '—';

    const statusBig = document.getElementById('status-big');
    const statusSub = document.getElementById('status-sub');
    const statusLed = document.getElementById('status-led');
    const statusLabel = document.getElementById('status-label');
    const statusCard = document.getElementById('status-card');
    const payCardLabel = document.getElementById('pay-card-label');

    if (!data.hasSubscription) {
        statusBig.textContent = 'Подписки нет';
        statusSub.textContent = 'Выберите тариф ниже и оплатите его.';
        statusLed.classList.add('is-expired');
        statusLabel.textContent = 'Подписка';
        payCardLabel.textContent = 'Купить подписку';
    } else {
        const now = Math.floor(Date.now() / 1000);
        const expiresAt = data.expiresAtEpoch || 0;
        const diffMs = (expiresAt - now) * 1000;
        const expired = expiresAt > 0 && expiresAt < now;
        if (expired) {
            statusBig.textContent = 'Истекла';
            statusSub.textContent = 'Срок истёк ' + formatDate(expiresAt) + '. Для возобновления доступа оплатите тариф.';
            statusLed.classList.add('is-expired');
            statusCard.classList.add('is-expired');
            statusLabel.textContent = 'Подписка';
            payCardLabel.textContent = 'Возобновить подписку';
        } else {
            statusBig.textContent = formatTimeLeft(diffMs);
            const prefix = data.isTrial ? 'Пробный период · до ' : 'Активна до ';
            statusSub.textContent = prefix + formatDate(expiresAt);
            statusLed.classList.add('is-active');
            statusLabel.textContent = data.isTrial ? 'Пробный период' : 'Подписка';
            payCardLabel.textContent = data.isTrial ? 'Купить подписку' : 'Продлить подписку';
        }
    }

    renderSubscriptions(data);

    const subLink = data.subscriptionLink || '';
    if (subLink) {
        const subCard = document.getElementById('sub-card');
        document.getElementById('sub-value').textContent = subLink;
        subCard.hidden = false;
    }

    // Чекбокс автопродления — показываем только если recurrentEnabled на бэке
    const autoToggle = document.getElementById('pay-autorenew-toggle');
    if (autoToggle) autoToggle.hidden = !data.recurrentEnabled;

    loadingSection.hidden = true;
    contentSection.hidden = false;
}

// ====================================================================
//  Тарифы
// ====================================================================

async function loadTariffs() {
    try {
        const r = await fetch(`${API_BASE}/api/account/tariffs`, {
            credentials: 'same-origin',
            headers: { 'Accept': 'application/json' },
        });
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        __tariffs = await r.json();
        renderTariffs();
    } catch (e) {
        console.error('tariffs load failed:', e);
        document.getElementById('tariffs').innerHTML =
            '<p class="card-sub">Загрузить тарифы не удалось. Обновите страницу.</p>';
    }
}

function renderTariffs() {
    const wrap = document.getElementById('tariffs');
    if (!__tariffs.length) {
        wrap.innerHTML = '<p class="card-sub">Тарифы недоступны.</p>';
        return;
    }

    // Дефолт — третий тариф (6 месяцев в нашем порядке), как и в Mini App.
    if (!__selectedPlanId) {
        __selectedPlanId = (__tariffs[2] || __tariffs[0]).id;
    }

    const monthlyOf = (t) => Math.round(t.priceKopeks / 100 / Math.max(1, Math.round(t.days / 30)));
    const cheapestPerMonth = Math.min(...__tariffs.map(monthlyOf));

    wrap.innerHTML = __tariffs.map((t) => {
        const perMonth = monthlyOf(t);
        const isSelected = t.id === __selectedPlanId;
        const price = (t.priceKopeks / 100).toFixed(0);
        const months = Math.round(t.days / 30);
        const discount = months > 1
            ? Math.round((1 - (perMonth / monthlyOf(__tariffs[0]))) * 100)
            : 0;
        return `
            <label class="tariff ${isSelected ? 'is-selected' : ''}" data-plan-id="${t.id}">
                <input type="radio" name="tariff" value="${t.id}" ${isSelected ? 'checked' : ''}>
                <div class="tariff__main">
                    <b>${escapeHtml(t.title)}</b>
                    ${months > 1 ? `<small>${perMonth} ₽/мес${discount > 0 ? ` · −${discount}%` : ''}</small>` : ''}
                </div>
                <div class="tariff__price">${price} ₽</div>
            </label>
        `;
    }).join('');

    wrap.querySelectorAll('.tariff').forEach((el) => {
        el.addEventListener('click', () => {
            __selectedPlanId = el.dataset.planId;
            // Сбрасываем применённый промо — он мог быть посчитан под другой план
            resetPromo();
            renderTariffs();
            renderPayPrice();
        });
    });
    renderPayPrice();
}

function renderPayPrice() {
    const tariff = __tariffs.find((t) => t.id === __selectedPlanId);
    const priceEl = document.getElementById('pay-btn-price');
    if (!tariff) { priceEl.textContent = '—'; return; }
    if (__appliedPromo && __appliedPromo.finalAmountKopeks != null) {
        priceEl.innerHTML = `<s>${(__appliedPromo.originalAmountKopeks / 100).toFixed(0)} ₽</s>
                             <b>${(__appliedPromo.finalAmountKopeks / 100).toFixed(0)} ₽</b>`;
    } else {
        priceEl.textContent = (tariff.priceKopeks / 100).toFixed(0) + ' ₽';
    }
}

// ====================================================================
//  Промокод
// ====================================================================

function resetPromo() {
    __appliedPromo = null;
    const result = document.getElementById('promo-result');
    if (result) { result.hidden = true; result.textContent = ''; }
}

document.getElementById('promo-toggle').addEventListener('click', () => {
    const row = document.getElementById('promo-row');
    row.hidden = !row.hidden;
    if (!row.hidden) document.getElementById('promo-input').focus();
});

document.getElementById('promo-apply').addEventListener('click', async () => {
    const btn = document.getElementById('promo-apply');
    const input = document.getElementById('promo-input');
    const result = document.getElementById('promo-result');
    const code = (input.value || '').trim();
    if (!code) return;
    if (!__selectedPlanId) return;
    btn.setAttribute('disabled', '');
    try {
        const r = await fetch(`${API_BASE}/api/account/promos/validate`, {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ code, planId: __selectedPlanId }),
        });
        const data = await r.json().catch(() => ({}));
        if (data.valid) {
            __appliedPromo = data;
            result.textContent = `Скидка применена. Итого: ${(data.finalAmountKopeks / 100).toFixed(0)} ₽`;
            result.classList.remove('login-result--err');
            result.classList.add('login-result--ok');
        } else {
            __appliedPromo = null;
            result.textContent = data.message || 'Промокод не подходит';
            result.classList.remove('login-result--ok');
            result.classList.add('login-result--err');
        }
        result.hidden = false;
        renderPayPrice();
    } catch (e) {
        console.error('promo apply failed:', e);
        result.textContent = 'Проверить промокод не удалось.';
        result.classList.add('login-result--err');
        result.hidden = false;
    } finally {
        btn.removeAttribute('disabled');
    }
});

// ====================================================================
//  Оплата
// ====================================================================

document.getElementById('pay-btn').addEventListener('click', async () => {
    const btn = document.getElementById('pay-btn');
    if (btn.hasAttribute('disabled')) return;
    if (!__selectedPlanId) {
        alert('Выберите тариф');
        return;
    }
    const labelEl = document.getElementById('pay-btn-label');
    const originalLabel = labelEl.textContent;
    btn.setAttribute('disabled', '');
    btn.classList.add('is-busy');
    labelEl.textContent = 'Создание платежа…';

    try {
        const body = {
            planId: __selectedPlanId,
            autorenew: !!document.getElementById('pay-autorenew-checkbox').checked
                && !document.getElementById('pay-autorenew-toggle').hidden,
        };
        if (__appliedPromo && __appliedPromo.code) {
            body.promoCode = __appliedPromo.code;
        }
        const r = await fetch(`${API_BASE}/api/account/payments/yookassa`, {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
        });
        const data = await r.json().catch(() => ({}));
        if (!r.ok) {
            const msg = data.message || data.error || `HTTP ${r.status}`;
            alert('Создать платёж не удалось: ' + msg);
            return;
        }
        if (!data.confirmationUrl) {
            alert('Платёж создан, но ЮKassa не вернула ссылку на оплату. Повторите попытку.');
            return;
        }
        // Редиректим в ЮKassa в этом же окне — вернёт на /account/?return=yk&paymentId=<id>
        window.location.href = data.confirmationUrl;
    } catch (e) {
        console.error('payment failed:', e);
        alert('Создать платёж не удалось. Проверьте подключение к интернету.');
    } finally {
        // Если редирект не сработал по какой-то причине — возвращаем кнопку
        labelEl.textContent = originalLabel;
        btn.classList.remove('is-busy');
        btn.removeAttribute('disabled');
    }
});

// ====================================================================
//  Logout + Copy + helpers (без изменений)
// ====================================================================

document.getElementById('logout-btn').addEventListener('click', async () => {
    try {
        await fetch(`${API_BASE}/auth/logout`, {
            method: 'POST',
            credentials: 'same-origin',
        });
    } catch (e) {
        console.warn('logout fetch failed:', e);
    }
    window.location.href = '/account/login';
});

document.getElementById('copy-btn').addEventListener('click', async () => {
    const btn = document.getElementById('copy-btn');
    const text = document.getElementById('sub-value').textContent || '';
    if (!text) return;
    try {
        await navigator.clipboard.writeText(text);
        const original = btn.textContent;
        btn.textContent = 'Скопировано ✓';
        btn.classList.add('is-copied');
        setTimeout(() => {
            btn.textContent = original;
            btn.classList.remove('is-copied');
        }, 1800);
    } catch (e) {
        console.warn('clipboard write failed, falling back:', e);
        const range = document.createRange();
        range.selectNodeContents(document.getElementById('sub-value'));
        const sel = window.getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
        try { document.execCommand('copy'); btn.textContent = 'Скопировано'; } catch {}
    }
});

function formatDate(epoch) {
    if (!epoch) return '—';
    const d = new Date(epoch * 1000);
    return d.toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' });
}

function pluralizeDays(n) {
    const last = n % 10;
    const last2 = n % 100;
    if (last2 >= 11 && last2 <= 14) return n + ' дней';
    if (last === 1) return n + ' день';
    if (last >= 2 && last <= 4) return n + ' дня';
    return n + ' дней';
}

function pluralizeHours(n) {
    const last = n % 10;
    const last2 = n % 100;
    if (last2 >= 11 && last2 <= 14) return n + ' часов';
    if (last === 1) return n + ' час';
    if (last >= 2 && last <= 4) return n + ' часа';
    return n + ' часов';
}

// При остатке < 24h показываем часы — иначе «1 день» неоднозначен (23h vs 25h).
function formatTimeLeft(diffMs) {
    if (diffMs <= 0) return '0 дней';
    if (diffMs < 86400000) {
        const hours = Math.min(23, Math.max(1, Math.ceil(diffMs / 3600000)));
        return pluralizeHours(hours);
    }
    return pluralizeDays(Math.ceil(diffMs / 86400000));
}

function escapeHtml(s) {
    if (s == null) return '';
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

// ====================================================================
//  Мои подписки — только на чтение
// ====================================================================
//
// Переключаться из ЛК нельзя сознательно: это канал для тех, кто не в Telegram, и второй
// путь переключения — второй набор граблей ради процента трафика. Но ПОКАЗАТЬ подписки
// обязаны: человек, у которого на замороженной лежат его деньги, не должен видеть экран,
// где их нет.

/**
 * Главное здесь — ПОСЛЕДНЯЯ СТРОКА. Оплата из ЛК всегда идёт в работающую подписку, выбора
 * цели в этом канале нет. Человек, увидевший список из двух подписок и кнопку «Оплатить»,
 * без этой строки решит, что деньги пойдут в ту, которую он выберет глазами.
 */
function renderSubscriptions(data) {
    const card = document.getElementById('subs-card');
    const list = document.getElementById('subs-list');
    const note = document.getElementById('subs-note');
    if (!card || !list || !note) return;

    const subs = (data && Array.isArray(data.subscriptions)) ? data.subscriptions : [];
    // Подписка одна — карточки нет вовсе: человек с одним ключом не должен заметить,
    // что в системе что-то изменилось.
    if (subs.length < 2) {
        card.hidden = true;
        list.innerHTML = '';
        return;
    }
    card.hidden = false;
    list.innerHTML = subs.map(s => {
        const tier = s.tierLabel ? ' \u00b7 ' + escapeHtml(s.tierLabel) : '';
        return '<div class="subs-item' + (s.current ? ' subs-item--current' : '') + '">'
            + '<div class="subs-item__title">Подписка #' + Number(s.seq) + tier + '</div>'
            + '<div class="subs-item__badges">' + subsBadges(s) + '</div>'
            + '<div class="subs-item__state">' + subsState(s) + '</div>'
            + '</div>';
    }).join('');

    // Формулировка ветвится по live, а не по current: у истёкшей подписки флаг current
    // остаётся выставленным, и без этой развилки экран говорил бы «та, что работает
    // сейчас» ровно под строкой «не работает · истекла 3 августа».
    const current = subs.find(s => s.current);
    const tail = ' Пополнение другой подписки и переключение доступны в боте или приложении Telegram.';
    if (!current) {
        note.textContent = 'Ни одна из подписок не действует. Включить нужную можно '
            + 'в боте или приложении Telegram.';
    } else if (current.live) {
        note.textContent = 'Оплата из кабинета продлевает действующую подписку #'
            + Number(current.seq) + '.' + tail;
    } else {
        note.textContent = 'Оплата из кабинета зачисляется в подписку #' + Number(current.seq)
            + ' — последнюю активную. Она заработает сразу после оплаты.' + tail;
    }
}

/**
 * Бейджи состояния словами — те же, что в Mini App.
 *
 * Значок на их месте отвечал только на «какая работает» и молчал о втором вопросе:
 * какую подписку продлят с карты. Пометку продления считает сервер — замороженные не
 * продлеваются никогда, и повторять это правило на фронте значило бы завести второй его экземпляр.
 */
function subsBadges(s) {
    var out = [];
    if (s.frozen) {
        out.push('<span class="subs-badge">Заморожена</span>');
    } else if (s.live) {
        out.push('<span class="subs-badge subs-badge--live">Действует сейчас</span>');
    } else {
        out.push('<span class="subs-badge subs-badge--dead">Не действует</span>');
    }
    if (s.renewing) {
        out.push('<span class="subs-badge">Продлевается автоматически</span>');
    }
    return out.join('');
}

/** Те же формулировки, что в боте и Mini App — разошедшиеся экраны не разобрать. */
function subsState(s) {
    // Состояние называет бейдж, поэтому строка его не повторяет: слова, ради которых она
    // существует, — остаток и «срок не расходуется».
    if (s.frozen) {
        return 'остаток ' + pluralizeDays(s.daysLeft || 0) + ' \u00b7 срок не расходуется';
    }
    if (s.live) {
        return 'до ' + formatDate(s.expiresAtEpoch);
    }
    return s.expiresAtEpoch
        ? 'истекла ' + formatDate(s.expiresAtEpoch)
        : 'срок вышел';
}

loadAccount();
