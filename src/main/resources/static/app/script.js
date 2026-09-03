// ====================================================================
//  Telegram WebApp init
// ====================================================================
const tg = window.Telegram.WebApp;
tg.ready();
tg.expand();

const user = tg.initDataUnsafe.user;

const API_BASE = '';

// ====================================================================
//  Уважение к системной настройке «меньше движения»
// ====================================================================
//
// Telegram прокидывает системный prefers-reduced-motion в WebView. Обе фоновые
// анимации приложения (дождь и полки сервисов) — декоративные, и при этой
// настройке они просто не запускаются: дождь не спавнится, полки остаются
// обычным горизонтальным скроллом.
const REDUCED_MOTION = !!(window.matchMedia
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches);

// ====================================================================
//  Matrix-rain бэкграунд: вертикально падающие колонки «O R C H E S T R A …»
// ====================================================================
//
// Колонки — обычные <div> с writing-mode:vertical-rl и CSS-анимацией fall.
// JS только спавнит N колонок при загрузке и перераскидывает их при ресайзе.
// Перформанс: чистый CSS-transform → GPU-accelerated, без RAF.
//
// Числа ниже подобраны так, чтобы дождь читался как фактура, а не как движение
// под текстом. Относительно первой версии: колонок реже (одна на 68px вместо 60),
// проход экрана 40–56 с вместо 28–42, прозрачность .07–.10 вместо .09–.14.
// Это три ручки яркости фона — крутить здесь, больше нигде они не заданы.

function spawnRainColumns() {
    const wrap = document.getElementById('bg-dvd');
    if (!wrap) return;
    wrap.innerHTML = '';
    if (REDUCED_MOTION) return;

    const text = 'O R C H E S T R A   ';
    const fill = text.repeat(8);

    const w = window.innerWidth;
    const cols = Math.max(5, Math.min(11, Math.floor(w / 68)));

    for (let i = 0; i < cols; i++) {
        const col = document.createElement('div');
        col.className = 'rain-col';
        col.textContent = fill;
        // Размещаем по горизонтали равномерно. (i+0.5) даёт центр каждой секции.
        col.style.left = `${(i + 0.5) * (100 / cols)}%`;
        // Разные длительности — колонки идут не в унисон
        col.style.animationDuration = `${40 + (i * 4.7) % 16}s`;
        col.style.animationDelay = `${-i * 6}s`;
        // Лёгкая разница в прозрачности и кегле — глубина
        col.style.opacity = String(0.07 + (i % 3) * 0.015);
        col.style.fontSize = `${11 + (i % 3)}px`;
        wrap.appendChild(col);
    }
}

spawnRainColumns();

// При ресайзе пересчитываем количество колонок — throttled чтобы не дёргать
// на каждый пиксель при перетаскивании окна.
let __rainResizeTimer = null;
window.addEventListener('resize', () => {
    clearTimeout(__rainResizeTimer);
    __rainResizeTimer = setTimeout(spawnRainColumns, 200);
});

// ====================================================================
//  Haptic helper — лёгкая вибрация на тапе. Работает в Telegram на мобилах,
//  в десктопе/вне Telegram просто no-op.
// ====================================================================
function haptic(style = 'light') {
    try { tg.HapticFeedback.impactOccurred(style); } catch {}
}

// ====================================================================
//  openSmart — выбирает openTelegramLink vs openLink по host'у URL.
//  Telegram-WebApp API:
//   - openTelegramLink(url) — для t.me/* ссылок, открывает внутри Telegram нативно;
//   - openLink(url) — для обычных https, открывает во внешнем/in-app браузере.
//  Если t.me-ссылку открыть через openLink — Telegram сначала уйдёт в браузер,
//  потом deep-link обратно, что закрывает текущий Mini App. Поэтому различаем.
// ====================================================================
function openSmart(url) {
    if (!url) return;
    try {
        const u = new URL(url);
        if (u.hostname === 't.me' || u.hostname.endsWith('.t.me')) {
            tg.openTelegramLink(url);
            return;
        }
    } catch { /* битый URL — fallback на openLink */ }
    tg.openLink(url);
}

// ====================================================================
//  Навигация между экранами (Home / Setup / Profile / Support)
// ====================================================================
//
// Схема: у нас 4 <section class="screen"> + <nav class="bottom-nav"> с 4 кнопками.
// data-screen-id связывает nav-кнопку с секцией. Клик: снимаем .hidden с нужной
// секции, ставим .hidden на остальные; .active — на нужную nav-кнопку.

const screens = document.querySelectorAll('.screen');
const navBtns = document.querySelectorAll('.nav-btn');
const titleEl = document.getElementById('screen-title');

const SCREEN_TITLES = {
    home: 'Главная',
    setup: 'Подключение',
    profile: 'Профиль',
    support: 'Поддержка',
    devices: 'Мои устройства',
    history: 'История',
    referral: 'Рефералы',
    'autorenew-settings': 'Автопродление',
    'email-settings': 'Email',
    servers: 'Серверы',
};

function showScreen(id) {
    screens.forEach(s => s.classList.toggle('hidden', s.dataset.screenId !== id));

    // Подэкраны (history, referral, autorenew-settings) имеют data-parent-screen-id.
    // Для них подсвечиваем родительскую вкладку в nav — юзер видит что он "в Профиле".
    const target = document.querySelector(`.screen[data-screen-id="${id}"]`);
    const navId = (target && target.dataset.parentScreenId) || id;
    navBtns.forEach(b => b.classList.toggle('active', b.dataset.screenId === navId));

    titleEl.textContent = SCREEN_TITLES[id] || 'Orchestra';

    // Скролл вверх при смене экрана
    window.scrollTo({ top: 0, behavior: 'instant' });
}

navBtns.forEach(btn => {
    btn.addEventListener('click', () => {
        haptic('light');
        showScreen(btn.dataset.screenId);
    });
});

// Кнопки "← Профиль" на подэкранах
document.querySelectorAll('.sub-back').forEach(btn => {
    btn.addEventListener('click', () => {
        haptic('light');
        showScreen(btn.dataset.backTo || 'profile');
    });
});

// ====================================================================
//  Hero: статус подписки (Home)
// ====================================================================
const statusEl = document.getElementById('status');
statusEl.textContent = user ? 'Загрузка...' : 'Вне Telegram данные недоступны';

// Профиль: имя, id — берём сразу из initDataUnsafe, не ждём /api/me
const profileNameEl = document.getElementById('profile-name');
const profileIdEl = document.getElementById('profile-id');
if (user) {
    profileNameEl.textContent = [user.first_name, user.last_name].filter(Boolean).join(' ') || 'Пользователь';
    profileIdEl.textContent = `id: ${user.id}`;

    // Аватарка: если Telegram передал photo_url — заменяем SVG на img.
    // Поле опционально (user может не иметь фото или скрывать его от WebApp).
    if (user.photo_url) {
        const avatarEl = document.querySelector('.avatar');
        if (avatarEl) {
            avatarEl.innerHTML = `<img src="${user.photo_url}" alt="" referrerpolicy="no-referrer">`;
        }
    }
}

// ====================================================================
//  /api/me — статус подписки + config
// ====================================================================
let appConfig = { guideUrl: '', offerUrl: '', botUsername: '' };

// Demo-режим: на localhost (dev preview) — инжектим фейковые данные, чтобы UI
// рендерился полноценно (с subscription link, toggle'ами, статусом и тд),
// а не пустыми placeholder'ами «Вне Telegram данные недоступны».
// В обычном режиме (открыто внутри Telegram) блок не используется.
function isPreviewMode() {
    return location.hostname === 'localhost' || location.hostname === '127.0.0.1';
}

function applyDemoData() {
    const demo = {
        hasSubscription: true,
        status: 'ACTIVE',
        isTrial: true,
        expiresAtEpoch: Math.floor(Date.now() / 1000) + 14 * 86400,
        subscriptionLink: 'https://example.com/sub/demo-token',
        accessLink: 'https://example.com/sub/demo-token',
        // Автопродление включено: иначе строку в карточке статуса, факты на экране
        // автопродления и пометку «продлевается» в списке негде было бы проверить без Telegram.
        autorenewAvailable: true,
        autorenewEnabled: true,
        autorenewInfo: {
            enabled: true, cardBound: true, willCharge: true,
            // Ступень совпадает со ступенью подписки #1 — иначе demo показывал бы
            // состояние, которого сервер больше не отдаёт.
            planName: 'Месяц', planGone: false,
            tierName: '2 устройства', amountRub: 299,
            nextChargeEpoch: Math.floor(Date.now() / 1000) + 13 * 86400,
            targetSeq: 1, lastFailureEpoch: 0,
        },
        deviceLimit: 2,
        devicesUsed: 1,
        // Две подписки — чтобы dev-preview показывал карточку «Мои подписки» и строку
        // выбора цели платежа. С одной подпиской оба элемента скрыты — и проверить
        // их без Telegram было бы негде.
        subscriptions: [
            {
                id: 1, seq: 1, tierLabel: '2 устройства', tierId: 'd2', devices: 2,
                daysLeft: 14, frozen: false, current: true, live: true, renewing: true,
                expiresAtEpoch: Math.floor(Date.now() / 1000) + 14 * 86400,
                link: 'https://sub.example/sub/demotoken',
            },
            {
                id: 2, seq: 2, tierLabel: 'Семейная ×5', tierId: 'f5', devices: 5,
                daysLeft: 40, frozen: true, current: false, live: false, renewing: false,
                expiresAtEpoch: 0, link: null,
            },
        ],
        multiSub: { canOpenNew: true, maxPerUser: 3, switchingEnabled: true },
        config: {
            guideUrl: 'https://example.com',
            offerUrl: 'https://example.com',
            recurrentEnabled: true,
            newsChannelUrl: 'https://t.me/example',
        },
    };
    // Через тот же путь, что и боевые данные: иначе demo-режим разъезжается
    // с реальным рендером и перестаёт быть проверкой.
    applyMeData(demo);
    // Demo-имя в профиле
    if (profileNameEl) profileNameEl.textContent = 'Demo User';
    if (profileIdEl) profileIdEl.textContent = 'id: 0000000';
}

async function loadMe() {
    if (!user) {
        if (isPreviewMode()) applyDemoData();
        return;
    }
    try {
        const response = await fetch(`${API_BASE}/api/me`, {
            headers: { 'X-Telegram-Init-Data': tg.initData },
        });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const data = await response.json();

        // Детект «оплата только что прошла»: сравниваем expiresAt до и после fetch.
        // Если стало больше — подписка продлилась/активировалась. Показываем toast,
        // чтобы юзер сразу видел в Mini App, что всё прошло (а не только в DM от бота).
        // Skip на самом первом загрузке (prev пустой).
        const prev = window.__meData;
        if (prev && data.hasSubscription) {
            const prevExp = prev.expiresAtEpoch || 0;
            const newExp = data.expiresAtEpoch || 0;
            if (newExp > prevExp + 60 /* +1 мин запас от мелких сдвигов */) {
                try { tg.HapticFeedback.notificationOccurred('success'); } catch {}
                tg.showAlert('Подписка активна. Срок: до ' + formatDate(newExp));
            }
        }

        applyMeData(data);
    } catch (e) {
        statusEl.textContent = 'Загрузить статус не удалось';
        console.error('loadMe failed:', e);
    }
}

/**
 * Перерисовать всё, что зависит от состояния человека.
 *
 * Отдельной функцией, потому что ровно этот же payload приходит ответом на
 * активацию подписки: переключение меняет срок, ссылку и лимит устройств разом, и
 * перерисовываться надо из ОДНОГО снимка. Иначе между ответом на активацию и
 * догрузкой /me существует окно, в котором на экране новая подписка со старой ссылкой —
 * то есть человек копирует ссылку, которая уже не работает.
 */
function applyMeData(data) {
    // Кэшируем — autorenew screen и т.п. могут переиспользовать без fetch
    window.__meData = data;

    // Конфиг (URL-ы + флаги) — используем в Setup/Support и ставим на support-кнопки
    if (data.config) appConfig = data.config;

    renderSubscriptionStatus(data);
    renderSubscriptionLink(data);
    renderAutorenewStatus(data);
    renderEmailUi(data);
    renderDevicesMenuStatus(data);
    renderSubscriptions(data);
    renderRescueBanner(data);

    // News channel item в Support экране — показываем только если backend задал URL
    // (env NEWS_CHANNEL_URL). Сделано через hidden-toggle а не conditional render —
    // элемент уже в DOM, надо просто снять/вернуть атрибут.
    const newsItem = document.getElementById('support-news');
    if (newsItem) newsItem.hidden = !appConfig.newsChannelUrl;
}

// Подпись пункта «Мои устройства» в профиле: «2 из 3» при заданном лимите тарифа.
function renderDevicesMenuStatus(data) {
    const el = document.getElementById('profile-devices-status');
    if (!el) return;
    if (data && data.deviceLimit > 0) {
        el.textContent = `${data.devicesUsed ?? 0} из ${data.deviceLimit}`;
    } else {
        el.textContent = 'подключённые устройства';
    }
}

// Pluralize "день/дня/дней" по правилам русского языка
function pluralizeDays(n) {
    const mod10 = n % 10;
    const mod100 = n % 100;
    if (mod10 === 1 && mod100 !== 11) return n + ' день';
    if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return n + ' дня';
    return n + ' дней';
}

function pluralizeDevices(n) {
    const mod10 = n % 10;
    const mod100 = n % 100;
    if (mod10 === 1 && mod100 !== 11) return n + ' устройство';
    if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return n + ' устройства';
    return n + ' устройств';
}

function pluralizeHours(n) {
    const mod10 = n % 10;
    const mod100 = n % 100;
    if (mod10 === 1 && mod100 !== 11) return n + ' час';
    if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return n + ' часа';
    return n + ' часов';
}

// При остатке < 24h показываем часы — иначе «1 день» неоднозначен (23h vs 25h).
// Cap 23 защищает от «24 часа» когда ceil дёргает близкое к границе значение.
function formatTimeLeft(diffMs) {
    if (diffMs <= 0) return '0 дней';
    if (diffMs < 86400000) {
        const hours = Math.min(23, Math.max(1, Math.ceil(diffMs / 3600000)));
        return pluralizeHours(hours);
    }
    return pluralizeDays(Math.ceil(diffMs / 86400000));
}

function renderSubscriptionStatus(data) {
    const bigEl = document.getElementById('status-big');
    const labelEl = document.getElementById('status-label');
    const ledEl = document.getElementById('status-led');
    const progressEl = document.getElementById('status-progress');

    function setLed(active) {
        if (ledEl) ledEl.classList.toggle('led--off', !active);
    }
    function setProgress(pct) {
        if (progressEl) progressEl.style.setProperty('--progress', `${pct}%`);
    }

    // Экран показывает РАБОТАЮЩУЮ подписку. Без её номера человек с несколькими подписками не
    // понимает, к какой из них относятся срок, устройства и ссылка на этой странице, — а
    // ссылку он копирует именно отсюда.
    const subs = (data && data.subscriptions) || [];
    const currentSub = subs.find(s => s.current);
    const numbered = subs.length > 1 && currentSub
        ? `Подписка #${currentSub.seq} из ${subs.length}` : null;
    // Оплаченные дни, лежащие на замороженной подписке: от их наличия зависит, звать ли
    // человека платить. Считаем тем же способом, что и плашка под карточкой.
    const rescue = rescueParked(data);

    renderRenewLine(data);

    if (!data.hasSubscription) {
        // Не прочерк: на 40px он занимает всю строку и ничего не сообщает.
        if (bigEl) bigEl.textContent = '0 дней';
        if (labelEl) labelEl.textContent = 'Нет подписки';
        statusEl.textContent = 'выберите тариф ниже';
        setLed(false);
        setProgress(0);
        return;
    }
    // Не только по статусу: статуса «истекла» в системе нет, подписка с прошедшим сроком
    // остаётся ACTIVE до прохода планировщика. Признак «работает» считает сервер (live) —
    // без него карточка в этом окне показывала бы зелёный индикатор и дату из прошлого.
    if (data.status === 'DISABLED' || (currentSub && !currentSub.live)) {
        if (bigEl) bigEl.textContent = '0 дней';
        if (labelEl) labelEl.textContent = numbered ? `${numbered}, истекла` : 'Истекла';
        // Звать платить, когда оплаченные дни уже есть, нельзя: плашка прямо под карточкой
        // говорит «оплата не требуется», и два совета подряд противоречат друг другу.
        statusEl.textContent = rescue
            ? 'оплаченные дни есть — включите подписку ниже'
            : 'продлите подписку ниже';
        setLed(false);
        setProgress(0);
        return;
    }

    const diffMs = data.expiresAtEpoch ? (data.expiresAtEpoch * 1000 - Date.now()) : 0;
    const daysLeft = Math.max(0, Math.ceil(diffMs / 86400000));
    const expires = formatDate(data.expiresAtEpoch);

    if (bigEl) bigEl.textContent = formatTimeLeft(diffMs);
    // Триал → label «Пробный период», sub-line с префиксом для ясности что
    // это не платная (т.е. истечёт без автопродления).
    // Платный → label «Подписка», sub просто дата без префикса (label уже сказал).
    // Подписок несколько → номер вытесняет и то и другое: он важнее, потому что отвечает на
    // вопрос «а это про какую из них».
    if (labelEl) {
        labelEl.textContent = numbered || (data.isTrial ? 'Пробный период' : 'Подписка');
    }
    // Число устройств приписываем к сроку: человек покупает срок И устройства, а на экране
    // до этого стоял только срок. «Тариф свой не вижу, хочу видеть 10 устройств» — дословная
    // жалоба покупателя семейной ступени. Лимита нет (триал/legacy) — приписки нет.
    const devicesSuffix = data.deviceLimit > 0 ? ` на ${pluralizeDevices(data.deviceLimit)}` : '';
    statusEl.textContent = data.isTrial
        ? `пробный период до ${expires}${devicesSuffix}`
        : `до ${expires}${devicesSuffix}`;
    setLed(true);
    // Прогресс-бар: декоративный — заполняется тем больше, чем больше осталось дней
    // (cap 30 — для долгих подписок просто показываем 100%).
    setProgress(Math.max(8, Math.min(100, daysLeft / 30 * 100)));
}

/**
 * Строка автопродления в карточке статуса: сумма и дата списания.
 *
 * Показываем только когда списание РЕАЛЬНО произойдёт (willCharge считает сервер: включено,
 * карта привязана и магазин умеет рекуррентные платежи). Написать «продлится автоматически»
 * там, где не будет ни одной попытки списания, — обещание, которое сервис не выполнит, и
 * человек узнает об этом переставшим работать доступом.
 */
function renderRenewLine(data) {
    const el = document.getElementById('status-renew');
    if (!el) return;
    const info = (data && data.autorenewInfo) || null;
    if (!info || !info.willCharge) {
        el.hidden = true;
        el.innerHTML = '';
        return;
    }
    const amount = info.amountRub > 0 ? `<b>${info.amountRub} ₽</b>` : '<b>включено</b>';
    const when = info.nextChargeEpoch ? ` спишется ${formatDate(info.nextChargeEpoch)}` : '';
    el.innerHTML = `<span>Автопродление: ${amount}${when}</span>`;
    el.hidden = false;
}

/**
 * Плашка «на замороженной подписке лежат оплаченные дни».
 *
 * Самый дорогой для человека случай: доступа нет, а дни уже оплачены — и он идёт покупать
 * второй раз. Условие показа именно «нет ни одной РАБОТАЮЩЕЙ подписки», а не «текущая
 * истекла»: статуса «истекла» в системе нет, и подписка с прошедшим сроком остаётся текущей.
 *
 * Пустые замороженные не предлагаем: их включение — это «остаться без доступа прямо сейчас».
 */
function rescueParked(data) {
    const subs = (data && data.subscriptions) || [];
    if (subs.some(s => s.live)) {
        return null;
    }
    return subs
        .filter(s => s.frozen && (s.daysLeft || 0) > 0)
        .sort((a, b) => (b.daysLeft || 0) - (a.daysLeft || 0))[0] || null;
}

function renderRescueBanner(data) {
    const banner = document.getElementById('rescue-banner');
    if (!banner) return;
    const parked = rescueParked(data);

    if (!parked) {
        banner.hidden = true;
        return;
    }
    document.getElementById('rescue-banner-title').textContent =
        `На подписке #${parked.seq} сохранено ${pluralizeDays(parked.daysLeft)}`;
    document.getElementById('rescue-banner-sub').textContent =
        'Включите её — оплата не требуется.';
    banner.dataset.subId = parked.id;
    banner.hidden = false;
}

document.getElementById('rescue-banner-cta').addEventListener('click', () => {
    haptic('light');
    const id = document.getElementById('rescue-banner').dataset.subId;
    if (id) openSwitchDrawer(id);
});

function renderSubscriptionLink(data) {
    const link = (data && data.subscriptionLink) || '';
    const managedLink = (data && data.accessLink) || '';

    // Показываем ВСЕГДА ссылку сервиса со встроенным обходом РФ-сервисов (решение владельца
    // 2026-07-15: сырую AccessProvider-ссылку без маршрутизации юзеру не отдаём нигде). Фолбэк
    // на обычную — только если вариант сервиса не построился (не должен случаться в норме).
    const displayLink = managedLink || link;

    // Основная ссылка на Home — видна сразу как только backend выдал trial / платку
    const homeSection = document.getElementById('home-sub-section');
    const homeValue = document.getElementById('home-sub-value');
    homeValue.textContent = displayLink;
    homeSection.hidden = !link;

    // Подсказка «поставьте приложение» — только пока ни одного устройства не появилось.
    // devicesUsed приходит в /api/me и считается по x-hwid: как только приложение хоть раз сходило
    // за подпиской, подсказка исчезает сама и не мозолит глаза постоянным клиентам.
    const setupNudge = document.getElementById('home-setup-nudge');
    if (setupNudge) {
        setupNudge.hidden = !link || ((data && data.devicesUsed) || 0) > 0;
    }

    // Дубликат в Profile — привычка, удобно когда юзер туда пришёл проверить
    const profileSection = document.getElementById('sub-link-section');
    const profileValue = document.getElementById('sub-link-value');
    profileValue.textContent = displayLink;
    profileSection.hidden = !link;

    // Короткий лейбл чтобы не клиппировался на узких экранах. «Продлить» / «Оплатить» —
    // глагол одним словом, цена всё равно показана справа.
    buyVerb = link ? 'Продлить' : 'Оплатить';
    updateBuyBtnLabel();
}

/**
 * Подпись кнопки оплаты.
 *
 * Пока срок не выбран, кнопка неактивна — и раньше она в этом состоянии говорила
 * «Продлить» и показывала прочерк вместо цены, то есть выглядела сломанной, а не
 * ждущей выбора. Теперь неактивная кнопка называет недостающий шаг, а прочерк
 * убран: цена появляется вместе с выбранным сроком.
 */
let buyVerb = 'Оплатить';

function updateBuyBtnLabel() {
    const label = document.getElementById('buy-btn-label');
    const price = document.getElementById('buy-btn-price');
    const chosen = !!selectedTariffId;
    if (label) label.textContent = chosen ? buyVerb : 'Выберите срок';
    if (price) price.hidden = !chosen;
}

function renderAutorenewStatus(data) {
    // Видимость:
    //   - Profile-кнопка «Автопродление» появляется когда фича доступна (recurrentEnabled)
    //     ИЛИ когда у юзера уже есть привязанная карта (autorenewAvailable). Второе нужно
    //     чтобы юзер мог управлять старой подпиской даже если временно выключили фичу флагом.
    //   - Чекбокс в YooKassa drawer — только когда recurrentEnabled (в выключенном виде
    //     opt-in бессмысленен).
    const recurrentEnabled = !!(data && data.config && data.config.recurrentEnabled);
    const autorenewAvailable = !!(data && data.autorenewAvailable);

    const profileItem = document.getElementById('profile-autorenew-item');
    if (profileItem) profileItem.hidden = !(recurrentEnabled || autorenewAvailable);

    const drawerToggle = document.getElementById('pay-autorenew-toggle');
    if (drawerToggle) drawerToggle.hidden = !recurrentEnabled;

    // Текст статуса в profile-меню (виден только если кнопка не скрыта)
    const el = document.getElementById('autorenew-status');
    if (el) {
        if (!data.hasSubscription) {
            el.textContent = 'недоступно, нет подписки';
        } else {
            el.textContent = data.autorenewEnabled ? 'включено' : 'выключено';
        }
    }
}

function formatDate(epochSeconds) {
    if (!epochSeconds) return '—';
    const date = new Date(epochSeconds * 1000);
    return new Intl.DateTimeFormat('ru-RU', {
        day: 'numeric', month: 'long', year: 'numeric',
    }).format(date);
}

// ====================================================================
//  Копирование ссылки подписки (работает и для home, и для profile кнопок)
// ====================================================================
async function copySubscriptionLink() {
    // Оба варианта value — home и profile, оба синхронизированы renderSubscriptionLink-ом
    const link = document.getElementById('home-sub-value').textContent
        || document.getElementById('sub-link-value').textContent;
    if (!link) return;
    try {
        await navigator.clipboard.writeText(link);
        try { tg.HapticFeedback.notificationOccurred('success'); } catch {}
        // Показываем не просто «скопировано», а сразу следующий шаг —
        // юзер не должен сам догадываться куда вставлять.
        tg.showAlert('Ссылка скопирована. Откройте клиентское приложение → «+» → «Вставить из буфера».');
    } catch (e) {
        tg.showAlert('Скопировать автоматически не удалось. Выделите ссылку выше и скопируйте вручную.');
    }
}

document.getElementById('home-sub-copy').addEventListener('click', () => copySubscriptionLink());

// Подсказка ведёт на тот же экран, что вторая кнопка нижней навигации, — не дублирует
// его содержимое, а сокращает путь из точки, где человек только что получил ссылку.
document.getElementById('home-setup-nudge').addEventListener('click', () => showScreen('setup'));
document.getElementById('sub-link-copy').addEventListener('click', () => copySubscriptionLink());

// ====================================================================
//  клиентское приложение download links (Setup screen)
// ====================================================================
document.querySelectorAll('.platform-btn').forEach(btn => {
    btn.addEventListener('click', () => {
        haptic('light');
        const url = btn.dataset.url;
        if (url) tg.openLink(url);
    });
});

// ====================================================================
//  Кнопки меню Профиля — открывают подэкраны и подгружают данные
// ====================================================================
const PROFILE_MENU_SCREENS = {
    devices: 'devices',
    history: 'history',
    referral: 'referral',
    autorenew: 'autorenew-settings',
    email: 'email-settings',
};

document.querySelectorAll('[data-action]').forEach(btn => {
    btn.addEventListener('click', () => {
        haptic('light');
        const action = btn.dataset.action;
        const screenId = PROFILE_MENU_SCREENS[action];
        if (!screenId) return;

        showScreen(screenId);

        // Грузим данные для подэкрана. Лениво — только при первом открытии.
        // После этого перезагружаем каждый раз, т.к. данные могли измениться.
        if (action === 'history') loadHistory();
        else if (action === 'referral') loadReferral();
        else if (action === 'autorenew') loadAutorenew();
        else if (action === 'email') loadEmailScreen();
        else if (action === 'devices') loadDevices();
    });
});

// ====================================================================
//  Devices screen: /api/devices → список устройств + отключить/вернуть
// ====================================================================
async function loadDevices() {
    const listEl = document.getElementById('devices-list');
    const slotsEl = document.getElementById('devices-slots');
    listEl.innerHTML = '<p class="empty-state">Загрузка…</p>';

    try {
        const r = await fetch(`${API_BASE}/api/devices`, {
            headers: { 'X-Telegram-Init-Data': tg.initData },
        });
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        const data = await r.json();

        slotsEl.textContent = data.deviceLimit > 0
            ? `Занято ${data.devicesUsed} из ${data.deviceLimit}`
            : 'Лимит не задан';

        // Кнопка «Добавить устройство» — если докупка доступна (активная подписка со ступенью).
        const addonBtn = document.getElementById('devices-addon');
        if (addonBtn) {
            const addon = data.addon || {};
            addonBtn.hidden = !addon.available;
            if (addon.available) {
                const rub = Math.round(addon.priceKopeks / 100);
                document.getElementById('devices-addon-price').textContent =
                    `${rub} ₽ до конца текущей подписки`;
            }
        }

        if (!data.devices.length) {
            listEl.innerHTML = '<p class="empty-state">Устройства не зафиксированы</p>';
            return;
        }

        // Данные по id — чтобы не таскать пользовательские строки через HTML-атрибуты
        // (escapeHtml не экранирует кавычки — attr-инъекция).
        const devicesById = {};
        data.devices.forEach(d => { devicesById[d.id] = d; });

        listEl.innerHTML = data.devices.map(d => {
            const canRestore = d.status === 'REVOKED';
            const btnLabel = canRestore ? 'Вернуть' : 'Отключить';
            const action = canRestore ? 'restore' : 'revoke';
            // Пользовательское имя — заголовком, железное — подписью.
            const title = d.customName || d.label;
            const hwSub = d.customName ? `${escapeHtml(d.label)}, ` : '';
            return `
                <div class="history-item" data-device-id="${d.id}">
                    <div class="history-item__main">
                        <div class="history-item__plan">${escapeHtml(title)}</div>
                        <div class="history-item__meta">
                            ${hwSub}${deviceStatusLabel(d.status)}, последний раз в сети ${formatDateTime(d.lastSeenEpoch)}
                        </div>
                    </div>
                    <button class="device-action device-rename" data-device-id="${d.id}" aria-label="Переименовать устройство">Имя</button>
                    <button class="device-action" data-device-id="${d.id}" data-device-action="${action}">${btnLabel}</button>
                </div>
            `;
        }).join('');

        listEl.querySelectorAll('.device-action[data-device-action]').forEach(btn => {
            btn.addEventListener('click', async () => {
                haptic('medium');
                btn.setAttribute('disabled', '');
                try {
                    const resp = await fetch(
                        `${API_BASE}/api/devices/${btn.dataset.deviceId}/${btn.dataset.deviceAction}`, {
                            method: 'POST',
                            headers: { 'X-Telegram-Init-Data': tg.initData },
                        });
                    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
                    const result = await resp.json().catch(() => ({}));
                    if (btn.dataset.deviceAction === 'restore' && result.activated === false) {
                        tg.showAlert('Свободных слотов нет. Устройство включится автоматически при освобождении слота.');
                    }
                } catch (e) {
                    console.error('device action failed', e);
                    tg.showAlert('Выполнить не удалось. Повторите попытку.');
                } finally {
                    loadDevices();
                }
            });
        });

        // ✏️ Переименование: инлайн-строка с input под карточкой. Значение сетим через
        // .value (не через HTML) — см. комментарий про attr-инъекцию выше.
        listEl.querySelectorAll('.device-rename').forEach(btn => {
            btn.addEventListener('click', () => {
                haptic('light');
                const item = btn.closest('.history-item');
                const existing = item.nextElementSibling;
                if (existing && existing.classList.contains('device-rename-row')) {
                    existing.remove(); // повторный тап — свернуть
                    return;
                }
                const device = devicesById[btn.dataset.deviceId];
                const row = document.createElement('div');
                row.className = 'device-rename-row';
                row.innerHTML = `
                    <input type="text" maxlength="64" placeholder="Например: телефон мамы">
                    <button class="device-action device-rename-save">Сохранить</button>`;
                item.after(row);
                const input = row.querySelector('input');
                input.value = (device && device.customName) || '';
                input.focus();
                row.querySelector('.device-rename-save').addEventListener('click', async () => {
                    haptic('medium');
                    try {
                        const resp = await fetch(`${API_BASE}/api/devices/${btn.dataset.deviceId}/name`, {
                            method: 'POST',
                            headers: {
                                'Content-Type': 'application/json',
                                'X-Telegram-Init-Data': tg.initData,
                            },
                            body: JSON.stringify({ name: input.value }),
                        });
                        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
                    } catch (e) {
                        console.error('device rename failed', e);
                        tg.showAlert('Переименовать не удалось. Повторите попытку.');
                    }
                    loadDevices();
                });
            });
        });
    } catch (e) {
        console.error('loadDevices failed:', e);
        listEl.innerHTML = '<p class="empty-state">Не удалось загрузить устройства</p>';
    }
}

// «Добавить устройство» (+1 слот до конца подписки): выбор способа оплаты нативным
// tg.showPopup, POST /api/devices/addon, открытие платёжной страницы способом провайдера.
const addonBuyBtn = document.getElementById('devices-addon');
if (addonBuyBtn) {
    addonBuyBtn.addEventListener('click', () => {
        haptic('medium');
        const startAddon = async (provider) => {
            addonBuyBtn.setAttribute('disabled', '');
            try {
                const r = await fetch(`${API_BASE}/api/devices/addon`, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'X-Telegram-Init-Data': tg.initData,
                    },
                    body: JSON.stringify({ provider }),
                });
                const data = await r.json().catch(() => ({}));
                if (!r.ok) throw new Error(data.message || `HTTP ${r.status}`);
                if (provider === 'stars') {
                    tg.openInvoice(data.url, (status) => {
                        if (status === 'paid') tg.showAlert('Оплачено. Слот будет добавлен в течение нескольких секунд.');
                    });
                } else if (provider === 'cryptobot') {
                    openSmart(data.url);
                } else {
                    tg.openLink(data.url);
                }
            } catch (e) {
                console.error('device addon failed', e);
                tg.showAlert(String((e && e.message) || 'Создать платёж не удалось.'));
            } finally {
                addonBuyBtn.removeAttribute('disabled');
            }
        };
        try {
            tg.showPopup({
                title: 'Добавить устройство',
                message: 'Слот действует до конца текущей подписки. Выберите способ оплаты.',
                buttons: [
                    { id: 'yookassa', type: 'default', text: 'Картой' },
                    { id: 'stars', type: 'default', text: 'Stars' },
                    { id: 'cryptobot', type: 'default', text: 'Криптой' },
                ],
            }, (btnId) => { if (btnId) startAddon(btnId); });
        } catch (_) {
            // Вне Telegram (dev preview) showPopup нет
            if (window.confirm('Купить дополнительное устройство картой?')) startAddon('yookassa');
        }
    });
}

// «Сменить ссылку подписки» — выйти со всех устройств. Подтверждение нативным
// tg.showConfirm; после успеха перезагружаем /api/me (новая ссылка в профиле/Home)
// и список устройств (он очищен).
const rotateLinkBtn = document.getElementById('devices-rotate-link');
if (rotateLinkBtn) {
    rotateLinkBtn.addEventListener('click', () => {
        haptic('medium');
        const doRotate = async () => {
            rotateLinkBtn.setAttribute('disabled', '');
            try {
                const r = await fetch(`${API_BASE}/api/devices/rotate-link`, {
                    method: 'POST',
                    headers: { 'X-Telegram-Init-Data': tg.initData },
                });
                const data = await r.json().catch(() => ({}));
                if (!r.ok) throw new Error(data.message || `HTTP ${r.status}`);
                tg.showAlert('Ссылка обновлена. Прежняя больше не работает: добавьте новую ссылку в клиентское приложение на своих устройствах (Профиль → Ваша ссылка).');
                loadMe();
                loadDevices();
            } catch (e) {
                console.error('rotate-link failed', e);
                tg.showAlert(String((e && e.message) || 'Сменить ссылку не удалось. Повторите попытку позже.'));
            } finally {
                rotateLinkBtn.removeAttribute('disabled');
            }
        };
        try {
            tg.showConfirm(
                'Прежняя ссылка перестанет работать на ВСЕХ устройствах, список устройств будет очищен. Продолжить?',
                (ok) => { if (ok) doRotate(); });
        } catch (_) {
            // Вне Telegram (dev preview) showConfirm нет — обычный confirm
            if (window.confirm('Сменить ссылку подписки?')) doRotate();
        }
    });
}

function deviceStatusLabel(s) {
    switch (s) {
        case 'ACTIVE':  return 'активно';
        case 'BLOCKED': return 'сверх лимита';
        case 'REVOKED': return 'отключено';
        default:        return s.toLowerCase();
    }
}

// ====================================================================
//  History screen: /api/payments → список карточек
// ====================================================================
async function loadHistory() {
    const listEl = document.getElementById('history-list');
    listEl.innerHTML = '<p class="empty-state">Загрузка…</p>';

    try {
        const r = await fetch(`${API_BASE}/api/payments`, {
            headers: { 'X-Telegram-Init-Data': tg.initData },
        });
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        const items = await r.json();

        if (!items.length) {
            listEl.innerHTML = '<p class="empty-state">Операций нет</p>';
            return;
        }

        listEl.innerHTML = items.map(p => {
            const amount = Math.round(p.amountKopeks / 100);
            const when = formatDateTime(p.paidAtEpoch || p.createdAtEpoch);
            const statusText = statusLabel(p.status);
            const statusCls = statusClass(p.status);
            return `
                <div class="history-item">
                    <div class="history-item__main">
                        <div class="history-item__plan">${escapeHtml(p.planName || '—')}</div>
                        <div class="history-item__meta">
                            ${when}
                            <span class="status-tag status-tag--${statusCls}">${statusText}</span>
                        </div>
                    </div>
                    <div class="history-item__amount">${amount} ₽</div>
                </div>
            `;
        }).join('');
    } catch (e) {
        console.error('loadHistory failed:', e);
        listEl.innerHTML = '<p class="empty-state">Не удалось загрузить историю</p>';
    }
}

function statusLabel(s) {
    switch (s) {
        case 'SUCCEEDED': return 'оплачено';
        case 'PENDING':   return 'ожидание';
        case 'CANCELED':  return 'отменено';
        case 'FAILED':    return 'ошибка';
        case 'REFUNDED':  return 'возврат';
        default:          return s.toLowerCase();
    }
}
function statusClass(s) {
    switch (s) {
        case 'SUCCEEDED': return 'ok';
        case 'PENDING':   return 'pending';
        case 'CANCELED':
        case 'FAILED':    return 'fail';
        case 'REFUNDED':  return 'refunded';
        default:          return 'pending';
    }
}
function formatDateTime(epoch) {
    if (!epoch) return '—';
    return new Intl.DateTimeFormat('ru-RU', {
        day: 'numeric', month: 'long', year: 'numeric',
        hour: '2-digit', minute: '2-digit',
    }).format(new Date(epoch * 1000));
}

function escapeHtml(s) {
    const div = document.createElement('div');
    div.textContent = s;
    return div.innerHTML;
}

// ====================================================================
//  Referral screen: /api/referral → ссылка + статистика
// ====================================================================
async function loadReferral() {
    const linkEl = document.getElementById('ref-link-value');
    linkEl.textContent = 'Загрузка…';
    try {
        const r = await fetch(`${API_BASE}/api/referral`, {
            headers: { 'X-Telegram-Init-Data': tg.initData },
        });
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        const data = await r.json();

        linkEl.textContent = data.link || '—';
        document.getElementById('ref-invited').textContent = data.invited ?? 0;
        document.getElementById('ref-rewarded').textContent = data.rewarded ?? 0;
        document.getElementById('ref-bonus-days').textContent = data.totalBonusDays ?? 0;
    } catch (e) {
        console.error('loadReferral failed:', e);
        linkEl.textContent = 'Не удалось загрузить';
    }
}

document.getElementById('ref-link-copy').addEventListener('click', async () => {
    const link = document.getElementById('ref-link-value').textContent;
    if (!link || link === '—' || link === 'Загрузка…') return;
    try {
        await navigator.clipboard.writeText(link);
        try { tg.HapticFeedback.notificationOccurred('success'); } catch {}
        tg.showAlert('Ссылка скопирована');
    } catch (e) {
        tg.showAlert('Не удалось скопировать');
    }
});

// ====================================================================
//  Autorenew screen: /api/autorenew POST → toggle
// ====================================================================
function renderAutorenewScreen(state) {
    const heading = document.getElementById('autorenew-heading-status');
    const info = document.getElementById('autorenew-info');
    const btnLabel = document.getElementById('autorenew-toggle-label');
    const btn = document.getElementById('autorenew-toggle-btn');

    if (!state.autorenewAvailable) {
        heading.textContent = 'карта не привязана';
        info.textContent = 'Для подключения автопродления оплатите подписку с опцией «С автопродлением». Карта будет сохранена, дальнейшие продления выполняются автоматически.';
        btnLabel.textContent = 'Недоступно';
        btn.setAttribute('disabled', '');
    } else if (state.autorenewEnabled) {
        heading.textContent = 'включено';
        info.textContent = 'Списание выполняется за 24 часа до истечения подписки с привязанной карты. Уведомление придёт в чат.';
        btnLabel.textContent = 'Отключить';
        btn.removeAttribute('disabled');
    } else {
        heading.textContent = 'выключено';
        info.textContent = 'Автоматическое продление отключено. Карта привязана, включить можно в любой момент.';
        btnLabel.textContent = 'Включить';
        btn.removeAttribute('disabled');
    }
    btn.dataset.currentEnabled = state.autorenewEnabled ? '1' : '0';
    // Кнопка «Отключить» не должна выглядеть как главное действие экрана: зелёная
    // заливка приглашает нажать, а это отказ от услуги. Выключение оформлено как
    // спокойная вторичная кнопка (CSS: .btn-primary.btn-quiet).
    btn.classList.toggle('btn-quiet', !!state.autorenewEnabled);
    renderAutorenewFacts(state);
}

/**
 * Что спишется, когда и с какой подписки — те же цифры, что показывает бот в /autorenew.
 *
 * Раньше экран знал ровно одно слово, «включено»: ни суммы, ни даты, ни номера подписки.
 * Человек с двумя подписками не мог узнать здесь ничего из того, ради чего сюда заходят.
 */
function renderAutorenewFacts(state) {
    const section = document.getElementById('autorenew-facts-section');
    const factsEl = document.getElementById('autorenew-facts');
    const warnEl = document.getElementById('autorenew-warn');
    if (!section || !factsEl || !warnEl) return;

    const info = state && state.autorenewInfo;
    // Показываем факты только когда списание реально произойдёт. При выключенном
    // автопродлении сумма и дата — это предсказание, которое не сбудется, и на экране оно
    // читается как «с меня всё равно спишут».
    if (!info || !info.willCharge) {
        section.hidden = true;
        warnEl.hidden = true;
        return;
    }

    const rows = [];
    if (info.targetSeq > 0 && ((state.subscriptions || []).length > 1)) {
        rows.push(['Продлевается', `Подписка #${info.targetSeq}`]);
    }
    if (info.planGone) {
        rows.push(['Тариф', 'снят с продажи — продление только вручную']);
    } else if (info.planName) {
        rows.push(['Тариф', info.planName + (info.tierName ? `, ${info.tierName}` : '')]);
    }
    if (info.amountRub > 0) rows.push(['Сумма', `${info.amountRub} ₽`]);
    if (info.nextChargeEpoch) rows.push(['Спишется', formatDate(info.nextChargeEpoch)]);

    factsEl.innerHTML = rows.map(([k, v]) => `
        <div class="facts__row">
            <span class="facts__key">${escapeHtml(k)}</span>
            <span class="facts__val">${escapeHtml(v)}</span>
        </div>
    `).join('');
    section.hidden = rows.length === 0;

    // Предупреждения о смене ступени здесь больше нет и быть не может: списание берёт ступень
    // самой продлеваемой подписки, поэтому число устройств после продления не меняется.
    const warnings = [];
    if (info.lastFailureEpoch) {
        warnings.push(`Последняя попытка списания не удалась ${formatDate(info.lastFailureEpoch)}. `
            + 'Вероятная причина — отказ карты.');
    }
    warnEl.textContent = warnings.join(' ');
    warnEl.hidden = warnings.length === 0;
}

async function loadAutorenew() {
    // Состояние берём из кэшированного /api/me, чтобы не делать лишний запрос
    if (window.__meData) {
        renderAutorenewScreen(window.__meData);
        return;
    }
    renderAutorenewScreen({ autorenewAvailable: false, autorenewEnabled: false });
}

document.getElementById('autorenew-toggle-btn').addEventListener('click', async () => {
    const btn = document.getElementById('autorenew-toggle-btn');
    if (btn.hasAttribute('disabled')) return;
    haptic('medium');

    const currentlyEnabled = btn.dataset.currentEnabled === '1';
    const wantEnable = !currentlyEnabled;

    btn.setAttribute('disabled', '');
    try {
        const r = await fetch(`${API_BASE}/api/autorenew`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-Telegram-Init-Data': tg.initData,
            },
            body: JSON.stringify({ enabled: wantEnable }),
        });
        const data = await r.json().catch(() => ({}));
        if (!r.ok) throw new Error(data.message || data.error || `HTTP ${r.status}`);
        // Обновляем кэш и перерисовываем
        if (window.__meData) {
            window.__meData.autorenewEnabled = data.autorenewEnabled;
            window.__meData.autorenewAvailable = data.autorenewAvailable;
        }
        renderAutorenewScreen(window.__meData || data);
        // Дата, сумма и пометка «продлевается» в списке подписок зависят от флага, а ответ
        // тоггла их не содержит — пересчитывает их сервер. Досчитывать на фронте значило бы
        // завести второе правило рядом с тем, по которому реально списывают деньги.
        await loadMe();
        renderAutorenewScreen(window.__meData || data);
    } catch (e) {
        console.error('toggle autorenew failed:', e);
        tg.showAlert('Выполнить не удалось. ' + (e.message || ''));
    } finally {
        btn.removeAttribute('disabled');
    }
});

// ====================================================================
//  Email: capture-баннер на Home + sub-screen в Профиле + save/delete
// ====================================================================
//
// Flow:
//   - /api/me возвращает {email, emailVerified}
//   - Если email пуст и юзер не закрыл баннер — на Home сверху висит email-banner
//   - В Профиле всегда виден menu-item «Email» с текущим адресом или «не указан»
//   - По тапу — экран email-settings с input + Сохранить/Удалить
//
// Баннер dismiss хранится в localStorage. Это персистентно между сессиями (юзер
// один раз закрыл — мы не приставаем), но если он позже сам введёт email — баннер
// и так больше не появится (условие на data.email).

const EMAIL_BANNER_DISMISS_KEY = 'orchestra_email_banner_dismissed';

function isEmailBannerDismissed() {
    try { return localStorage.getItem(EMAIL_BANNER_DISMISS_KEY) === '1'; }
    catch { return false; }
}

function dismissEmailBanner() {
    try { localStorage.setItem(EMAIL_BANNER_DISMISS_KEY, '1'); } catch {}
}

function renderEmailUi(data) {
    const banner = document.getElementById('email-banner');
    const profileStatus = document.getElementById('profile-email-status');
    const email = (data && data.email) || '';
    const verified = !!(data && data.emailVerified);

    if (profileStatus) {
        if (email) {
            profileStatus.textContent = verified ? `${email}, подтверждён` : email;
        } else {
            profileStatus.textContent = 'не указан';
        }
    }

    if (banner) {
        // Показываем только когда email пуст, баннер не закрыт, и юзер реально в Telegram
        // (в preview без user тоже норм — applyDemoData передаёт email='').
        const shouldShow = !email && !isEmailBannerDismissed();
        banner.hidden = !shouldShow;
    }
}

function renderEmailScreen(data) {
    const headingStatus = document.getElementById('email-heading-status');
    const input = document.getElementById('email-input');
    const deleteBtn = document.getElementById('email-delete-btn');
    const result = document.getElementById('email-result');
    const consentCheckbox = document.getElementById('email-consent-checkbox');
    const offerLink = document.getElementById('email-consent-offer-link');
    const email = (data && data.email) || '';
    const verified = !!(data && data.emailVerified);

    if (headingStatus) {
        if (email && verified) headingStatus.textContent = 'подтверждён';
        else if (email) headingStatus.textContent = 'указан';
        else headingStatus.textContent = 'не указан';
    }
    if (input) input.value = email;
    if (deleteBtn) deleteBtn.hidden = !email;
    // Magic-link кнопка «Войти в кабинет через браузер» — только при сохранённом email
    const sendLinkBtn = document.getElementById('email-send-link-btn');
    const sendLinkResult = document.getElementById('email-send-link-result');
    if (sendLinkBtn) sendLinkBtn.hidden = !email;
    if (sendLinkResult) {
        sendLinkResult.hidden = true;
        sendLinkResult.textContent = '';
        sendLinkResult.classList.remove('email-result--ok', 'email-result--err');
    }
    if (result) {
        result.hidden = true;
        result.textContent = '';
        result.classList.remove('email-result--ok', 'email-result--err');
    }
    // Сброс чекбокса при ре-рендере экрана. Видимость управляется updateEmailSaveButtonState
    // на основании того, отличается ли input от сохранённого значения.
    if (consentCheckbox) consentCheckbox.checked = false;
    if (offerLink) {
        // Если offerUrl не задан — оставляем «оферта» как текст без ссылки,
        // чтобы не было битых href #. Когда URL задан — proper href.
        const url = (appConfig && appConfig.offerUrl) || '';
        if (url) {
            offerLink.setAttribute('href', url);
            offerLink.style.pointerEvents = '';
        } else {
            offerLink.removeAttribute('href');
            offerLink.style.pointerEvents = 'none';
        }
    }
    updateEmailSaveButtonState();
}

/**
 * Управляет видимостью чекбокса consent и состоянием disabled кнопки «Сохранить»
 * относительно текущего сохранённого email (window.__meData.email).
 * <p>
 * Логика:
 *   - input.value === saved email → ничего не изменилось, кнопка disabled,
 *     чекбокс скрыт (нет смысла переподтверждать на тот же адрес).
 *   - input.value !== saved → юзер вводит новый или меняет адрес: чекбокс
 *     виден (требуем согласие на конкретный адрес, см. 152-ФЗ ст.9). Кнопка
 *     enabled только когда чекбокс отмечен.
 * <p>
 * Вызывается из renderEmailScreen, по 'input' на текстовом поле и по 'change'
 * на чекбоксе.
 */
function updateEmailSaveButtonState() {
    const btn = document.getElementById('email-save-btn');
    const input = document.getElementById('email-input');
    const consentBlock = document.getElementById('email-consent');
    const consentCheckbox = document.getElementById('email-consent-checkbox');
    if (!btn || !input) return;

    const saved = ((window.__meData && window.__meData.email) || '').trim().toLowerCase();
    const value = (input.value || '').trim().toLowerCase();
    const hasText = value.length > 0;
    const changed = value !== saved;

    // Видимость чекбокса. Сбрасываем чек на снятый когда блок скрываем,
    // чтобы при следующем изменении значения юзеру пришлось согласиться заново.
    if (consentBlock) {
        consentBlock.hidden = !changed;
        if (!changed && consentCheckbox) consentCheckbox.checked = false;
    }
    const consentNeeded = consentBlock && !consentBlock.hidden;
    const consentGiven = !consentNeeded || (consentCheckbox && consentCheckbox.checked);
    const canSave = hasText && changed && consentGiven;
    if (canSave) btn.removeAttribute('disabled');
    else btn.setAttribute('disabled', '');
}

function loadEmailScreen() {
    // Берём из кэша /api/me — отдельный GET не нужен, состояние свежее
    renderEmailScreen(window.__meData || {});
}

async function saveEmail(rawEmail) {
    // consent=true прибиваем гвоздями: фронт по UI-логике не даёт жать «Сохранить»
    // без галочки чекбокса, а на edge case'ы (юзер обошёл UI) backend ответит
    // 400 consent_required и тогда мы покажем сообщение об ошибке.
    const r = await fetch(`${API_BASE}/api/me/email`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-Telegram-Init-Data': tg.initData,
        },
        body: JSON.stringify({ email: rawEmail, consent: true }),
    });
    const data = await r.json().catch(() => ({}));
    if (!r.ok) {
        const err = new Error(data.message || data.error || `HTTP ${r.status}`);
        err.status = r.status;
        throw err;
    }
    return data;
}

async function deleteEmail() {
    const r = await fetch(`${API_BASE}/api/me/email`, {
        method: 'DELETE',
        headers: { 'X-Telegram-Init-Data': tg.initData },
    });
    const data = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(data.message || data.error || `HTTP ${r.status}`);
    return data;
}

/**
 * Переводит кнопку в loading-state: меняет текст label, ставит .is-busy (CSS
 * добавит pulsing-shine), вешает disabled. Сетевой запрос к проду занимает
 * 200-800мс (NL ↔ RU + TLS + Spring); без этого фидбэка юзер уверен «я нажал,
 * ничего не происходит» и кликает ещё несколько раз.
 * <p>
 * Возвращаемая restore-функция НЕ трогает {@code disabled}: после запроса
 * disabled-state должен определять caller — renderEmailScreen может оставить
 * кнопку disabled после успешного save (input == saved), или enabled на ошибке.
 *
 * @param {HTMLButtonElement} btn кнопка
 * @param {HTMLElement|null} labelEl span с текстом (если есть)
 * @param {string} busyText текст на время запроса (например «Сохраняем…»)
 * @returns {() => void} функция-restore, возвращает оригинальный текст и снимает is-busy
 */
function setButtonBusy(btn, labelEl, busyText) {
    const originalText = labelEl ? labelEl.textContent : '';
    if (labelEl) labelEl.textContent = busyText;
    btn.classList.add('is-busy');
    btn.setAttribute('disabled', '');
    return () => {
        if (labelEl) labelEl.textContent = originalText;
        btn.classList.remove('is-busy');
        // disabled намеренно не снимаем — caller сам решит через updateEmailSaveButtonState
        // или ручным removeAttribute (для delete-кнопки, где state управляется hidden, а не disabled).
    };
}

document.getElementById('email-save-btn').addEventListener('click', async () => {
    const btn = document.getElementById('email-save-btn');
    if (btn.hasAttribute('disabled')) return;
    const input = document.getElementById('email-input');
    const result = document.getElementById('email-result');
    const labelEl = document.getElementById('email-save-label');
    const value = (input.value || '').trim();
    if (!value) {
        result.textContent = 'Введите email';
        result.classList.remove('email-result--ok');
        result.classList.add('email-result--err');
        result.hidden = false;
        return;
    }
    haptic('medium');
    // Сразу показываем нейтральное «Сохраняем…» под полем — даже если SMTP/сеть
    // тормозит, юзер видит что запрос ушёл и не жмёт кнопку повторно.
    result.textContent = 'Сохраняем…';
    result.classList.remove('email-result--ok', 'email-result--err');
    result.hidden = false;
    const restore = setButtonBusy(btn, labelEl, 'Сохраняем…');
    try {
        const data = await saveEmail(value);
        // Обновляем кэш — баннер на Home и menu-item в Профиле подхватятся при renderEmailUi
        if (window.__meData) {
            window.__meData.email = data.email;
            window.__meData.emailVerified = data.emailVerified;
        }
        renderEmailScreen(data);
        renderEmailUi(data);
        result.textContent = 'Сохранено ✓';
        result.classList.remove('email-result--err');
        result.classList.add('email-result--ok');
        result.hidden = false;
        try { tg.HapticFeedback.notificationOccurred('success'); } catch {}
    } catch (e) {
        console.error('save email failed:', e);
        result.textContent = e.message || 'Сохранить не удалось';
        result.classList.remove('email-result--ok');
        result.classList.add('email-result--err');
        result.hidden = false;
        try { tg.HapticFeedback.notificationOccurred('error'); } catch {}
    } finally {
        restore();
        // disabled-state расчитываем заново: при success — input == saved → disabled;
        // при error — input != saved → enabled (юзер может повторить).
        updateEmailSaveButtonState();
    }
});

// Magic-link: «Войти в кабинет через браузер». Шлёт POST /api/me/email/send-link,
// после чего юзер откроет письмо в desktop-браузере и попадёт в /account/ через
// signed cookie. Rate-limit на бэке (1/мин, 5/час); inline-сообщение показывает
// результат прямо под кнопкой.
document.getElementById('email-send-link-btn').addEventListener('click', async () => {
    const btn = document.getElementById('email-send-link-btn');
    if (btn.hasAttribute('disabled')) return;
    const labelEl = document.getElementById('email-send-link-label');
    const resultEl = document.getElementById('email-send-link-result');
    haptic('medium');
    resultEl.textContent = 'Отправляем письмо…';
    resultEl.classList.remove('email-result--ok', 'email-result--err');
    resultEl.hidden = false;
    const restore = setButtonBusy(btn, labelEl, 'Отправляем…');
    try {
        const r = await fetch(`${API_BASE}/api/me/email/send-link`, {
            method: 'POST',
            headers: { 'X-Telegram-Init-Data': tg.initData },
        });
        const data = await r.json().catch(() => ({}));
        if (!r.ok) {
            const err = new Error(data.message || data.error || `HTTP ${r.status}`);
            err.status = r.status;
            throw err;
        }
        resultEl.textContent = data.message || 'Письмо отправлено';
        resultEl.classList.remove('email-result--err');
        resultEl.classList.add('email-result--ok');
        resultEl.hidden = false;
        try { tg.HapticFeedback.notificationOccurred('success'); } catch {}
    } catch (e) {
        console.error('send magic-link failed:', e);
        resultEl.textContent = e.message || 'Отправить письмо не удалось';
        resultEl.classList.remove('email-result--ok');
        resultEl.classList.add('email-result--err');
        resultEl.hidden = false;
        try { tg.HapticFeedback.notificationOccurred('error'); } catch {}
    } finally {
        restore();
        btn.removeAttribute('disabled');
    }
});

document.getElementById('email-delete-btn').addEventListener('click', async () => {
    const btn = document.getElementById('email-delete-btn');
    if (btn.hasAttribute('disabled')) return;
    haptic('medium');
    // Label у delete-кнопки — последний <span> (после svg).
    const labelEl = btn.querySelector('span:last-child');
    const restore = setButtonBusy(btn, labelEl, 'Удаляем…');
    try {
        const data = await deleteEmail();
        if (window.__meData) {
            window.__meData.email = '';
            window.__meData.emailVerified = false;
        }
        renderEmailScreen(data);
        renderEmailUi(data);
        try { tg.HapticFeedback.notificationOccurred('success'); } catch {}
    } catch (e) {
        console.error('delete email failed:', e);
        tg.showAlert('Выполнить не удалось. ' + (e.message || ''));
    } finally {
        restore();
        // Delete-кнопка управляется через hidden (renderEmailScreen ставит на основании
        // наличия email), но disabled от setButtonBusy надо снять явно — на success
        // кнопка скроется через renderEmailScreen, на error должна остаться кликабельной.
        btn.removeAttribute('disabled');
    }
});

// Email-баннер на Home: CTA открывает экран Email, крестик закрывает баннер.
document.getElementById('email-banner-cta').addEventListener('click', () => {
    haptic('light');
    showScreen('email-settings');
    loadEmailScreen();
});

document.getElementById('email-banner-close').addEventListener('click', () => {
    haptic('light');
    dismissEmailBanner();
    const banner = document.getElementById('email-banner');
    if (banner) banner.hidden = true;
});

// Динамическое включение/выключение кнопки «Сохранить» по состоянию input + чекбокса
document.getElementById('email-input').addEventListener('input', updateEmailSaveButtonState);
document.getElementById('email-consent-checkbox').addEventListener('change', updateEmailSaveButtonState);

// Ссылка на оферту в consent-блоке — открываем через openSmart, чтобы не вылетать из
// Telegram (default-навигация по <a href> закроет Mini App).
document.getElementById('email-consent-offer-link').addEventListener('click', (e) => {
    e.preventDefault();
    const url = (appConfig && appConfig.offerUrl) || '';
    if (url) {
        haptic('light');
        openSmart(url);
    } else {
        tg.showAlert('Публичная оферта в подготовке.');
    }
});

// ====================================================================
//  Support: FAQ / контакт / оферта
// ====================================================================
document.getElementById('support-faq').addEventListener('click', () => {
    if (!appConfig.guideUrl) return tg.showAlert('Раздел в подготовке.');
    tg.openLink(appConfig.guideUrl);
});

document.getElementById('support-contact').addEventListener('click', () => {
    haptic('light');
    // Открываем чат с @orchestra_support через Telegram-native deep-link.
    // tg.openTelegramLink — не вылетает из Telegram (в отличие от openLink),
    // открывает профиль саппорта прямо в той же сессии.
    tg.openTelegramLink('https://t.me/orchestra_support');
});

document.getElementById('support-terms').addEventListener('click', () => {
    if (!appConfig.offerUrl) return tg.showAlert('Публичная оферта в подготовке.');
    tg.openLink(appConfig.offerUrl);
});

// Канал новостей — кнопка скрыта по умолчанию, JS снимает hidden когда backend
// отдал config.newsChannelUrl. Открываем через openTelegramLink (это t.me-ссылка),
// чтобы Telegram открыл канал нативно и Orchestra Mini App не закрылся.
const supportNewsBtn = document.getElementById('support-news');
if (supportNewsBtn) {
    supportNewsBtn.addEventListener('click', () => {
        haptic('light');
        if (!appConfig.newsChannelUrl) return;
        tg.openTelegramLink(appConfig.newsChannelUrl);
    });
}

// ====================================================================
//  Статус серверов: /api/servers → список нод (Активен / Недоступен)
// ====================================================================
//
// Бэкенд считает доступность TCP-коннектом к каждой ноде и кеширует на 30с
// (см. ServerStatusService). Фронт только рисует список. Грузим лениво —
// при каждом открытии подэкрана, как loadHistory/loadReferral.
async function loadServers() {
    const listEl = document.getElementById('server-list');
    listEl.innerHTML = '<p class="empty-state">Загрузка…</p>';
    try {
        const r = await fetch(`${API_BASE}/api/servers`, {
            headers: { 'X-Telegram-Init-Data': tg.initData },
        });
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        const items = await r.json();

        if (!items.length) {
            listEl.innerHTML = '<p class="empty-state">Список серверов пуст</p>';
            return;
        }

        listEl.innerHTML = items.map(s => {
            // Три состояния, а не два: «нет данных» — это мониторинг не ответил, и рисовать
            // вместо него зелёное (или красное) значит выдумывать за него ответ.
            const unknown = s.known === false;
            const cls = unknown ? 'unknown' : (s.online ? 'online' : 'offline');
            const label = unknown ? 'Нет данных' : (s.online ? 'Активен' : 'Недоступен');
            // Аптайм за сутки: нода может отвечать сейчас и при этом падать по десять раз
            // в день — по мгновенному снимку сервер не выберешь.
            const meta = [];
            if (!unknown && typeof s.uptime24 === 'number') {
                meta.push(`за сутки ${s.uptime24.toFixed(1)}%`);
            }
            if (!unknown && s.online && typeof s.pingMs === 'number') {
                meta.push(`${Math.round(s.pingMs)} мс`);
            }
            return `
                <div class="server-item">
                    <span class="server-item__flag">${escapeHtml(s.flag || '')}</span>
                    <span class="server-item__body">
                        <b class="server-item__name">${escapeHtml(s.name || '—')}</b>
                        ${meta.length ? `<small class="server-item__meta">${escapeHtml(meta.join(', '))}</small>` : ''}
                    </span>
                    <span class="server-item__status server-item__status--${cls}">
                        <span class="server-item__dot server-item__dot--${cls}"></span>
                        ${label}
                    </span>
                </div>
            `;
        }).join('');
        // Источник — Uptime Kuma, та же страница, что и публичный статус. Подпись объясняет,
        // почему цифры на экране совпадают с ней, а не считаются приложением отдельно.
        if (items.some(s => s.known === false)) {
            listEl.insertAdjacentHTML('beforeend',
                '<p class="empty-state">По части серверов мониторинг сейчас не отвечает — '
                + 'это не значит, что они не работают.</p>');
        }
    } catch (e) {
        console.error('loadServers failed:', e);
        listEl.innerHTML = '<p class="empty-state">Не удалось загрузить статус серверов</p>';
    }
}

document.getElementById('support-status').addEventListener('click', () => {
    haptic('light');
    showScreen('servers');
    loadServers();
});

document.getElementById('server-refresh').addEventListener('click', () => {
    haptic('light');
    loadServers();
});

// ====================================================================
//  Сервисы — 2 marquee-rows с brand-логотипами
// ====================================================================
//
// Sets между TOP и BOTTOM не пересекаются — юзер видит больше уникальных
// сервисов за один scroll. Не загрузившаяся иконка прячется (hideBrokenIcons),
// текст сервиса при этом остаётся читаемым.

// Пункты бегущих строк. Иконок нет намеренно: набор меняется вместе с тарифом,
// и картинка к каждому пункту устаревала бы быстрее текста.
const SERVICES_TOP = [
    { name: 'Оплата картой' },
    { name: 'Оплата криптовалютой' },
    { name: 'Telegram Stars' },
    { name: 'Автопродление' },
    { name: 'Промокоды' },
    { name: 'Реферальные бонусы' },
    { name: '7 дней бесплатно' },
    { name: 'Возврат по запросу' },
];

const SERVICES_BOTTOM = [
    { name: 'До 10 устройств' },
    { name: 'Семейные тарифы' },
    { name: 'Докупка устройств' },
    { name: 'Смена ссылки в один тап' },
    { name: 'Личный кабинет' },
    { name: 'Вход по ссылке из письма' },
    { name: 'Уведомления об истечении' },
    { name: 'Заморозка второй подписки' },
];

function renderServiceChip(s) {
    // НЕ ставим loading="lazy" — marquee бесконечно прокручивает обе копии,
    // и lazy-load иконок второй копии вызывает reflow при появлении в viewport.
    // 36 SVG-иконок суммарно <50KB — eager-load дешевле jitter'а в анимации.
    //
    // Прятать битую иконку через inline onerror нельзя: CSP Mini App
    // (SecurityHeadersFilter.applyMiniAppCsp) даёт script-src без 'unsafe-inline',
    // а inline-обработчик — это inline-скрипт, он не выполнится. Тот же эффект
    // навешивается слушателем в hideBrokenIcons().
    const icon = s.icon
        ? `<img class="service-chip__icon" src="${s.icon}" alt="">`
        : '';
    return `<div class="service-chip">
        ${icon}
        <span>${s.name}</span>
    </div>`;
}

// Прячет иконку, которая не загрузилась (404 или обрезанный ответ прокси).
// Слушаем на контейнере в фазе ПЕРЕХВАТА: событие error на <img> не всплывает,
// поэтому обычный делегированный слушатель его не увидит.
function hideBrokenIcons(track) {
    if (track.dataset.iconErrHandler === '1') return; // renderServices зовут повторно
    track.dataset.iconErrHandler = '1';
    track.addEventListener('error', (e) => {
        const el = e.target;
        if (el && el.classList && el.classList.contains('service-chip__icon')) {
            el.style.display = 'none';
        }
    }, true);
}

/**
 * Две полки логотипов на главном экране: верхняя едет влево, нижняя вправо.
 *
 * Скорости снижены вдвое против первой версии (20 и 17 px/с вместо 30 и 26).
 * Смысл движения — показать, что сервисов много и список не помещается; на
 * прежней скорости название уезжало раньше, чем его успевали дочитать, и полка
 * работала против этого смысла.
 *
 * При системном «меньше движения» марка не запускается: список рендерится один
 * раз (без второй копии, она нужна только петле) и листается пальцем.
 */
function renderServices() {
    const topTrack = document.getElementById('services-track-top');
    const bottomTrack = document.getElementById('services-track-bottom');
    if (!topTrack || !bottomTrack) return;
    const topHtml = SERVICES_TOP.map(renderServiceChip).join('');
    const bottomHtml = SERVICES_BOTTOM.map(renderServiceChip).join('');
    // Слушатель вешаем ДО вставки разметки: иначе error по уже стартовавшей
    // загрузке успеет проскочить мимо него.
    hideBrokenIcons(topTrack);
    hideBrokenIcons(bottomTrack);

    if (REDUCED_MOTION) {
        topTrack.innerHTML = topHtml;
        bottomTrack.innerHTML = bottomHtml;
        [topTrack, bottomTrack].forEach(t => t.parentElement
            && t.parentElement.classList.add('services-marquee--manual'));
        // Нижнюю полку сдвигаем: две выровненные по левому краю строки читаются
        // как неподвижная таблица, сдвиг показывает, что их можно листать.
        requestAnimationFrame(() => {
            if (bottomTrack.parentElement) bottomTrack.parentElement.scrollLeft = 56;
        });
        return;
    }

    // Дублируем список → wrap-around в RAF-марке даёт бесшовную петлю
    topTrack.innerHTML = topHtml + topHtml;
    bottomTrack.innerHTML = bottomHtml + bottomHtml;
    startMarquee(topTrack, 20, 'left');
    startMarquee(bottomTrack, 17, 'right');
}

// ====================================================================
//  JS-RAF marquee — pixel-precise scroll для services-marquee
// ====================================================================
//
// Почему не CSS animation: на iOS Safari чисто-CSS animation давала два
// артефакта — (1) sub-pixel jitter каждые 20-40s от translate%-rounding,
// (2) intermittent «freeze» после reload Mini App (animation иногда не
// стартует корректно при page-load). RAF + integer pixel position решает
// оба — мы полностью владеем positioning'ом и обходим оптимизации Safari.
function startMarquee(track, speedPxPerSec, direction) {
    if (!track) return;
    let halfWidth = 0;
    let position = 0;
    let lastTime = 0;

    function measure() {
        // Дублированный content → scrollWidth = 2 копии. Half = одна копия.
        // Wrap'аемся когда уехали на halfWidth — визуально незаметно т.к. копии идентичны.
        halfWidth = track.scrollWidth / 2;
    }

    measure();
    if (direction === 'right') position = -halfWidth;

    function tick(now) {
        if (!lastTime) lastTime = now;
        const dt = (now - lastTime) / 1000;
        lastTime = now;

        // Track ещё не имеет ширины (картинки не загружены / DOM не разложен) — ждём
        if (halfWidth <= 0) {
            measure();
            requestAnimationFrame(tick);
            return;
        }

        if (direction === 'left') {
            position -= speedPxPerSec * dt;
            if (position <= -halfWidth) position += halfWidth;
        } else {
            position += speedPxPerSec * dt;
            if (position >= 0) position -= halfWidth;
        }

        track.style.transform = `translate3d(${position.toFixed(2)}px, 0, 0)`;
        requestAnimationFrame(tick);
    }

    requestAnimationFrame(tick);

    // Re-measure при resize и после загрузки иконок (intrinsic size SVG)
    let resizeTimer;
    window.addEventListener('resize', () => {
        clearTimeout(resizeTimer);
        resizeTimer = setTimeout(measure, 200);
    });
    track.querySelectorAll('img').forEach(img => {
        if (!img.complete) {
            img.addEventListener('load', measure, { once: true });
        }
    });
}

// ====================================================================
//  Тарифы (Home). Живой источник цен — GET /api/pricing (application.yml:
//  app.plans + app.device-tiers), загружается в loadPricing(). Списки ниже —
//  ФОЛЛБЭК на случай недоступного API (открытие вне Telegram, сеть): витрина
//  работает, а цену при оплате сервер всё равно пересчитывает сам.
//  basePrice — цена БАЗОВОЙ ступени «2 устройства» (d2, ×1.0 — без округления);
//  остальные ступени = basePrice × множитель с округлением до 5 ₽ — ФОРМУЛА ДОЛЖНА
//  СОВПАДАТЬ с AppConfig.tierPriceKopeks.
// ====================================================================
let TARIFFS = [
    { id: '1m',  title: '1 месяц',    basePrice: 179,  months: 1  },
    { id: '3m',  title: '3 месяца',   basePrice: 480,  months: 3  },
    { id: '6m',  title: '6 месяцев',  basePrice: 840,  months: 6, popular: true  },
    { id: '12m', title: '12 месяцев', basePrice: 1440, months: 12 },
];

let DEVICE_TIERS = [
    { id: 'd1',  chip: '1 устройство',  devices: 1,  mult: 0.555 },
    { id: 'd2',  chip: '2 устройства',  devices: 2,  mult: 1.0, popular: true },
    { id: 'd3',  chip: '3 устройства',  devices: 3,  mult: 1.2 },
    { id: 'f5',  chip: 'Семейная ×5',   devices: 5,  mult: 1.8, family: true },
    { id: 'f10', chip: 'Семейная ×10',  devices: 10, mult: 2.8, family: true },
];
let selectedTierId = (DEVICE_TIERS.find(t => t.popular) || DEVICE_TIERS[0]).id;

// Лейбл чипса ступени: не-семейные — «N устройств(а)», семейные — «Семейная ×N».
function tierChip(devices, family) {
    if (family) return `Семейная ×${devices}`;
    const word = devices === 1 ? 'устройство' : (devices <= 4 ? 'устройства' : 'устройств');
    return `${devices} ${word}`;
}

/**
 * Подтягивает прайс с бэка и перерисовывает витрину. При любой ошибке молча
 * остаёмся на фоллбэк-хардкоде выше. Выбор юзера (срок/ступень) сохраняется,
 * если соответствующий id существует и в живом прайсе.
 */
async function loadPricing() {
    try {
        const r = await fetch(`${API_BASE}/api/pricing`, {
            headers: { 'X-Telegram-Init-Data': tg.initData },
        });
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        const data = await r.json();
        if (!Array.isArray(data.plans) || !data.plans.length
            || !Array.isArray(data.deviceTiers) || !data.deviceTiers.length) return;

        TARIFFS = data.plans.map(p => ({
            id: p.id,
            title: p.title,
            basePrice: Math.round(p.priceKopeks / 100),
            months: Math.max(1, Math.round(p.days / 30)),
            popular: !!p.popular,
        }));
        DEVICE_TIERS = data.deviceTiers.map(t => ({
            id: t.id,
            chip: tierChip(t.devices, t.family),
            devices: t.devices,
            mult: t.multiplier,
            popular: !!t.popular,
            family: !!t.family,
        }));

        if (!DEVICE_TIERS.some(t => t.id === selectedTierId)) {
            selectedTierId = (DEVICE_TIERS.find(t => t.popular) || DEVICE_TIERS[0]).id;
        }
        if (selectedTariffId && !TARIFFS.some(t => t.id === selectedTariffId)) {
            selectedTariffId = null;
            buyBtnEl.setAttribute('disabled', '');
            updateBuyBtnLabel();
        }
        renderDeviceTiers();
        renderTariffs();
        if (selectedTariffId) selectTariff(selectedTariffId);
    } catch (e) {
        // Сети нет / вне Telegram (401) — витрина остаётся на фоллбэк-ценах.
    }
}

function currentTier() {
    return DEVICE_TIERS.find(t => t.id === selectedTierId) || DEVICE_TIERS[0];
}

/** Цена ступени: ×1.0 → базовая как есть (179 ≠ 180), иначе округление до 5 ₽
 *  (= AppConfig.tierPriceKopeks — менять синхронно). */
function tierPriceRub(basePrice, tier) {
    if (tier.mult === 1.0) return basePrice;
    return Math.round(basePrice * tier.mult / 5) * 5;
}

/**
 * Витринная карточка тарифа под ТЕКУЩУЮ ступень устройств: price — итоговая,
 * perMonth — за месяц.
 *
 * Раньше здесь считалась ещё originalPrice — «цена без скидки» как месячный тариф
 * × число месяцев. По ней рисовались зачёркнутая цена и «−22%» на карточке, в
 * кнопке и в окне оплаты. Такой цены не существует: по ней никто никогда не платил,
 * это способ подачи, а не факт. Витрина показывает то, что человек заплатит, и
 * цену за месяц — выгода длинного срока видна сравнением карточек между собой.
 */
function tariffView(t) {
    const tier = currentTier();
    const price = tierPriceRub(t.basePrice, tier);
    const perMonth = Math.round(price / t.months);
    return { ...t, price, perMonth };
}

let selectedTariffId = null;

const tariffsEl = document.getElementById('tariffs');
const buyBtnEl = document.getElementById('buy-btn');
const buyPriceNewEl = document.getElementById('buy-btn-price-new');

// ====================================================================
//  Ступени устройств — чипсы над тарифами. Выбор пересчитывает цены карточек.
// ====================================================================
const deviceTiersEl = document.getElementById('device-tiers');

function renderDeviceTiers() {
    if (!deviceTiersEl) return;
    // В чипсе — только число. Полные подписи («1 устройство», «Семейная ×5») занимали
    // 148px в три ряда, то есть треть блока покупки уходила на пять слов. Слово
    // «устройств» стоит один раз в заголовке строки, а полное имя выбранной ступени
    // показывается справа от него (renderTierName) — так оно всегда на экране, но
    // не повторяется пять раз.
    // Эмодзи-семья и звёздочка «популярное» убраны: цветной глиф перетягивал взгляд
    // с самого числа. Популярность — точка в углу чипса (CSS, [data-popular]).
    deviceTiersEl.innerHTML = DEVICE_TIERS.map(t => `
        <button class="device-tier-chip${t.id === selectedTierId ? ' selected' : ''}"
                data-tier-id="${t.id}"${t.popular ? ' data-popular="1"' : ''}
                aria-label="${t.chip}${t.popular ? ', выбирают чаще всего' : ''}">${t.devices}</button>
    `).join('');
    deviceTiersEl.querySelectorAll('.device-tier-chip').forEach(chip => {
        chip.addEventListener('click', () => {
            haptic('light');
            selectDeviceTier(chip.dataset.tierId);
        });
    });
    renderTierName();
}

/** Полное имя выбранной ступени справа от заголовка «Устройств». */
function renderTierName() {
    const el = document.getElementById('tier-name');
    if (el) el.textContent = currentTier().chip;
}

function selectDeviceTier(tierId) {
    if (!DEVICE_TIERS.some(t => t.id === tierId)) return;
    selectedTierId = tierId;
    deviceTiersEl.querySelectorAll('.device-tier-chip').forEach(chip => {
        chip.classList.toggle('selected', chip.dataset.tierId === tierId);
    });
    renderTierName();
    // Пересобираем карточки тарифов под новую ступень; выбранный срок сохраняем.
    renderTariffs();
    if (selectedTariffId) selectTariff(selectedTariffId);
}

function renderTariffs() {
    // V3 layout: карточки в сетку 2×2 вместо четырёх строк во всю ширину.
    // Список из четырёх строк занимал 279px — почти экран целиком, и до кнопки
    // оплаты приходилось листать. В сетке те же четыре тарифа занимают ~180px.
    // Радио-кружок убран: в узкой карточке он съедал строку заголовка, а
    // выбранное состояние и без него однозначно — рамка, подложка и цвет цены.
    const html = TARIFFS.map(tariffView).map(t => {
        const popBadge = t.popular ? `<span class="tariff-card__pop">Популярный</span>` : '';
        // Per-month в формате `117 ₽/мес` без пробелов вокруг слеша — слитнее читается.
        // На 1мес per-month совпадает с итоговой ценой — не дублируем, строку скрываем.
        const subline = (t.perMonth === t.price)
            ? ''
            : `<span class="tariff-card__per-month">${t.perMonth} ₽/мес</span>`;
        return `
            <button type="button" class="tariff-card" data-tariff-id="${t.id}">
                ${popBadge}
                <span class="tariff-card__title">${t.title}</span>
                <span class="tariff-card__price">${t.price} ₽</span>
                ${subline}
            </button>
        `;
    }).join('');
    tariffsEl.innerHTML = html;
    tariffsEl.querySelectorAll('.tariff-card').forEach(card => {
        card.addEventListener('click', () => {
            haptic('light');
            selectTariff(card.dataset.tariffId);
        });
    });
}

function selectTariff(tariffId) {
    selectedTariffId = tariffId;
    tariffsEl.querySelectorAll('.tariff-card').forEach(card => {
        card.classList.toggle('selected', card.dataset.tariffId === tariffId);
    });
    const tariff = tariffView(TARIFFS.find(t => t.id === tariffId));
    buyPriceNewEl.textContent = `${tariff.price} ₽`;
    buyBtnEl.removeAttribute('disabled');
    updateBuyBtnLabel();
}

// ====================================================================
//  Мои подписки: список в профиле и переключение
// ====================================================================
//
// Экран обязан назвать ВСЕ последствия переключения до нажатия. Считает их сервер
// (GET switch-preview), фронт только показывает: собственный расчёт на фронте разъехался бы
// с сервером ровно в тот день, когда правило поменяется на сервере, — и экран начал бы
// обещать не то, что происходит.

/** Ответ switch-preview для открытого drawer’а — из него же берётся точка сверки для activate. */
let switchPreviewState = null;
/** Таймер отложенного hidden: без сброса он прячет только что открытый drawer. */
let switchDrawerHideTimer = null;
/**
 * Номер последнего запроса предпросмотра. Ответы приходят в порядке сети, а не в порядке
 * нажатий: на медленной мобильной сети протухший ответ по подписке #2 мог бы молча
 * подменить уже показанный экран по #3 — и человек подтвердил бы включение не той подписки.
 */
let switchPreviewReqSeq = 0;

function renderSubscriptions(data) {
    const section = document.getElementById('subs-section');
    const list = document.getElementById('subs-list');
    if (!section || !list) return;

    const subs = (data && data.subscriptions) || [];
    // Подписка одна — секции нет вовсе. Человек с одним ключом не должен
    // заметить, что в системе появились несколько подписок.
    if (subs.length < 2) {
        section.hidden = true;
        list.innerHTML = '';
        return;
    }
    section.hidden = false;
    list.innerHTML = subs.map(s => {
        const tier = s.tierLabel ? `, ${escapeHtml(s.tierLabel)}` : '';
        const go = s.frozen
            ? `<button type="button" class="sub-item__go" data-sub-id="${s.id}">Включить</button>`
            : '';
        return `
            <div class="sub-item${s.current ? ' sub-item--current' : ''}">
                <div class="sub-item__body">
                    <div class="sub-item__title">Подписка #${s.seq}${tier}</div>
                    <div class="sub-item__badges">${subscriptionBadges(s)}</div>
                    <div class="sub-item__state">${subscriptionStateLine(s)}</div>
                </div>
                ${go}
            </div>
        `;
    }).join('');

    list.querySelectorAll('.sub-item__go').forEach(btn => {
        btn.addEventListener('click', () => {
            haptic('light');
            openSwitchDrawer(btn.dataset.subId);
        });
    });
}

/**
 * \u0411\u0435\u0439\u0434\u0436\u0438 \u0441\u043e\u0441\u0442\u043e\u044f\u043d\u0438\u044f \u0441\u043b\u043e\u0432\u0430\u043c\u0438.
 *
 * \u0420\u0430\u043d\u044c\u0448\u0435 \u0441\u043e\u0441\u0442\u043e\u044f\u043d\u0438\u0435 \u043e\u0431\u043e\u0437\u043d\u0430\u0447\u0430\u043b \u0437\u043d\u0430\u0447\u043e\u043a \u25b6\ufe0f/\u23f8/\u26a0\ufe0f \u043f\u0435\u0440\u0435\u0434 \u043d\u0430\u0437\u0432\u0430\u043d\u0438\u0435\u043c: \u0437\u043d\u0430\u0447\u043e\u043a \u0447\u0438\u0442\u0430\u0435\u0442\u0441\u044f \u043a\u0430\u043a \u043a\u043d\u043e\u043f\u043a\u0430
 * (\u0435\u0433\u043e \u043f\u044b\u0442\u0430\u044e\u0442\u0441\u044f \u043d\u0430\u0436\u0430\u0442\u044c) \u0438 \u043d\u0435 \u043e\u0442\u0432\u0435\u0447\u0430\u0435\u0442 \u043d\u0430 \u0432\u0442\u043e\u0440\u043e\u0439 \u043f\u043e \u0447\u0430\u0441\u0442\u043e\u0442\u0435 \u0432\u043e\u043f\u0440\u043e\u0441 \u043a \u044d\u0442\u043e\u043c\u0443 \u0441\u043f\u0438\u0441\u043a\u0443 \u2014 \u043a\u0430\u043a\u0443\u044e \u0438\u0437
 * \u043f\u043e\u0434\u043f\u0438\u0441\u043e\u043a \u043f\u0440\u043e\u0434\u043b\u044f\u0442 \u0441 \u043a\u0430\u0440\u0442\u044b. \u0421\u043b\u043e\u0432\u043e \u043e\u0442\u0432\u0435\u0447\u0430\u0435\u0442 \u043d\u0430 \u043e\u0431\u0430.
 */
function subscriptionBadges(s) {
    const out = [];
    if (s.frozen) {
        out.push('<span class="sub-badge">\u0417\u0430\u043c\u043e\u0440\u043e\u0436\u0435\u043d\u0430</span>');
    } else if (s.live) {
        out.push('<span class="sub-badge sub-badge--live">\u0414\u0435\u0439\u0441\u0442\u0432\u0443\u0435\u0442 \u0441\u0435\u0439\u0447\u0430\u0441</span>');
    } else {
        // \u0421\u0442\u0430\u0442\u0443\u0441\u0430 \u00ab\u0438\u0441\u0442\u0435\u043a\u043b\u0430\u00bb \u0432 \u0441\u0438\u0441\u0442\u0435\u043c\u0435 \u043d\u0435\u0442 \u2014 \u0442\u0435\u043a\u0443\u0449\u0430\u044f \u043f\u043e\u0434\u043f\u0438\u0441\u043a\u0430 \u0441 \u043f\u0440\u043e\u0448\u0435\u0434\u0448\u0438\u043c \u0441\u0440\u043e\u043a\u043e\u043c \u043e\u0441\u0442\u0430\u0451\u0442\u0441\u044f
        // \u0442\u0435\u043a\u0443\u0449\u0435\u0439. \u041d\u0430\u043f\u0438\u0441\u0430\u0442\u044c \u0437\u0434\u0435\u0441\u044c \u00ab\u0434\u0435\u0439\u0441\u0442\u0432\u0443\u0435\u0442\u00bb \u0437\u043d\u0430\u0447\u0438\u043b\u043e \u0431\u044b \u0441\u043e\u043b\u0433\u0430\u0442\u044c.
        out.push('<span class="sub-badge sub-badge--dead">\u041d\u0435 \u0434\u0435\u0439\u0441\u0442\u0432\u0443\u0435\u0442</span>');
    }
    // \u041f\u043e\u043c\u0435\u0442\u043a\u0443 \u0441\u0447\u0438\u0442\u0430\u0435\u0442 \u0441\u0435\u0440\u0432\u0435\u0440: \u0437\u0430\u043c\u043e\u0440\u043e\u0436\u0435\u043d\u043d\u044b\u0435 \u043d\u0435 \u043f\u0440\u043e\u0434\u043b\u0435\u0432\u0430\u044e\u0442\u0441\u044f \u043d\u0438\u043a\u043e\u0433\u0434\u0430, \u0438 \u0432\u044b\u0447\u0438\u0441\u043b\u044f\u0442\u044c \u044d\u0442\u043e \u043d\u0430 \u0444\u0440\u043e\u043d\u0442\u0435
    // \u0437\u043d\u0430\u0447\u0438\u043b\u043e \u0431\u044b \u0437\u0430\u0432\u0435\u0441\u0442\u0438 \u0432\u0442\u043e\u0440\u043e\u0435 \u043f\u0440\u0430\u0432\u0438\u043b\u043e \u0440\u044f\u0434\u043e\u043c \u0441 \u0442\u0435\u043c, \u043f\u043e \u043a\u043e\u0442\u043e\u0440\u043e\u043c\u0443 \u0440\u0435\u0430\u043b\u044c\u043d\u043e \u0441\u043f\u0438\u0441\u044b\u0432\u0430\u044e\u0442 \u0434\u0435\u043d\u044c\u0433\u0438.
    if (s.renewing) {
        out.push('<span class="sub-badge sub-badge--renew">\u041f\u0440\u043e\u0434\u043b\u0435\u0432\u0430\u0435\u0442\u0441\u044f</span>');
    }
    return out.join('');
}

/**
 * Строка состояния — те же три формулировки, что и в боте. Замороженной важно не
 * «сколько лежит», а что дни НЕ тают, пока она спит: именно этого боятся,
 * увидев слово «заморожена».
 */
function subscriptionStateLine(s) {
    // Само состояние теперь называет бейдж, поэтому строка его не повторяет: «Заморожена» +
    // «заморожена, остаток…» подряд читается как две разные вещи. Слова, ради которых строка
    // существует, остаются те же, что в боте: остаток и «срок не расходуется».
    if (s.frozen) {
        return `остаток ${pluralizeDays(s.daysLeft || 0)}, срок не расходуется`;
    }
    if (s.live) {
        return `до ${formatDate(s.expiresAtEpoch)}`;
    }
    // Статуса «истекла» в системе нет — текущая подписка с прошедшим сроком
    // остаётся текущей. Написать тут «работает» значило бы солгать.
    return s.expiresAtEpoch ? `истекла ${formatDate(s.expiresAtEpoch)}` : 'срок вышел';
}

async function openSwitchDrawer(subId) {
    const drawer = document.getElementById('switch-drawer');
    const backdrop = document.getElementById('switch-backdrop');
    const listEl = document.getElementById('switch-preview');
    const warnEl = document.getElementById('switch-warn');
    const confirmBtn = document.getElementById('switch-confirm');
    if (!drawer || !backdrop) return;

    const reqId = ++switchPreviewReqSeq;
    switchPreviewState = null;
    listEl.innerHTML = '<li class="switch-preview__note">Загрузка…</li>';
    warnEl.hidden = true;
    confirmBtn.hidden = true;
    confirmBtn.removeAttribute('disabled');

    clearTimeout(switchDrawerHideTimer);
    backdrop.hidden = false;
    drawer.hidden = false;
    // eslint-disable-next-line no-unused-expressions
    drawer.offsetHeight;
    backdrop.classList.add('open');
    drawer.classList.add('open');

    try {
        const r = await fetch(`${API_BASE}/api/subscriptions/${subId}/switch-preview`, {
            headers: { 'X-Telegram-Init-Data': tg.initData },
        });
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        const preview = await r.json();
        if (reqId !== switchPreviewReqSeq) {
            return;
        }
        if (preview.alreadyCurrent) {
            // Кто-то уже включил её — из бота или с другого устройства.
            closeSwitchDrawer();
            loadMe();
            return;
        }
        switchPreviewState = preview;
        renderSwitchPreview(preview);
    } catch (e) {
        console.error('switch-preview failed:', e);
        if (reqId !== switchPreviewReqSeq) {
            return;
        }
        listEl.innerHTML = '<li class="switch-preview__note">Загрузить не удалось. Повторите попытку через минуту.</li>';
    }
}

function renderSwitchPreview(p) {
    const listEl = document.getElementById('switch-preview');
    const warnEl = document.getElementById('switch-warn');
    const confirmBtn = document.getElementById('switch-confirm');
    const titleEl = document.getElementById('switch-drawer-title');
    titleEl.textContent = `Включить подписку #${p.targetSeq}?`;

    if (p.emptyTarget) {
        // Кнопки «всё равно включить» здесь нет намеренно, как и в боте: включение
        // пустой подписки — это «остаться без доступа прямо сейчас».
        listEl.innerHTML = '<li class="switch-preview__note">На этой подписке не осталось оплаченных '
            + 'дней. Её включение отключит доступ: действующая подписка будет заморожена, '
            + 'а срока на этой нет.</li>';
        warnEl.textContent = 'Сначала пополните подписку, после этого её можно включить.';
        warnEl.hidden = false;
        confirmBtn.hidden = true;
        return;
    }
    if (!p.switchingEnabled) {
        // Аварийный тормоз. Молча прятать кнопку нельзя — у человека там замороженные
        // деньги, и исчезнувшая кнопка читается как «деньги пропали».
        listEl.innerHTML = '<li class="switch-preview__note">Переключение подписок временно '
            + 'недоступно. Сроки подписок не изменяются.</li>';
        warnEl.hidden = true;
        confirmBtn.hidden = true;
        return;
    }

    const items = [];
    items.push(`<li>Начнёт расходоваться срок подписки <b>#${p.targetSeq}</b>: `
        + `${pluralizeDays(p.willActivate.daysLeft)}, до ${formatDate(p.willActivate.untilEpoch)}</li>`);
    if (p.willFreeze) {
        items.push(`<li>Подписка <b>#${p.willFreeze.seq}</b> будет заморожена: остаток `
            + `${pluralizeDays(p.willFreeze.daysLeft)} сохраняется</li>`);
    }
    if (p.linkChanges) {
        items.push('<li><b>Ссылка сменится</b> — в приложении её нужно заменить</li>');
    }
    if (p.devicesDemoted && p.devicesDemoted.length) {
        const names = p.devicesDemoted.map(escapeHtml).join(', ');
        const more = p.devicesDemotedMore > 0 ? ` и ещё ${p.devicesDemotedMore}` : '';
        items.push(`<li>Будут отключены устройства: ${names}${more}</li>`);
    }
    items.push('<li class="switch-preview__note">Во время переключения доступ может не работать до минуты.</li>');

    listEl.innerHTML = items.join('');
    warnEl.hidden = true;
    confirmBtn.hidden = false;
    document.getElementById('switch-confirm-label').textContent = `Включить #${p.targetSeq}`;
}

function closeSwitchDrawer() {
    const drawer = document.getElementById('switch-drawer');
    const backdrop = document.getElementById('switch-backdrop');
    if (!drawer || !backdrop) return;
    backdrop.classList.remove('open');
    drawer.classList.remove('open');
    // Цель снимаем сразу: доехавший после закрытия ответ не должен оставлять взведённое
    // «включить эту» на следующее открытие.
    switchPreviewState = null;
    switchPreviewReqSeq++;
    clearTimeout(switchDrawerHideTimer);
    switchDrawerHideTimer = setTimeout(() => {
        backdrop.hidden = true;
        drawer.hidden = true;
    }, 350);
}

async function confirmSwitch() {
    if (!switchPreviewState) return;
    const btn = document.getElementById('switch-confirm');
    if (btn.hasAttribute('disabled')) return;
    const restore = setButtonBusy(btn, document.getElementById('switch-confirm-label'), 'Включение…');
    const target = switchPreviewState;
    try {
        const r = await fetch(`${API_BASE}/api/subscriptions/${target.targetId}/activate`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-Telegram-Init-Data': tg.initData,
            },
            // Что было текущим на экране, который человек видел. Сервер сверит и откажет,
            // если за это время состояние поменялось из бота или оплатой.
            body: JSON.stringify({
                expectedCurrentSubscriptionId: target.currentSubscriptionId === undefined
                    ? null : target.currentSubscriptionId,
            }),
        });
        const data = await r.json().catch(() => ({}));
        if (!r.ok) {
            closeSwitchDrawer();
            tg.showAlert(data.message || 'Переключение не выполнено. Состояние подписок не изменилось.');
            loadMe();
            return;
        }
        // Ответ — свежий /me целиком.
        applyMeData(data);
        closeSwitchDrawer();
        try { tg.HapticFeedback.notificationOccurred('success'); } catch {}
        tg.showAlert(`Подписка #${target.targetSeq} включена. Ссылка изменилась: `
            + 'замените её в приложении, иначе доступ продолжит работать по прежней подписке.');
    } catch (e) {
        console.error('activate failed:', e);
        closeSwitchDrawer();
        tg.showAlert('Переключение не выполнено. Повторите попытку.');
        loadMe();
    } finally {
        restore();
        btn.removeAttribute('disabled');
    }
}

document.getElementById('switch-confirm').addEventListener('click', () => {
    haptic('medium');
    confirmSwitch();
});
document.getElementById('switch-drawer-close').addEventListener('click', closeSwitchDrawer);
document.getElementById('switch-backdrop').addEventListener('click', closeSwitchDrawer);

// ====================================================================
//  Payment drawer — открывается по «Купить/Продлить», внутри 3 способа оплаты
// ====================================================================
const drawerEl = document.getElementById('pay-drawer');
const drawerBackdropEl = document.getElementById('pay-backdrop');
const drawerPlanEl = document.getElementById('pay-drawer-plan');
const drawerAmountEl = document.getElementById('pay-drawer-amount');
/** Таймер отложенного hidden — см. closePayDrawer. */
let payDrawerHideTimer = null;

// --------------------------------------------------------------------
//  Цель платежа: в какую подписку зачислятся деньги
// --------------------------------------------------------------------
//
// Главное свойство: деньги ложатся туда, что написано на экране. Поэтому выбранная
// цель уезжает в теле запроса и записывается на строку платежа с рождения, а не вычисляется
// заново в момент активации, когда состояние уже другое.

let selectedPayTarget = 'current';

/**
 * Из чего выбирать. Пустой/одиночный список значит «выбора нет» — тогда строка
 * вообще не показывается: у человека с одной подпиской экран оплаты обязан остаться
 * таким же, каким был.
 */
function payTargetOptions() {
    const me = window.__meData || {};
    const subs = me.subscriptions || [];
    const multi = me.multiSub || {};
    const opts = [];

    const current = subs.find(s => s.current);
    // ⛔ Действующая подписка предлагается ТОЛЬКО когда оплата не меняет ей ступень.
    //
    // Иначе это «заменить работающий тариф на купленный»: оплата дешёвой ступени понижала бы
    // лимит устройств действующей подписки на весь оплаченный срок и отключала лишние
    // устройства прямо сейчас. Ровно от этого и защищает вся конструкция с несколькими
    // подписками — предлагать такое отдельной кнопкой значит возвращать баг руками человека.
    // Ступени нет вовсе (триал, покупка до появления ступеней) — менять нечему, показываем.
    const currentKeepsTier = !!current
        && (!current.tierId || current.tierId === selectedTierId);
    if (current && currentKeepsTier) {
        opts.push({
            // ИМЕННО id, а не литерал 'current'. Литерал означает «канал цель не назвал», и
            // сервер решает сам — а при другой ступени и включённом флаге его решение «завести
            // отдельную подписку», то есть не то, что написано на этом чипсе. Тот же литерал старел бы
            // вместе со вкладкой: пока она висела, человек мог переключиться из бота, и деньги
            // ушли бы в другую подписку при правильной надписи на экране.
            value: String(current.id),
            label: `Подписка #${current.seq}`,
            hint: current.live
                ? `действует до ${formatDate(current.expiresAtEpoch)}`
                : 'не действует — срок вышел',
        });
    }
    subs.filter(s => s.frozen).forEach(s => opts.push({
        value: String(s.id),
        label: `Подписка #${s.seq}`,
        hint: `заморожена, в остатке ${pluralizeDays(s.daysLeft || 0)}`,
    }));

    // «Новая» — только если человеку разрешено и сервер такую покупку примет. Подписка
    // той же ступени уже есть — вариант не показываем вовсе: две неотличимые подписки
    // бесполезны, и предлагать выбор, который кончится отказом после выбора способа
    // оплаты — хуже, чем не предлагать вовсе.
    const limit = multi.maxPerUser || 0;
    if (multi.canOpenNew && subs.length && subs.length < limit
        && !subs.some(s => s.tierId && s.tierId === selectedTierId)) {
        opts.push({
            value: 'new',
            label: 'Новая, отдельная подписка',
            hint: 'вторая ссылка и свои устройства',
        });
    }
    return opts;
}

function renderPayTargets() {
    const row = document.getElementById('pay-target-row');
    const optionsEl = document.getElementById('pay-target-options');
    if (!row || !optionsEl) return;

    const opts = payTargetOptions();
    // Один вариант — выбирать не из чего, но СКАЗАТЬ, куда уйдут деньги, всё равно надо, если
    // это не действующая подписка: молча зачислить срок в замороженную значит оставить человека
    // искать оплаченные дни там, где их не прибавилось. Поэтому строку прячем только когда
    // единственная цель — действующая подписка (это и есть привычный экран большинства).
    const onlyTargetIsCurrent = opts.length === 1 && !!(window.__meData
        && (window.__meData.subscriptions || []).some(
            s => s.current && String(s.id) === String(opts[0].value)));
    if (opts.length === 0 || onlyTargetIsCurrent) {
        row.hidden = true;
        optionsEl.innerHTML = '';
        // Литерал, а не id: выбора не показывали, и решение остаётся за сервером — тогда
        // протухшая вкладка не уводит деньги в подписку, которая за это время изменилась.
        selectedPayTarget = 'current';
        updatePayTargetHint();
        updateDowngradeWarning();
        return;
    }
    // Цель одна и она не действующая — ниже она станет выбранной обычным путём и покажется
    // как утверждение, а не как выбор.
    // Выбор мог протухнуть: сменили ступень — и «новая подписка» исчезла из списка.
    if (!opts.some(o => o.value === selectedPayTarget)) {
        selectedPayTarget = opts[0].value;
    }
    row.hidden = false;
    // Радио-точка, а не только оттенок рамки: здесь решается, куда уйдут деньги, и это был
    // самый слабо обозначенный выбор в приложении — на телефоне выбранную «таблетку» от
    // соседней отличить не удавалось.
    optionsEl.innerHTML = opts.map(o => `
        <button type="button" class="pay-target__option${o.value === selectedPayTarget ? ' selected' : ''}"
                role="radio" aria-checked="${o.value === selectedPayTarget}"
                data-target="${escapeHtml(o.value)}">
            <span class="pay-target__radio" aria-hidden="true"></span>
            <span class="pay-target__text"><b>${escapeHtml(o.label)}</b><small>${escapeHtml(o.hint)}</small></span>
        </button>
    `).join('');
    optionsEl.querySelectorAll('.pay-target__option').forEach(btn => {
        btn.addEventListener('click', () => {
            haptic('light');
            selectPayTarget(btn.dataset.target);
        });
    });
    updatePayTargetHint();
    // Цель могла смениться прямо здесь (протухший выбор → opts[0]), а предупреждение о
    // даунгрейде считается по НЕЙ. Без пересчёта на экране оставался вердикт для прошлой цели.
    updateDowngradeWarning();
}

function selectPayTarget(value) {
    selectedPayTarget = value;
    document.querySelectorAll('#pay-target-options .pay-target__option').forEach(btn => {
        const on = btn.dataset.target === value;
        btn.classList.toggle('selected', on);
        btn.setAttribute('aria-checked', String(on));
    });
    updatePayTargetHint();
    updateDowngradeWarning();
}

/** Целится ли оплата в подписку, которая работает сейчас. */
function payTargetIsCurrent() {
    if (!selectedPayTarget || selectedPayTarget === 'current') {
        return true;
    }
    if (selectedPayTarget === 'new') {
        return false;
    }
    const subs = (window.__meData && window.__meData.subscriptions) || [];
    const target = subs.find(s => String(s.id) === String(selectedPayTarget));
    return !!(target && target.current);
}

/**
 * Пересчитать предупреждение о даунгрейде под выбранную цель. Считается по слотам ТЕКУЩЕЙ
 * подписки, поэтому при цели «другая подписка» его надо убирать: те устройства не пострадают.
 */
function updateDowngradeWarning() {
    const el = document.getElementById('pay-drawer-devices-warn');
    if (!el) return;
    const used = (window.__meData && window.__meData.devicesUsed) || 0;
    const tierDevices = currentTier().devices;
    if (payTargetIsCurrent() && used > tierDevices) {
        // Без эмодзи-предупреждения: плашка и так красная — знак дублировал цвет.
        el.textContent = `Сейчас активных устройств: ${used}. По этой ступени останется `
            + `${tierDevices}, остальные будут отключены.`;
        el.hidden = false;
    } else {
        el.hidden = true;
    }
}

/**
 * Последствия выбранной цели — словами и ДО оплаты.
 *
 * Тексты те же, что в боте (этап 5.5). Человек, платящий за другую ступень, ждёт,
 * что доступ станет «больше», а получает вторую подписку со второй ссылкой — узнать об этом
 * он обязан до оплаты, а не из чека.
 */
function updatePayTargetHint() {
    const hintEl = document.getElementById('pay-target-hint');
    if (!hintEl) return;
    const me = window.__meData || {};
    const subs = me.subscriptions || [];
    const current = subs.find(s => s.current);
    let text = '';

    if (selectedPayTarget === 'new') {
        text = (current && current.live)
            ? `Появится вторая подписка. Она создаётся замороженной: доступ продолжит работать `
                + `по подписке #${current.seq} до включения новой.`
            : 'Появится вторая подписка. Она включится сразу — ссылка сменится, '
                + 'в приложении её нужно заменить.';
    } else if (selectedPayTarget !== 'current') {
        const target = subs.find(s => String(s.id) === String(selectedPayTarget));
        if (target) {
            // Ветвимся по СОСТОЯНИЮ подписки, а не по виду значения: текущая теперь
            // тоже приезжает числом, и проверка «не current — значит замороженная» солгала бы
            // ровно про ту подписку, которая у человека работает.
            // ⚠ Развилка по current.live обязательна и здесь, а не только в ветке «новая».
            // Оплата в замороженную подписку идёт через depositIntoParked → settleAfterCommit,
            // а тот ВКЛЮЧАЕТ её немедленно, если живой текущей нет. Обещание «не начнёт
            // работать, пока Вы её не включите» в этом случае — прямая ложь: подписка
            // включится сама, а ссылка сменится, и человек об этом не предупреждён.
            const liveCurrent = current && current.live;
            if (!target.frozen) {
                text = `Срок будет добавлен к действующей подписке #${target.seq}. `
                    + 'Вторая подписка не появится.';
            } else if (liveCurrent) {
                text = `Срок будет зачислен в замороженную подписку #${target.seq}. `
                    + 'Она не начнёт работать до включения.';
            } else {
                text = `Срок будет зачислен в подписку #${target.seq}. Действующих подписок нет, `
                    + 'поэтому она включится сразу после оплаты — ссылка сменится, '
                    + 'в приложении её нужно заменить.';
            }
            if (target.tierId && target.tierId !== selectedTierId) {
                text += ` Ступень подписки #${target.seq} станет «${currentTier().chip}».`;
            }
        }
    }
    // Деньги уходят не в ту подписку, которую продлевает карта, — и это ровно тот случай,
    // когда человек ждёт, что автопродление подхватит оплаченную, а оно продолжает списывать
    // за действующую. Говорим об этом до оплаты, а не после первого лишнего списания.
    const renew = me.autorenewInfo;
    if (text && renew && renew.willCharge && !payTargetIsCurrent() && current) {
        text += ` Автопродление останется на подписке #${current.seq}.`;
    }
    hintEl.textContent = text;
    hintEl.hidden = !text;
}

function openPayDrawer() {
    if (!selectedTariffId) return;
    const tariff = tariffView(TARIFFS.find(t => t.id === selectedTariffId));

    drawerPlanEl.textContent = `${tariff.title}, ${currentTier().chip}`;
    drawerAmountEl.textContent = `${tariff.price} ₽`;

    // Зачёркнутая цена в окне оплаты осталась ровно под одну задачу — показать сумму
    // ДО применённого промокода (applyPromoToDrawer). Витринного «было 1074, стало 840»
    // здесь больше нет, поэтому при открытии строка всегда скрыта.
    document.getElementById('pay-drawer-amount-old').hidden = true;

    // Сбрасываем промокод при каждом открытии — состояние от прошлой попытки
    // (например юзер отменил оплату и сменил тариф) не должно протекать сюда.
    resetPromoUI();

    // Цель платежа — тоже заново: список зависит от выбранной ступени, а её могли
    // сменить между открытиями. Она же пересчитает предупреждение о даунгрейде: оно
    // считается по слотам ЦЕЛЕВОЙ подписки и до выбора цели правды не знает.
    renderPayTargets();

    clearTimeout(payDrawerHideTimer);
    drawerBackdropEl.hidden = false;
    drawerEl.hidden = false;
    // Триггерим reflow чтобы transition сработал на следующем кадре
    // eslint-disable-next-line no-unused-expressions
    drawerEl.offsetHeight;
    drawerBackdropEl.classList.add('open');
    drawerEl.classList.add('open');
}

function closePayDrawer() {
    drawerBackdropEl.classList.remove('open');
    drawerEl.classList.remove('open');
    // Скрываем полностью после окончания transition — чтобы tab-focus не ловил.
    // Таймер запоминаем: без этого быстрое «закрыл и сразу открыл» прячет уже открытый
    // drawer через 350 мс — человек видит, как экран оплаты «съел» его нажатие.
    clearTimeout(payDrawerHideTimer);
    payDrawerHideTimer = setTimeout(() => {
        drawerBackdropEl.hidden = true;
        drawerEl.hidden = true;
    }, 350);

    // Сбрасываем чекбокс автопродления — иначе при следующем открытии (другой тариф,
    // отказ от оплаты) галка останется в прошлом состоянии. Безопасный дефолт — выкл.
    const autorenewCb = document.getElementById('pay-autorenew-checkbox');
    if (autorenewCb) autorenewCb.checked = false;

    // Сбрасываем промокод
    resetPromoUI();

    // И цель платежа: безопасный дефолт — текущая подписка. Оставшаяся от прошлого
    // открытия «новая подписка» завела бы её при следующей оплате незамеченной.
    selectedPayTarget = 'current';
}

// ====================================================================
//  Промокод
// ====================================================================
//
// State applied на drawer-уровне: appliedPromo либо null (не применён),
// либо { code, discountKopeks, finalAmountKopeks } — после успешного
// /api/promos/validate. startPayment передаёт код в body запроса; backend
// атомарно резервирует слот и пересчитывает amount на сервере.
let appliedPromo = null;

const promoBlockEl = document.getElementById('promo-block');
const promoToggleEl = document.getElementById('promo-toggle');
const promoInputRowEl = document.getElementById('promo-input-row');
const promoInputEl = document.getElementById('promo-input');
const promoApplyEl = document.getElementById('promo-apply');
const promoResultEl = document.getElementById('promo-result');

function resetPromoUI() {
    appliedPromo = null;
    promoBlockEl.classList.remove('promo--applied');
    promoInputEl.value = '';
    promoInputRowEl.hidden = true;
    promoResultEl.hidden = true;
    promoResultEl.className = 'promo__result';
    promoResultEl.textContent = '';
}

// Раскрытие свёрнутой ссылки «Есть промокод?» → показываем input
promoToggleEl.addEventListener('click', () => {
    haptic('light');
    promoInputRowEl.hidden = false;
    promoToggleEl.style.display = 'none';
    promoInputEl.focus();
});

// Submit по Enter или по кнопке «Применить»
promoInputEl.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); applyPromo(); }
});
promoApplyEl.addEventListener('click', () => { haptic('light'); applyPromo(); });

// На iOS клавиатура поднимается через ~250ms после focus — за это время drawer
// не успевает понять, что input скрыт. Дёргаем scrollIntoView с задержкой,
// чтобы поле гарантированно оказалось в видимой части drawer'а (drawer
// scrollable благодаря max-height + overflow-y).
promoInputEl.addEventListener('focus', () => {
    setTimeout(() => {
        try { promoInputEl.scrollIntoView({ block: 'center', behavior: 'smooth' }); } catch {}
    }, 350);
});

async function applyPromo() {
    const code = (promoInputEl.value || '').trim();
    if (!code) return;
    if (!selectedTariffId) return;
    const planId = apiPlanIdFor(selectedTariffId);

    promoApplyEl.setAttribute('disabled', '');
    try {
        const r = await fetch(`${API_BASE}/api/promos/validate`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-Telegram-Init-Data': tg.initData,
            },
            body: JSON.stringify({ code, planId, deviceTierId: selectedTierId }),
        });
        const data = await r.json().catch(() => ({}));
        if (!r.ok) {
            showPromoError(data.message || 'Не удалось проверить промокод');
            return;
        }
        if (!data.valid) {
            showPromoError(data.message || 'Промокод недействителен');
            try { tg.HapticFeedback.notificationOccurred('error'); } catch {}
            return;
        }
        appliedPromo = {
            code: data.code,
            discountKopeks: data.discountKopeks,
            finalAmountKopeks: data.finalAmountKopeks,
        };
        applyPromoToDrawer(data);
        try { tg.HapticFeedback.notificationOccurred('success'); } catch {}
    } catch (e) {
        console.error('applyPromo failed', e);
        showPromoError('Проверить промокод не удалось. Повторите попытку позже.');
    } finally {
        promoApplyEl.removeAttribute('disabled');
    }
}

function applyPromoToDrawer(data) {
    const finalRub = Math.round(data.finalAmountKopeks / 100);
    const originalRub = Math.round(data.originalAmountKopeks / 100);
    const discountLabel = data.discountType === 'PERCENT'
        ? `${data.discountValue}%`
        : `${Math.round(data.discountKopeks / 100)} ₽`;

    drawerAmountEl.textContent = `${finalRub} ₽`;
    const amountOldEl = document.getElementById('pay-drawer-amount-old');
    amountOldEl.textContent = `${originalRub} ₽`;
    amountOldEl.hidden = false;

    promoBlockEl.classList.add('promo--applied');
    promoResultEl.className = 'promo__result promo__result--ok';
    promoResultEl.innerHTML = `
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><polyline points="20 6 9 17 4 12"/></svg>
        Промокод <b>${escapeHtml(data.code)}</b>, скидка ${discountLabel}
        <button type="button" class="promo__result-clear" id="promo-clear-btn">Убрать</button>
    `;
    promoResultEl.hidden = false;
    document.getElementById('promo-clear-btn').addEventListener('click', () => {
        haptic('light');
        clearAppliedPromo();
    });
}

function clearAppliedPromo() {
    appliedPromo = null;
    // Восстанавливаем исходную цену из текущего тарифа (с учётом ступени устройств)
    const found = TARIFFS.find(t => t.id === selectedTariffId);
    const tariff = found ? tariffView(found) : null;
    if (tariff) {
        drawerAmountEl.textContent = `${tariff.price} ₽`;
    }
    // Промокод сняли — сравнивать не с чем, зачёркнутую сумму убираем.
    document.getElementById('pay-drawer-amount-old').hidden = true;
    promoBlockEl.classList.remove('promo--applied');
    promoResultEl.hidden = true;
    promoInputEl.value = '';
    promoInputRowEl.hidden = true;
    promoToggleEl.style.display = '';
}

function showPromoError(message) {
    promoResultEl.className = 'promo__result promo__result--error';
    promoResultEl.textContent = message;
    promoResultEl.hidden = false;
}

// escapeHtml объявлен выше (см. рендер истории платежей) — дубль не нужен.

drawerBackdropEl.addEventListener('click', () => { haptic('light'); closePayDrawer(); });
document.getElementById('pay-drawer-close').addEventListener('click', () => { haptic('light'); closePayDrawer(); });

// Клик «Купить/Продлить» — теперь не идёт сразу в YooKassa, а открывает drawer
buyBtnEl.addEventListener('click', () => {
    if (!selectedTariffId) return;
    haptic('medium');
    openPayDrawer();
});

// Клики по 3 способам оплаты
document.querySelectorAll('.pay-method').forEach(btn => {
    btn.addEventListener('click', () => {
        haptic('medium');
        const method = btn.dataset.method;
        startPayment(method);
    });
});

/**
 * Единая точка создания платежа для любого провайдера. Каждый метод имеет свой
 * endpoint и свой способ открытия платёжной страницы.
 */
async function startPayment(method) {
    if (!selectedTariffId) return;
    const planId = apiPlanIdFor(selectedTariffId);

    // Дизейблим все pay-method кнопки на время запроса
    document.querySelectorAll('.pay-method').forEach(b => b.setAttribute('disabled', ''));

    const endpoint = {
        yookassa:  '/api/payments/yookassa',
        stars:     '/api/payments/stars',
        cryptobot: '/api/payments/cryptobot',
    }[method];

    // Для YooKassa читаем состояние чекбокса «С автопродлением» из drawer'а.
    // Чекбокс виден только когда config.recurrentEnabled=true (см. renderAutorenewStatus),
    // так что в выключенном-флагом-сценарии тут всегда false. Stars/Crypto не поддерживают
    // рекуррент через нашего провайдера — для них чекбокс игнорируется.
    const autorenewCb = document.getElementById('pay-autorenew-checkbox');
    const autorenew = !!(autorenewCb && autorenewCb.checked);
    // Промокод (если применён через /api/promos/validate). Backend пересчитает amount
    // и атомарно заинкрементит used_count в той же транзакции, что и Payment-create.
    const promoCode = appliedPromo ? appliedPromo.code : null;
    const deviceTierId = selectedTierId;
    // Цель — ровно та, что выбрана в строке «Зачислится в:». Нет строки — 'current',
    // то есть сегодняшнее поведение.
    const target = selectedPayTarget || 'current';
    const body = method === 'yookassa'
        ? { planId, autorenew, promoCode, deviceTierId, target }
        : { planId, promoCode, deviceTierId, target };

    try {
        const r = await fetch(`${API_BASE}${endpoint}`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-Telegram-Init-Data': tg.initData,
            },
            body: JSON.stringify(body),
        });
        if (!r.ok) {
            const err = await r.json().catch(() => ({}));
            // Сервер объяснил отказ человечески (лимит подписок, дубль ступени) —
            // показываем его текст, а не «не удалось создать платёж»: из трёх причин отказа
            // две человек решает сам, если ему их назвать.
            if (err.message) {
                tg.showAlert(err.message);
                return;
            }
            throw new Error(err.error || `HTTP ${r.status}`);
        }
        const data = await r.json();

        closePayDrawer();

        // Разные провайдеры — разные способы открытия
        if (method === 'yookassa') {
            // YooKassa confirmation URL — обычный https://yoomoney.ru/... → внешний браузер
            tg.openLink(data.confirmationUrl);
        } else if (method === 'cryptobot') {
            // CryptoBot отдаёт mini_app_invoice_url вида https://t.me/CryptoBot/app?startapp=...
            // Это Telegram-ссылка → openTelegramLink, чтобы Telegram открыл CryptoBot mini-app
            // напрямую (без захода в браузер). Если openLink — будет браузер → deep-link →
            // переключение mini-app, и Orchestra при этом закрывается.
            // Запасные варианты (pay_url) — обычный https → openLink.
            openSmart(data.invoiceUrl);
        } else if (method === 'stars') {
            // openInvoice — нативный UI Stars внутри Telegram; после оплаты
            // Telegram шлёт successful_payment боту, существующий обработчик
            // в SubscriptionBot активирует подписку.
            tg.openInvoice(data.invoiceUrl, (status) => {
                if (status === 'paid') {
                    tg.showAlert('Оплачено. Подписка будет активирована в течение нескольких секунд.');
                }
            });
        }
    } catch (e) {
        console.error('startPayment failed:', method, e);
        tg.showAlert('Создать платёж не удалось. Выберите другой способ или повторите попытку через минуту.');
    } finally {
        document.querySelectorAll('.pay-method').forEach(b => b.removeAttribute('disabled'));
    }
}

/**
 * id тарифа витрины → planId бэкенда. Живой прайс (loadPricing → /api/pricing) кладёт в
 * TARIFFS уже БЭКЕНДОВЫЕ id (month_1, …) — их отдаём как есть. Маппинг ниже нужен только
 * для фоллбэк-витрины с короткими id ('1m', …), которая живёт когда /api/pricing недоступен.
 * Неизвестный id не бросаем: бэк сам ответит 400 на несуществующий план, а исключение здесь
 * убивало оплату молча (throw ДО try — ни алерта, ни разблокировки кнопок).
 */
function apiPlanIdFor(uiTariffId) {
    switch (uiTariffId) {
        case '1m':  return 'month_1';
        case '3m':  return 'month_3';
        case '6m':  return 'month_6';
        case '12m': return 'month_12';
        default: return uiTariffId;
    }
}

// ====================================================================
//  Auto-refresh статуса при возврате в Mini App
// ====================================================================
//
// После оплаты юзер уходит во внешний платёжный flow:
//   - YooKassa → внешний браузер с формой YooKassa
//   - CryptoBot → @CryptoBot mini-app внутри Telegram
//   - Stars → нативный Stars overlay внутри Telegram
//
// Возвращаясь в Orchestra Mini App, юзер ожидал бы видеть обновлённый
// статус подписки (не «нет активной подписки», а «подписка до …»).
// Раньше для этого надо было закрыть и переоткрыть Mini App — теперь
// слушаем visibilitychange и перезагружаем /api/me как только Mini App
// снова visible.
//
// Throttle 2 секунды — защита от спама запросов если юзер быстро
// переключается между приложениями. Backend выдержит больше, но смысла нет.
let lastReloadTs = 0;
document.addEventListener('visibilitychange', () => {
    if (document.hidden) return;
    const now = Date.now();
    if (now - lastReloadTs < 2000) return;
    lastReloadTs = now;
    loadMe();
});

// ====================================================================
//  Запуск
// ====================================================================
renderServices();
renderDeviceTiers();
renderTariffs();
buyBtnEl.setAttribute('disabled', '');
updateBuyBtnLabel();
loadPricing();
loadMe();
