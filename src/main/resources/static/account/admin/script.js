/*
 * Админ-панель владельца: срезы базы + карточка человека.
 *
 * Два правила, которых стоит держаться при правках:
 *  1. Значения в ячейки кладутся через textContent, а не innerHTML. В базе лежат
 *     пользовательские строки (@username, имя устройства, User-Agent, note в журнале) —
 *     собранная из них разметка была бы XSS с самым удобным зрителем: владельцем.
 *  2. Имена колонок совпадают с полями записей бэкенда (AdminPanelService.*Row) — CSV
 *     собирается из тех же record-компонентов, поэтому экран и выгрузка не разъезжаются.
 */
(function () {
    'use strict';

    var API = '/api/account/admin';
    var LOGIN = 'login.html';

    // ==============================================================================
    // Формат
    // ==============================================================================

    var DATETIME = new Intl.DateTimeFormat('ru-RU', {
        timeZone: 'Europe/Moscow', day: '2-digit', month: '2-digit', year: '2-digit',
        hour: '2-digit', minute: '2-digit'
    });
    var DATE = new Intl.DateTimeFormat('ru-RU', {
        timeZone: 'Europe/Moscow', day: '2-digit', month: '2-digit', year: '2-digit'
    });

    /** Бэкенд отдаёт LocalDateTime без зоны, а прод живёт в UTC — дописываем Z и печатаем в МСК. */
    function parseTs(value) {
        if (!value) return null;
        var iso = /[Zz]$|[+-]\d\d:?\d\d$/.test(value) ? value : value + 'Z';
        var date = new Date(iso);
        return isNaN(date.getTime()) ? null : date;
    }

    function fmtDateTime(value) {
        var date = parseTs(value);
        return date ? DATETIME.format(date) : '';
    }

    function fmtDate(value) {
        var date = parseTs(value);
        return date ? DATE.format(date) : '';
    }

    function fmtAgo(value) {
        var date = parseTs(value);
        if (!date) return '';
        var minutes = Math.round((Date.now() - date.getTime()) / 60000);
        if (minutes < 1) return 'только что';
        if (minutes < 60) return minutes + ' мин назад';
        var hours = Math.round(minutes / 60);
        if (hours < 24) return hours + ' ч назад';
        return Math.round(hours / 24) + ' дн назад';
    }

    function fmtMoney(kopeks) {
        if (kopeks === null || kopeks === undefined) return '';
        return (kopeks / 100).toLocaleString('ru-RU', {
            minimumFractionDigits: 0, maximumFractionDigits: 2
        }) + ' ₽';
    }

    function fmtNumber(value) {
        if (value === null || value === undefined) return '';
        return Number(value).toLocaleString('ru-RU');
    }

    function fmtPercent(value) {
        if (value === null || value === undefined) return '—';
        return (Math.round(value * 10) / 10).toLocaleString('ru-RU') + ' %';
    }

    /** Секунды журнала — в человеческий срок: сутки/часы, со знаком. */
    function fmtSeconds(seconds) {
        if (seconds === null || seconds === undefined) return '';
        var sign = seconds < 0 ? '−' : '';
        var abs = Math.abs(seconds);
        if (abs >= 86400) return sign + (Math.round(abs / 8640) / 10).toLocaleString('ru-RU') + ' дн';
        if (abs >= 3600) return sign + Math.round(abs / 3600) + ' ч';
        if (abs >= 60) return sign + Math.round(abs / 60) + ' мин';
        return sign + abs + ' с';
    }

    function fmtBytes(bytes) {
        if (bytes === null || bytes === undefined) return '';
        if (bytes === 0) return '0';
        var units = ['Б', 'КБ', 'МБ', 'ГБ', 'ТБ'];
        var i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
        return (bytes / Math.pow(1024, i)).toLocaleString('ru-RU', {maximumFractionDigits: 1})
            + ' ' + units[i];
    }

    function fmtDaysLeft(days) {
        if (days === null || days === undefined) return '';
        if (days < 0) return 'истекла ' + Math.abs(days) + ' дн назад';
        return days + ' дн';
    }

    // ==============================================================================
    // Сеть
    // ==============================================================================

    function api(path) {
        return fetch(API + path, {
            credentials: 'same-origin',
            headers: {'Accept': 'application/json'}
        }).then(function (response) {
            if (response.status === 401) {
                window.location.replace(LOGIN + '?e=session');
                return new Promise(function () { /* больше ничего не делаем — уходим со страницы */ });
            }
            if (response.status === 429) {
                // Свой лимит есть только у /nodes: каждый его вызов — это поход в панель и
                // TCP-проба по каждой ноде. Голое «HTTP 429» не подсказывает, что делать.
                throw new Error('слишком часто — подождите немного и обновите');
            }
            if (!response.ok) {
                throw new Error('HTTP ' + response.status);
            }
            return response.json();
        });
    }

    function queryString(params) {
        var parts = [];
        Object.keys(params).forEach(function (key) {
            var value = params[key];
            if (value === null || value === undefined || value === '') return;
            parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(value));
        });
        return parts.length ? '?' + parts.join('&') : '';
    }

    // ==============================================================================
    // Описание разделов
    // ==============================================================================

    var STATUS_BADGE = {
        ACTIVE: 'ok', SUCCEEDED: 'ok',
        DISABLED: 'bad', FAILED: 'bad', CANCELED: 'bad', BLOCKED: 'bad', REVOKED: 'bad',
        PENDING: 'warn', REFUNDED: 'warn'
    };

    function col(key, title, type, extra) {
        var c = {key: key, title: title, type: type || 'text'};
        if (extra) Object.keys(extra).forEach(function (k) { c[k] = extra[k]; });
        return c;
    }

    var SEEN_HINT = 'Колонка «Обновлял конфиг» — момент последнего обращения клиента к ссылке '
        + 'подписки, с шагом в час, и только для клиентов, присылающих идентификатор устройства. '
        + 'Это не «последний раз пользовался»: человек может неделю работать на закешированных '
        + 'параметрах. Мгновенный онлайн есть только по узлам, которые спрашивают у нас '
        + 'авторизацию, — колонка «сеть».';

    var TABS = [
        {id: 'overview', title: 'Обзор'},
        // Не таблица: у нод ценность в расхождениях между источниками, а не в списке строк,
        // поэтому раздел рисуется своим экраном (см. renderNodes) и колонок не имеет.
        {id: 'nodes', title: 'Ноды'},
        {
            id: 'users', title: 'Люди', path: '/users', export: 'users',
            search: {param: 'q', placeholder: 'id, @username, e-mail, аккаунт'},
            hint: SEEN_HINT,
            filters: [{
                param: 'mode', options: [
                    {value: '', label: 'Все'},
                    {value: 'active', label: 'Активные'},
                    {value: 'expired', label: 'Истёкшие'},
                    {value: 'paying', label: 'Платившие'},
                    {value: 'trial', label: 'Без оплат'},
                    {value: 'email', label: 'С e-mail'},
                    {value: 'autorenew', label: 'Автопродление'}
                ]
            }],
            sort: 'createdAt',
            columns: [
                col('telegramId', 'Telegram ID', 'mono', {sortable: true}),
                col('telegramUsername', 'Username', 'user', {sortable: true}),
                col('status', 'Статус', 'badge', {sortable: true}),
                col('daysLeft', 'Осталось', 'daysLeft'),
                col('expiresAt', 'Истекает', 'datetime', {sortable: true}),
                col('onlineNow', 'Сеть', 'online'),
                col('lastSeenAt', 'Обновлял конфиг', 'ago'),
                col('devices', 'Устройств', 'num'),
                col('deviceLimit', 'Лимит', 'num'),
                col('deviceTierId', 'Ступень', 'mono'),
                col('paymentsCount', 'Оплат', 'num'),
                col('paidKopeks', 'Сумма', 'money'),
                col('lastPaidAt', 'Последняя оплата', 'datetime'),
                col('subscriptions', 'Подписок', 'num'),
                col('autorenewEnabled', 'Автопродл.', 'bool'),
                col('cardBound', 'Карта', 'bool'),
                col('email', 'E-mail', 'text', {sortable: true}),
                col('emailVerified', 'Подтв.', 'bool'),
                col('providerUsername', 'Аккаунт в панели', 'mono'),
                col('createdAt', 'Пришёл', 'datetime', {sortable: true}),
                col('trialUsedAt', 'Триал', 'date')
            ]
        },
        {
            id: 'payments', title: 'Платежи', path: '/payments', export: 'payments',
            search: {param: 'q', placeholder: 'id платежа, telegram id, тариф, промокод'},
            filters: [
                {
                    param: 'status', options: [
                        {value: '', label: 'Любой статус'},
                        {value: 'SUCCEEDED', label: 'Оплачен'},
                        {value: 'PENDING', label: 'Ожидает'},
                        {value: 'CANCELED', label: 'Отменён'},
                        {value: 'FAILED', label: 'Ошибка'},
                        {value: 'REFUNDED', label: 'Возврат'}
                    ]
                },
                {
                    param: 'provider', options: [
                        {value: '', label: 'Все способы'},
                        {value: 'YOOKASSA', label: 'ЮKassa'},
                        {value: 'CRYPTOBOT', label: 'Крипта'},
                        {value: 'STARS', label: 'Stars'}
                    ]
                }
            ],
            sort: 'createdAt',
            columns: [
                col('id', 'ID', 'mono', {sortable: true}),
                col('createdAt', 'Создан', 'datetime', {sortable: true}),
                col('paidAt', 'Оплачен', 'datetime', {sortable: true}),
                col('telegramId', 'Telegram ID', 'mono', {sortable: true}),
                col('telegramUsername', 'Username', 'user'),
                col('status', 'Статус', 'badge', {sortable: true}),
                col('provider', 'Способ', 'text'),
                col('amountKopeks', 'Сумма', 'money', {sortable: true}),
                col('planId', 'Тариф', 'mono'),
                col('deviceTierId', 'Ступень', 'mono'),
                col('targetKind', 'Куда', 'mono'),
                col('subscriptionId', 'Подписка', 'mono'),
                col('promoCodeUsed', 'Промокод', 'mono'),
                col('autorenew', 'Авто', 'bool'),
                col('refundedAt', 'Возврат', 'datetime'),
                col('refundAmountKopeks', 'Вернули', 'money'),
                
                col('externalId', 'ID у провайдера', 'mono')
            ]
        },
        {
            id: 'subscriptions', title: 'Подписки', path: '/subscriptions', export: 'subscriptions',
            search: {param: 'telegramId', placeholder: 'telegram id', numeric: true},
            hint: 'Таблица subscriptions — зеркало customers на время перехода к нескольким '
                + 'подпискам. Расхождения зеркала с источником правды показывает команда /subcheck.',
            filters: [{
                param: 'state', options: [
                    {value: '', label: 'Все'},
                    {value: 'current', label: 'Текущие'},
                    {value: 'parked', label: 'Припаркованные'}
                ]
            }],
            sort: 'createdAt',
            columns: [
                col('id', 'ID', 'mono', {sortable: true}),
                col('telegramId', 'Telegram ID', 'mono', {sortable: true}),
                col('telegramUsername', 'Username', 'user'),
                col('seq', '№', 'num', {sortable: true}),
                col('label', 'Название', 'text'),
                col('current', 'Текущая', 'bool'),
                col('state', 'Состояние', 'badge'),
                col('origin', 'Откуда', 'text'),
                col('expiresAt', 'Истекает', 'datetime', {sortable: true}),
                col('balanceSeconds', 'Заморожено', 'seconds'),
                col('deviceLimit', 'Лимит', 'num'),
                col('deviceTierId', 'Ступень', 'mono'),
                col('autorenewEnabled', 'Автопродл.', 'bool'),
                col('providerUsername', 'Аккаунт в панели', 'mono'),
                col('createdAt', 'Создана', 'datetime', {sortable: true}),
                col('parkedAt', 'Припаркована', 'datetime'),
                col('lastActivatedAt', 'Включена', 'datetime', {sortable: true}),
                col('providerIntent', 'Намерение', 'mono'),
                col('providerSyncedAt', 'Синхр.', 'datetime')
            ]
        },
        {
            id: 'devices', title: 'Устройства', path: '/devices', export: 'devices',
            search: {param: 'telegramId', placeholder: 'telegram id', numeric: true},
            hint: SEEN_HINT,
            filters: [{
                param: 'status', options: [
                    {value: '', label: 'Все'},
                    {value: 'ACTIVE', label: 'Активные'},
                    {value: 'BLOCKED', label: 'Сверх лимита'},
                    {value: 'REVOKED', label: 'Отключённые'}
                ]
            }],
            sort: 'lastSeenAt',
            columns: [
                col('telegramId', 'Telegram ID', 'mono', {sortable: true}),
                col('telegramUsername', 'Username', 'user'),
                col('customName', 'Имя', 'text'),
                col('deviceModel', 'Модель', 'text'),
                col('deviceOs', 'ОС', 'text', {sortable: true}),
                col('osVersion', 'Версия', 'mono'),
                col('status', 'Статус', 'badge'),
                col('lastSeenAt', 'Обновлял конфиг', 'ago', {sortable: true}),
                col('firstSeenAt', 'Впервые', 'datetime', {sortable: true}),
                col('userAgent', 'Клиент', 'text'),
                col('hwid', 'HWID', 'mono'),
                col('subscriptionId', 'Подписка', 'mono'),
                col('id', 'ID', 'mono')
            ]
        },
        {
            id: 'ledger', title: 'Журнал секунд', path: '/ledger', export: 'ledger',
            search: {param: 'telegramId', placeholder: 'telegram id', numeric: true},
            hint: 'Append-only журнал начислений и списаний оплаченного времени.',
            columns: [
                col('occurredAt', 'Когда', 'datetime'),
                col('telegramId', 'Telegram ID', 'mono'),
                col('subscriptionSeq', '№ подписки', 'num'),
                col('kind', 'Событие', 'badge'),
                col('deltaSeconds', 'Изменение', 'seconds'),
                col('balanceAfterSeconds', 'Остаток после', 'seconds'),
                col('paymentId', 'Платёж', 'mono'),
                col('note', 'Примечание', 'wrap'),
                col('id', 'ID', 'mono')
            ]
        },
        {
            id: 'referrals', title: 'Рефералы', path: '/referrals', export: 'referrals',
            columns: [
                col('referredAt', 'Пришёл', 'datetime'),
                col('referrerTelegramId', 'Пригласил (ID)', 'mono'),
                col('referrerUsername', 'Пригласил', 'user'),
                col('referredTelegramId', 'Приглашён (ID)', 'mono'),
                col('referredUsername', 'Приглашён', 'user'),
                col('paidAt', 'Бонус начислен', 'datetime'),
                col('rewardBonusDays', 'Дней пригласившему', 'num'),
                col('refereeBonusDays', 'Дней приглашённому', 'num'),
                col('paymentId', 'Платёж', 'mono'),
                col('bonusRevertedAt', 'Откат', 'datetime')
            ]
        },
        {
            id: 'promocodes', title: 'Промокоды', path: '/promocodes', export: 'promocodes', plain: true,
            hint: 'Счётчик использований считает и резервы: строка пишется при выставлении счёта, '
                + 'а не при оплате. Неоплаченный счёт с промокодом уже виден в этой цифре.',
            columns: [
                col('code', 'Код', 'mono'),
                col('active', 'Активен', 'bool'),
                col('discountType', 'Тип', 'text'),
                col('discountValue', 'Величина', 'num'),
                col('usedCount', 'Использован', 'num'),
                col('maxUses', 'Максимум', 'num'),
                col('expiresAt', 'До', 'datetime'),
                col('createdAt', 'Создан', 'datetime'),
                col('createdByTelegramId', 'Кем', 'mono'),
                col('id', 'ID', 'mono')
            ]
        },
        {
            id: 'promousages', title: 'Списания промо', path: '/promousages', export: 'promousages',
            columns: [
                col('usedAt', 'Когда', 'datetime'),
                col('code', 'Код', 'mono'),
                col('telegramId', 'Telegram ID', 'mono'),
                col('telegramUsername', 'Username', 'user'),
                col('discountKopeks', 'Скидка', 'money'),
                col('paymentId', 'Платёж', 'mono'),
                col('revertedAt', 'Откат', 'datetime'),
                col('id', 'ID', 'mono')
            ]
        },
        {
            id: 'grants', title: 'Гранты', path: '/grants', export: 'grants',
            hint: 'Отложенные подарки по @username: применяются при первом /start или открытии Mini App.',
            columns: [
                col('grantedAt', 'Выдан', 'datetime'),
                col('username', 'Кому (@)', 'text'),
                col('days', 'Дней', 'num'),
                col('used', 'Применён', 'bool'),
                col('usedAt', 'Когда применён', 'datetime'),
                col('usedByTelegramId', 'Telegram ID', 'mono'),
                col('expiresAt', 'Сгорает', 'datetime'),
                col('grantedByTelegramId', 'Кем выдан', 'mono'),
                col('id', 'ID', 'mono')
            ]
        },
        {
            id: 'magiclinks', title: 'Ссылки входа', path: '/magiclinks', export: 'magiclinks',
            hint: 'Сами токены не показываются и не выгружаются: по ним выдаётся живая сессия кабинета.',
            columns: [
                col('createdAt', 'Выдана', 'datetime'),
                col('email', 'E-mail', 'text'),
                col('telegramId', 'Telegram ID', 'mono'),
                col('telegramUsername', 'Username', 'user'),
                col('usedAt', 'Использована', 'datetime'),
                col('expiresAt', 'Истекает', 'datetime'),
                col('id', 'ID', 'mono')
            ]
        },
        {
            id: 'offers', title: 'Согласие с офертой', path: '/offers', export: 'offers',
            columns: [
                col('acceptedAt', 'Когда', 'datetime'),
                col('telegramId', 'Telegram ID', 'mono'),
                col('telegramUsername', 'Username', 'user'),
                col('offerUrl', 'Документ', 'link'),
                col('offerRevision', 'Редакция', 'mono')
            ]
        }
    ];

    function tabById(id) {
        for (var i = 0; i < TABS.length; i++) {
            if (TABS[i].id === id) return TABS[i];
        }
        return TABS[0];
    }

    // ==============================================================================
    // Меню
    // ==============================================================================

    /*
     * Разбивка разделов по группам сайдбара. Держится отдельно от TABS намеренно:
     * TABS описывает ДАННЫЕ раздела (путь, колонки, фильтры), а это — его место в
     * меню. Порядок здесь и есть порядок на экране; раздел, не попавший ни в одну
     * группу, в сайдбаре не появится, хотя по прямому хэшу откроется.
     */
    var NAV_GROUPS = [
        {title: 'Обзор',         items: ['overview']},
        {title: 'Люди и доступ', items: ['users', 'devices', 'subscriptions']},
        {title: 'Деньги',        items: ['payments', 'ledger']},
        {title: 'Рост',          items: ['referrals', 'promocodes', 'promousages', 'grants']},
        {title: 'Инфраструктура', items: ['nodes']},
        {title: 'Журналы',       items: ['magiclinks', 'offers']}
    ];

    /*
     * Иконки — наборы path'ей на сетке 24×24, обводкой. Лежат здесь, а не в разметке,
     * потому что пункты меню строятся скриптом. Растровых иконок нет сознательно:
     * CSP страницы разрешает img-src только 'self' и data:, а инлайновый <svg> под
     * img-src вообще не подпадает — и заодно красится currentColor'ом.
     */
    var ICONS = {
        overview:    ['M3 3h7v7H3z', 'M14 3h7v7h-7z', 'M14 14h7v7h-7z', 'M3 14h7v7H3z'],
        users:       ['M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2',
                      'M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z',
                      'M22 21v-2a4 4 0 0 0-3-3.87', 'M16 3.13a4 4 0 0 1 0 7.75'],
        devices:     ['M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z',
                      'M12 18h.01'],
        subscriptions: ['M3 6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z',
                      'M8 2v4', 'M16 2v4', 'M3 10h18'],
        payments:    ['M3 6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z',
                      'M2 10h20'],
        ledger:      ['M8 6h11', 'M8 12h11', 'M8 18h11', 'M4 6h.01', 'M4 12h.01', 'M4 18h.01'],
        referrals:   ['M18 8a3 3 0 1 0 0-6 3 3 0 0 0 0 6z', 'M6 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
                      'M18 22a3 3 0 1 0 0-6 3 3 0 0 0 0 6z', 'm8.6 13.5 6.8 4', 'm15.4 6.5-6.8 4'],
        promocodes:  ['m20.6 12.6-8 8a2 2 0 0 1-2.8 0l-7-7A2 2 0 0 1 2 12V4a2 2 0 0 1 2-2h8a2 2 0 0 1 1.4.6l7.2 7.2a2 2 0 0 1 0 2.8z',
                      'M7.5 7.5h.01'],
        promousages: ['M19 5 5 19', 'M6.5 9a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5z',
                      'M17.5 20a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5z'],
        grants:      ['M20 12v10H4V12', 'M2 7h20v5H2z', 'M12 22V7',
                      'M12 7H7.5a2.5 2.5 0 0 1 0-5C11 2 12 7 12 7z',
                      'M12 7h4.5a2.5 2.5 0 0 0 0-5C13 2 12 7 12 7z'],
        magiclinks:  ['M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.8 1.7',
                      'M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.8-1.7'],
        offers:      ['M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z',
                      'M14 2v6h6', 'm9 15 2 2 4-4'],
        nodes:       ['M4 4h16a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z',
                      'M4 14h16a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1z',
                      'M7 7h.01', 'M7 17h.01'],

        // Только для карточек обзора.
        check:       ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'm8.5 12.2 2.4 2.4 4.6-4.8'],
        ban:         ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M5.6 5.6l12.8 12.8'],
        wifi:        ['M5 12.55a11 11 0 0 1 14.08 0', 'M1.42 9a16 16 0 0 1 21.16 0',
                      'M8.53 16.11a6 6 0 0 1 6.95 0', 'M12 20h.01']
    };

    var SVG_NS = 'http://www.w3.org/2000/svg';

    /** Собирает <svg> из ICONS. Неизвестное имя — пустой значок, а не падение рендера. */
    function icon(name, className) {
        var svg = document.createElementNS(SVG_NS, 'svg');
        svg.setAttribute('viewBox', '0 0 24 24');
        svg.setAttribute('fill', 'none');
        svg.setAttribute('stroke', 'currentColor');
        svg.setAttribute('stroke-width', '1.8');
        svg.setAttribute('stroke-linecap', 'round');
        svg.setAttribute('stroke-linejoin', 'round');
        svg.setAttribute('aria-hidden', 'true');
        if (className) svg.setAttribute('class', className);
        (ICONS[name] || []).forEach(function (d) {
            var path = document.createElementNS(SVG_NS, 'path');
            path.setAttribute('d', d);
            svg.appendChild(path);
        });
        return svg;
    }

    // ==============================================================================
    // Состояние
    // ==============================================================================

    var state = {tab: 'overview', page: 0, size: 50, sort: null, dir: 'desc', search: '', filters: {}};

    var el = {};
    ['tabs', 'view-overview', 'view-table', 'loading', 'error', 'stamp', 'search', 'filters',
        'count', 'export-btn', 'grid', 'grid-head', 'grid-body', 'prev-btn', 'next-btn',
        'pageinfo', 'table-hint', 'refresh-btn', 'logout-btn', 'drawer', 'drawer-backdrop',
        'drawer-close', 'card-title', 'card-sub', 'card-body', 'overview-kpi', 'overview-money',
        'overview-plans', 'overview-cohorts', 'overview-online', 'overview-relay', 'overview-tables',
        'view-title', 'sidebar', 'sidebar-close', 'scrim', 'burger', 'clock',
        'view-nodes', 'nodes-kpi', 'nodes-list', 'nodes-monitors'
    ].forEach(function (id) { el[id] = document.getElementById(id); });

    // ==============================================================================
    // Ячейки
    // ==============================================================================

    function cell(value, type, row) {
        var td = document.createElement('td');
        switch (type) {
            case 'num':
                td.className = 'num';
                td.textContent = fmtNumber(value);
                break;
            case 'money':
                td.className = 'num';
                td.textContent = fmtMoney(value);
                break;
            case 'mono':
                td.className = 'mono';
                td.textContent = value === null || value === undefined ? '' : String(value);
                break;
            case 'datetime':
                td.className = 'mono dim';
                td.textContent = fmtDateTime(value);
                break;
            case 'date':
                td.className = 'mono dim';
                td.textContent = fmtDate(value);
                break;
            case 'ago':
                td.className = 'dim';
                td.textContent = value ? fmtAgo(value) : '—';
                if (value) td.title = fmtDateTime(value);
                break;
            case 'seconds':
                td.className = 'num';
                td.textContent = fmtSeconds(value);
                break;
            case 'daysLeft':
                td.className = 'num';
                td.textContent = fmtDaysLeft(value);
                if (value !== null && value !== undefined && value < 0) td.classList.add('dim');
                break;
            case 'bool':
                td.appendChild(boolBadge(value));
                break;
            case 'badge':
                if (value) {
                    var badge = document.createElement('span');
                    badge.className = 'badge' + (STATUS_BADGE[value] ? ' badge--' + STATUS_BADGE[value] : '');
                    badge.textContent = value;
                    td.appendChild(badge);
                }
                break;
            case 'online':
                if (value) {
                    var dot = document.createElement('span');
                    dot.className = 'dot';
                    dot.title = 'Подключение к узлу сервиса за последние 10 минут';
                    td.appendChild(dot);
                } else {
                    td.className = 'dim';
                    td.textContent = '—';
                }
                break;
            case 'user':
                td.textContent = value ? '@' + value : '';
                break;
            case 'link':
                if (value) {
                    var a = document.createElement('a');
                    a.href = value;
                    a.target = '_blank';
                    a.rel = 'noopener noreferrer';
                    a.textContent = 'открыть';
                    td.appendChild(a);
                }
                break;
            case 'wrap':
                td.className = 'wrap';
                td.textContent = value === null || value === undefined ? '' : String(value);
                break;
            default:
                td.textContent = value === null || value === undefined ? '' : String(value);
        }
        return td;
    }

    function boolBadge(value) {
        var span = document.createElement('span');
        span.className = 'badge' + (value ? ' badge--ok' : '');
        span.textContent = value ? 'да' : 'нет';
        return span;
    }

    // ==============================================================================
    // Обзор
    // ==============================================================================

    /**
     * Карточка метрики. {@code iconName} и {@code tone} необязательны: карточки денег
     * идут без значков, иначе шесть цветных плашек подряд начинают соперничать с
     * самими цифрами. tone — 'warn' | 'danger' | 'info', по умолчанию акцентный зелёный.
     */
    function kpi(container, label, value, sub, iconName, tone) {
        var box = document.createElement('div');
        box.className = 'kpi';
        if (iconName) {
            var badge = document.createElement('div');
            badge.className = 'kpi__icon' + (tone ? ' kpi__icon--' + tone : '');
            badge.appendChild(icon(iconName));
            box.appendChild(badge);
        }
        var l = document.createElement('div');
        l.className = 'kpi__label';
        l.textContent = label;
        var v = document.createElement('div');
        v.className = 'kpi__value';
        v.textContent = value;
        box.appendChild(l);
        box.appendChild(v);
        if (sub) {
            var s = document.createElement('div');
            s.className = 'kpi__sub';
            s.textContent = sub;
            box.appendChild(s);
        }
        container.appendChild(box);
    }

    function miniRows(container, rows) {
        container.textContent = '';
        if (!rows.length) {
            var empty = document.createElement('div');
            empty.className = 'minitable__empty';
            empty.textContent = 'Пусто';
            container.appendChild(empty);
            return;
        }
        rows.forEach(function (pair) {
            var row = document.createElement('div');
            row.className = 'minitable__row';
            var k = document.createElement('span');
            k.className = 'minitable__key';
            k.textContent = pair[0];
            var v = document.createElement('span');
            v.className = 'minitable__val';
            v.textContent = pair[1];
            row.appendChild(k);
            row.appendChild(v);
            container.appendChild(row);
        });
    }

    function renderOverview(data) {
        var s = data.stats;
        var m = data.metrics;

        el['overview-kpi'].textContent = '';
        kpi(el['overview-kpi'], 'Всего людей', fmtNumber(s.totalUsers),
            '+' + s.newUsersLast7d + ' за неделю, +' + s.newUsersLast30d + ' за месяц', 'users');
        kpi(el['overview-kpi'], 'Активны сейчас', fmtNumber(s.activeUsers),
            'из них платных ' + fmtNumber(m.activePayingUsers), 'check');
        kpi(el['overview-kpi'], 'Отключены', fmtNumber(s.disabledUsers),
            'истекли за 30 дней: ' + fmtNumber(s.paidUsersExpiredLast30d), 'ban', 'danger');
        kpi(el['overview-kpi'], 'Платили хоть раз', fmtNumber(s.paidUniqueUsers),
            'конверсия триала ' + fmtPercent(m.trialConversionPct), 'payments', 'info');
        kpi(el['overview-kpi'], 'Устройств за сутки', fmtNumber(data.devicesSeen24h),
            'неделя ' + fmtNumber(data.devicesSeen7d) + ' · месяц ' + fmtNumber(data.devicesSeen30d),
            'devices', 'info');
        kpi(el['overview-kpi'], 'Онлайн (hy2)', fmtNumber(data.nodeOnline.length),
            'подключений за 10 минут', 'wifi');

        el['overview-money'].textContent = '';
        kpi(el['overview-money'], 'Выручка 30 дней', fmtMoney(s.revenue30dKopeks),
            fmtNumber(s.successfulPayments30d) + ' оплат');
        // Средний чек и конверсия считаются здесь, а не приезжают из StatsSnapshot: Jackson
        // сериализует у record'а только его компоненты, а эти два — обычные методы.
        kpi(el['overview-money'], 'Средний чек', fmtMoney(s.successfulPayments30d
            ? Math.round(s.revenue30dKopeks / s.successfulPayments30d) : 0));
        kpi(el['overview-money'], 'MRR', fmtMoney(m.mrrKopeks));
        kpi(el['overview-money'], 'ARPU', fmtMoney(m.arpuKopeks));
        kpi(el['overview-money'], 'Отток за 30 дней', fmtPercent(m.churnRate30dPct));
        kpi(el['overview-money'], 'LTV', m.ltvDefined ? fmtMoney(m.ltvKopeks) : '—',
            m.ltvDefined ? '' : 'мало данных');

        var providers = s.revenue30dByProviderKopeks || {};
        var providerRows = Object.keys(providers).map(function (key) {
            return [key, fmtMoney(providers[key])];
        });
        var planRows = Object.keys(m.activePayingByPlan || {}).map(function (key) {
            return [key, fmtNumber(m.activePayingByPlan[key])];
        });
        miniRows(el['overview-plans'], planRows.concat(providerRows.length
            ? [['— выручка 30 дней по способам —', '']].concat(providerRows) : []));

        miniRows(el['overview-cohorts'], (m.cohorts || []).map(function (c) {
            return [c.monthLabel + ' · ' + c.cohortSize + ' чел.',
                c.stillPayingNow + ' (' + fmtPercent(c.retentionPct) + ')'];
        }));

        miniRows(el['overview-online'], data.nodeOnline.map(function (o) {
            return [o.providerUsername + ' · ' + (o.nodeIp || '?'), fmtAgo(o.at)];
        }));

        if (data.relay) {
            var relayRows = Object.keys(data.relay.connectionsByPort || {}).map(function (port) {
                return ['порт ' + port, fmtNumber(data.relay.connectionsByPort[port]) + ' соед.'];
            });
            relayRows.push(['уникальных клиентов', fmtNumber(data.relay.uniqueClients)]);
            relayRows.push(['замер', fmtAgo(data.relay.at)]);
            miniRows(el['overview-relay'], relayRows);
        } else {
            miniRows(el['overview-relay'], []);
        }

        miniRows(el['overview-tables'], Object.keys(data.tableRows).map(function (name) {
            return [name, fmtNumber(data.tableRows[name])];
        }));

        el.stamp.textContent = 'данные на ' + fmtDateTime(data.generatedAt) + ' МСК';
    }

    // ==============================================================================
    // Ноды
    // ==============================================================================

    var NODE_BADGE = {
        connected: 'badge--ok', connecting: 'badge--warn',
        error: 'badge--bad', disabled: ''
    };

    /** Плашка в шапке карточки ноды. tone: ok | bad | mute | mono. */
    function metaChip(parent, text, tone, title) {
        var chip = document.createElement('span');
        chip.className = 'metachip' + (tone ? ' metachip--' + tone : '');
        chip.textContent = text;
        if (title) chip.title = title;
        parent.appendChild(chip);
    }

    function renderNodes(data) {
        var nodes = data.nodes || [];

        var closed = nodes.filter(function (n) { return !n.port443Open; }).length;
        el['nodes-kpi'].textContent = '';
        kpi(el['nodes-kpi'], 'Нод в панели', fmtNumber(data.total), null, 'nodes');
        kpi(el['nodes-kpi'], 'Панель считает живыми', fmtNumber(data.connected),
            data.total ? 'из ' + fmtNumber(data.total) : 'панель не ответила', 'check');
        kpi(el['nodes-kpi'], 'Порт :443 закрыт', fmtNumber(closed),
            'по прямой пробе с main', 'ban', closed ? 'warn' : null);
        // Главная цифра экрана: расхождение между «панель видит ноду» и «порт снаружи открыт».
        kpi(el['nodes-kpi'], 'Подозрение на DPI', fmtNumber(data.dpiSuspects),
            data.dpiSuspects ? 'панель connected, а порт закрыт' : 'расхождений нет',
            'wifi', data.dpiSuspects ? 'danger' : null);

        var list = el['nodes-list'];
        list.textContent = '';
        if (!nodes.length) {
            var empty = document.createElement('div');
            empty.className = 'minitable__empty';
            empty.textContent = 'Панель не вернула ноды — недоступна либо список пуст.';
            list.appendChild(empty);
        }

        nodes.forEach(function (n) {
            var card = document.createElement('div');
            card.className = 'nodecard' + (n.dpiSuspect ? ' nodecard--alert' : '');

            var head = document.createElement('div');
            head.className = 'nodecard__head';
            var name = document.createElement('div');
            name.className = 'nodecard__name';
            name.textContent = n.name || '—';
            var badge = document.createElement('span');
            var tone = NODE_BADGE[String(n.status || '').toLowerCase()];
            badge.className = 'badge' + (tone ? ' ' + tone : '');
            badge.textContent = n.status || '?';
            head.appendChild(name);
            head.appendChild(badge);
            card.appendChild(head);

            var meta = document.createElement('div');
            meta.className = 'nodecard__meta';
            metaChip(meta, n.address || '—', 'mono');
            metaChip(meta, n.port443Open ? ':443 открыт' : ':443 закрыт',
                n.port443Open ? 'ok' : 'bad', 'Прямая TCP-проба с main, таймаут 2 с');
            if (n.coreVersion) {
                metaChip(meta, 'ядро ' + n.coreVersion, 'mute',
                    'Версия ядра на узле. Отставшее ядро ломает подключение у свежих клиентов '
                    + 'при полностью «зелёном» узле.');
            }
            if (n.nodeOnline) {
                metaChip(meta, n.nodeOnline + ' онлайн', 'ok',
                    'Подключений к этому узлу за 10 минут. У узлов, которые проверяют '
                    + 'подписчиков сами, такого следа нет.');
            }
            card.appendChild(meta);

            if (n.dpiSuspect) {
                var warn = document.createElement('p');
                warn.className = 'nodecard__warn';
                warn.textContent = 'Панель считает ноду живой, но :443 снаружи закрыт — похоже '
                    + 'на per-IP DPI на маршруте. На main: scripts/diag-node.sh '
                    + (n.address || '<IP>') + '. Если ICMP чист, а SYN дропается — тикет '
                    + 'хостингу на новый IP.';
                card.appendChild(warn);
            }
            if (n.message) {
                var msg = document.createElement('p');
                msg.className = 'nodecard__msg';
                msg.textContent = n.message;
                card.appendChild(msg);
            }
            list.appendChild(card);
        });

        miniRows(el['nodes-monitors'], (data.monitors || []).map(function (m) {
            // up === null означает «мониторинг не ответил». Показать это «недоступен» было бы
            // ровно той ложью, ради недопущения которой в ServerStatus и заведён признак known.
            var state = (m.up === null || m.up === undefined)
                ? 'неизвестно' : (m.up ? 'работает' : 'недоступен');
            var extra = [];
            if (m.uptime24Pct !== null && m.uptime24Pct !== undefined) {
                extra.push(fmtPercent(m.uptime24Pct) + ' за сутки');
            }
            if (m.pingMs !== null && m.pingMs !== undefined) {
                extra.push(Math.round(m.pingMs) + ' мс');
            }
            return [(m.flag ? m.flag + ' ' : '') + (m.name || '—'),
                state + (extra.length ? ' · ' + extra.join(' · ') : '')];
        }));

        el.stamp.textContent = 'опрошено ' + fmtDateTime(data.generatedAt) + ' МСК';
    }

    // ==============================================================================
    // Таблицы
    // ==============================================================================

    function currentParams(tab) {
        var params = {};
        if (tab.search && state.search) {
            params[tab.search.param] = state.search;
        }
        Object.keys(state.filters).forEach(function (key) {
            if (state.filters[key]) params[key] = state.filters[key];
        });
        return params;
    }

    function renderFilters(tab) {
        el.filters.textContent = '';
        (tab.filters || []).forEach(function (group) {
            group.options.forEach(function (option) {
                var chip = document.createElement('button');
                chip.type = 'button';
                chip.className = 'chip';
                chip.textContent = option.label;
                var active = (state.filters[group.param] || '') === option.value;
                if (active) chip.classList.add('is-active');
                chip.addEventListener('click', function () {
                    state.filters[group.param] = option.value;
                    state.page = 0;
                    // Перерисовать чипы обязательно: подсветку ставит только этот метод,
                    // а load() её не трогает — без вызова выбранный фильтр применялся к
                    // данным, но на экране активной оставалась кнопка «Все».
                    renderFilters(tab);
                    load();
                });
                el.filters.appendChild(chip);
            });
        });
    }

    function renderHead(tab) {
        el['grid-head'].textContent = '';
        tab.columns.forEach(function (column) {
            var th = document.createElement('th');
            th.textContent = column.title;
            if (column.sortable) {
                th.className = 'is-sortable';
                if (state.sort === column.key) {
                    var arrow = document.createElement('span');
                    arrow.className = 'arrow';
                    arrow.textContent = state.dir === 'asc' ? '↑' : '↓';
                    th.appendChild(arrow);
                }
                th.addEventListener('click', function () {
                    if (state.sort === column.key) {
                        state.dir = state.dir === 'asc' ? 'desc' : 'asc';
                    } else {
                        state.sort = column.key;
                        state.dir = 'desc';
                    }
                    state.page = 0;
                    load();
                });
            }
            el['grid-head'].appendChild(th);
        });
    }

    function renderRows(tab, rows) {
        el['grid-body'].textContent = '';
        if (!rows.length) {
            var tr = document.createElement('tr');
            var td = document.createElement('td');
            td.className = 'grid__empty';
            td.colSpan = tab.columns.length;
            td.textContent = 'Ничего не нашлось';
            tr.appendChild(td);
            el['grid-body'].appendChild(tr);
            return;
        }
        rows.forEach(function (row) {
            var tr = document.createElement('tr');
            if (row.telegramId) {
                tr.className = 'is-clickable';
                tr.title = 'Открыть карточку';
                tr.addEventListener('click', function () { openCard(row.telegramId); });
            }
            tab.columns.forEach(function (column) {
                tr.appendChild(cell(row[column.key], column.type, row));
            });
            el['grid-body'].appendChild(tr);
        });
    }

    function renderTable(tab, payload) {
        var rows = tab.plain ? payload : payload.items;
        var total = tab.plain ? payload.length : payload.total;
        var totalPages = tab.plain ? 1 : payload.totalPages;

        renderHead(tab);
        renderRows(tab, rows);

        el.count.textContent = 'строк: ' + fmtNumber(total);
        el.pageinfo.textContent = totalPages ? (state.page + 1) + ' / ' + totalPages : '1 / 1';
        el['prev-btn'].disabled = state.page <= 0;
        el['next-btn'].disabled = state.page + 1 >= totalPages;
        el['prev-btn'].hidden = tab.plain;
        el['next-btn'].hidden = tab.plain;
        el.pageinfo.hidden = tab.plain;
        el.stamp.textContent = 'данные на ' + fmtDateTime(new Date().toISOString().slice(0, 19)) + ' МСК';
    }

    // ==============================================================================
    // Карточка человека
    // ==============================================================================

    function facts(pairs) {
        var wrap = document.createElement('div');
        wrap.className = 'facts';
        pairs.forEach(function (pair) {
            if (pair[1] === '' || pair[1] === null || pair[1] === undefined) return;
            var box = document.createElement('div');
            var k = document.createElement('div');
            k.className = 'fact__key';
            k.textContent = pair[0];
            var v = document.createElement('div');
            v.className = 'fact__val' + (pair[2] ? ' mono' : '');
            v.textContent = String(pair[1]);
            box.appendChild(k);
            box.appendChild(v);
            wrap.appendChild(box);
        });
        return wrap;
    }

    function cardSection(title, note, node) {
        var panel = document.createElement('div');
        panel.className = 'panel';
        var head = document.createElement('div');
        head.className = 'panel__head';
        var h = document.createElement('h2');
        h.textContent = title;
        head.appendChild(h);
        if (note) {
            var p = document.createElement('p');
            p.className = 'panel__note';
            p.textContent = note;
            head.appendChild(p);
        }
        panel.appendChild(head);
        panel.appendChild(node);
        return panel;
    }

    function miniGrid(columns, rows) {
        var wrap = document.createElement('div');
        wrap.className = 'tablewrap';
        var table = document.createElement('table');
        table.className = 'grid';
        var thead = document.createElement('thead');
        var headRow = document.createElement('tr');
        columns.forEach(function (column) {
            var th = document.createElement('th');
            th.textContent = column.title;
            headRow.appendChild(th);
        });
        thead.appendChild(headRow);
        var tbody = document.createElement('tbody');
        if (!rows.length) {
            var tr = document.createElement('tr');
            var td = document.createElement('td');
            td.className = 'grid__empty';
            td.colSpan = columns.length;
            td.textContent = 'Пусто';
            tr.appendChild(td);
            tbody.appendChild(tr);
        } else {
            rows.forEach(function (row) {
                var tr = document.createElement('tr');
                columns.forEach(function (column) {
                    tr.appendChild(cell(row[column.key], column.type, row));
                });
                tbody.appendChild(tr);
            });
        }
        table.appendChild(thead);
        table.appendChild(tbody);
        wrap.appendChild(table);
        return wrap;
    }

    function openCard(telegramId) {
        el.drawer.hidden = false;
        el['card-title'].textContent = 'Загрузка…';
        el['card-sub'].textContent = String(telegramId);
        el['card-body'].textContent = '';
        api('/users/' + telegramId).then(function (card) {
            var u = card.user;
            el['card-title'].textContent = (u.telegramUsername ? '@' + u.telegramUsername : 'Без username');
            el['card-sub'].textContent = 'telegram id ' + u.telegramId
                + (u.providerUsername ? ' · ' + u.providerUsername : '');

            el['card-body'].textContent = '';
            el['card-body'].appendChild(cardSection('Человек', null, facts([
                ['Статус', u.status],
                ['Истекает', fmtDateTime(u.expiresAt) || '—'],
                ['Осталось', fmtDaysLeft(u.daysLeft) || '—'],
                ['Пришёл', fmtDateTime(u.createdAt)],
                ['Триал использован', fmtDateTime(u.trialUsedAt) || 'нет'],
                ['E-mail', u.email || 'нет'],
                ['E-mail подтверждён', fmtDateTime(card.emailVerifiedAt) || 'нет'],
                ['Согласие на обработку', fmtDateTime(card.emailConsentAt) || 'нет'],
                ['Ступень устройств', u.deviceTierId || '—'],
                ['Лимит устройств', u.deviceLimit === null ? 'по умолчанию' : u.deviceLimit],
                ['Устройств заведено', u.devices],
                ['Обновлял конфиг', u.lastSeenAt ? fmtDateTime(u.lastSeenAt) : 'никогда'],
                ['Онлайн (hy2)', u.onlineNow ? 'да' : 'нет'],
                ['Автопродление', u.autorenewEnabled ? 'включено' : 'выключено'],
                ['Карта привязана', u.cardBound ? 'да' : 'нет'],
                ['Тариф автопродления', u.autorenewPlanId || '—'],
                ['Последний сбой автосписания', fmtDateTime(card.autorenewLastFailureAt) || 'нет'],
                ['Оплат успешных', u.paymentsCount],
                ['Сумма оплат', fmtMoney(u.paidKopeks)],
                ['Последняя оплата', fmtDateTime(u.lastPaidAt) || '—'],
                ['Пригласил его', card.invitedBy
                    ? (card.invitedBy.referrerUsername ? '@' + card.invitedBy.referrerUsername
                        : String(card.invitedBy.referrerTelegramId)) : '—'],
                ['Привёл людей', card.invitedCount + ' (оплатили ' + card.invitedPaidCount + ')'],
                ['Версия сессии кабинета', card.sessionVersion, true],
                ['Уведомления 24ч / 1ч / winback',
                    (card.notified24h ? 'да' : 'нет') + ' / ' + (card.notified1h ? 'да' : 'нет')
                    + ' / ' + (card.winbackSent ? 'да' : 'нет')],
                ['Ссылка подписки', card.subscriptionLink || '—', true]
            ])));

            // Панель AccessProvider — отдельным запросом: это единственный поход наружу, и карточка
            // не должна его ждать. Место под неё занимаем сразу, чтобы блок не прыгал.
            var panelBox = document.createElement('div');
            panelBox.className = 'minitable__empty';
            panelBox.textContent = 'Спрашиваем провайдера…';
            el['card-body'].appendChild(cardSection('Провайдер доступа',
                'Активность аккаунта видит только провайдер — в нашей базе такого следа нет.',
                panelBox));
            loadPanelView(telegramId, panelBox);

            el['card-body'].appendChild(cardSection('Подписки', null, miniGrid([
                col('seq', '№', 'num'), col('label', 'Название', 'text'),
                col('current', 'Текущая', 'bool'), col('state', 'Состояние', 'badge'),
                col('origin', 'Откуда', 'text'), col('expiresAt', 'Истекает', 'datetime'),
                col('balanceSeconds', 'Заморожено', 'seconds'),
                col('deviceTierId', 'Ступень', 'mono'),
                col('providerUsername', 'Аккаунт', 'mono'),
                col('lastActivatedAt', 'Включена', 'datetime')
            ], card.subscriptions)));

            el['card-body'].appendChild(cardSection('Платежи', null, miniGrid([
                col('id', 'ID', 'mono'), col('createdAt', 'Создан', 'datetime'),
                col('paidAt', 'Оплачен', 'datetime'), col('status', 'Статус', 'badge'),
                col('provider', 'Способ', 'text'), col('amountKopeks', 'Сумма', 'money'),
                col('planId', 'Тариф', 'mono'), col('promoCodeUsed', 'Промо', 'mono'),
                col('refundedAt', 'Возврат', 'datetime')
            ], card.payments)));

            el['card-body'].appendChild(cardSection('Устройства',
                'Время — последнее обращение к ссылке подписки, не последний трафик.', miniGrid([
                    col('customName', 'Имя', 'text'), col('deviceModel', 'Модель', 'text'),
                    col('deviceOs', 'ОС', 'text'), col('osVersion', 'Версия', 'mono'),
                    col('status', 'Статус', 'badge'), col('lastSeenAt', 'Обновлял конфиг', 'ago'),
                    col('firstSeenAt', 'Впервые', 'datetime'), col('userAgent', 'Клиент', 'text'),
                    col('hwid', 'HWID', 'mono')
                ], card.devices)));

            el['card-body'].appendChild(cardSection('Журнал секунд', 'Последние 100 записей.', miniGrid([
                col('occurredAt', 'Когда', 'datetime'), col('subscriptionSeq', '№', 'num'),
                col('kind', 'Событие', 'badge'), col('deltaSeconds', 'Изменение', 'seconds'),
                col('balanceAfterSeconds', 'Остаток', 'seconds'),
                col('paymentId', 'Платёж', 'mono'), col('note', 'Примечание', 'wrap')
            ], card.ledger)));
        }).catch(function (e) {
            el['card-title'].textContent = 'Не открылось';
            el['card-sub'].textContent = String(e.message || e);
        });
    }

    function loadPanelView(telegramId, box) {
        api('/users/' + telegramId + '/panel').then(function (panel) {
            box.textContent = '';
            if (!panel.available) {
                box.className = 'minitable__empty';
                // Клиент панели отвечает пустотой и на «нет аккаунта», и на сбой связи —
                // различить нельзя, и делать вид, что можно, хуже, чем сказать прямо.
                box.textContent = 'Панель не ответила либо аккаунта в ней нет — различить нельзя.';
                return;
            }
            box.className = '';
            box.appendChild(facts([
                ['Аккаунт', panel.username, true],
                ['Статус в панели', panel.status],
                ['Последнее соединение', panel.onlineAt ? fmtDateTime(panel.onlineAt) : 'панель не видела'],
                ['Подписку тянули', panel.subUpdatedAt ? fmtDateTime(panel.subUpdatedAt) : '—'],
                ['Клиент', panel.subLastUserAgent || '—'],
                ['Трафик', fmtBytes(panel.usedTrafficBytes) || '0'],
                ['Лимит трафика', panel.dataLimitBytes ? fmtBytes(panel.dataLimitBytes) : 'без лимита'],
                ['Срок в панели', panel.expireEpoch
                    ? DATETIME.format(new Date(panel.expireEpoch * 1000)) : 'без срока']
            ]));
        }).catch(function () {
            box.className = 'minitable__empty';
            box.textContent = 'Панель не ответила.';
        });
    }

    function closeCard() {
        el.drawer.hidden = true;
        el['card-body'].textContent = '';
    }

    // ==============================================================================
    // Загрузка раздела
    // ==============================================================================

    function load() {
        var tab = tabById(state.tab);
        el.error.hidden = true;
        el.loading.hidden = false;
        el['view-overview'].hidden = true;
        el['view-nodes'].hidden = true;
        el['view-table'].hidden = true;

        if (tab.id === 'overview') {
            api('/overview').then(function (data) {
                renderOverview(data);
                el.loading.hidden = true;
                el['view-overview'].hidden = false;
            }).catch(showError);
            return;
        }

        if (tab.id === 'nodes') {
            api('/nodes').then(function (data) {
                renderNodes(data);
                el.loading.hidden = true;
                el['view-nodes'].hidden = false;
            }).catch(showError);
            return;
        }

        var params = currentParams(tab);
        if (!tab.plain) {
            params.page = state.page;
            params.size = state.size;
            if (state.sort) {
                params.sort = state.sort;
                params.dir = state.dir;
            }
        }
        el['export-btn'].href = API + '/export/' + tab.export + queryString(currentParams(tab));

        api(tab.path + queryString(params)).then(function (payload) {
            renderTable(tab, payload);
            el.loading.hidden = true;
            el['view-table'].hidden = false;
        }).catch(showError);
    }

    function showError(e) {
        el.loading.hidden = true;
        el.error.hidden = false;
        el.error.textContent = 'Не получилось загрузить: ' + (e && e.message ? e.message : e);
    }

    function selectTab(id) {
        var tab = tabById(id);
        state.tab = tab.id;
        state.page = 0;
        state.sort = tab.sort || null;
        state.dir = 'desc';
        state.search = '';
        state.filters = {};

        // Не по children: прямые дети #tabs — это группы, сами пункты лежат внутри них.
        Array.prototype.forEach.call(el.tabs.querySelectorAll('.navitem'), function (node) {
            var active = node.dataset.tab === tab.id;
            node.classList.toggle('is-active', active);
            node.setAttribute('aria-current', active ? 'page' : 'false');
        });

        el['view-title'].textContent = tab.title;
        closeSidebar();

        el.search.hidden = !tab.search;
        el.search.value = '';
        if (tab.search) el.search.placeholder = tab.search.placeholder || 'Поиск';
        el['table-hint'].hidden = !tab.hint;
        el['table-hint'].textContent = tab.hint || '';
        el['export-btn'].hidden = !tab.export;
        renderFilters(tab);

        if (window.location.hash !== '#' + tab.id) {
            window.history.replaceState(null, '', '#' + tab.id);
        }
        load();
    }

    // ==============================================================================
    // Инициализация
    // ==============================================================================

    NAV_GROUPS.forEach(function (group) {
        var box = document.createElement('div');
        box.className = 'navgroup';

        var title = document.createElement('div');
        title.className = 'navgroup__title';
        title.textContent = group.title;
        box.appendChild(title);

        group.items.forEach(function (id) {
            var tab = tabById(id);
            var button = document.createElement('button');
            button.type = 'button';
            button.className = 'navitem';
            button.dataset.tab = tab.id;

            var glyph = icon(tab.id, 'navitem__icon');
            var label = document.createElement('span');
            label.className = 'navitem__label';
            label.textContent = tab.title;

            button.appendChild(glyph);
            button.appendChild(label);
            button.addEventListener('click', function () { selectTab(tab.id); });
            box.appendChild(button);
        });

        el.tabs.appendChild(box);
    });

    // ---- Сайдбар на узком экране -------------------------------------------------
    // До 900 px рельса уезжает за край; открывают её бургер и закрывают крестик,
    // затемнение, Escape и — главное — сам выбор раздела (см. selectTab).

    function openSidebar() {
        el.sidebar.classList.add('is-open');
        el.scrim.hidden = false;
    }

    function closeSidebar() {
        el.sidebar.classList.remove('is-open');
        el.scrim.hidden = true;
    }

    el.burger.addEventListener('click', openSidebar);
    el['sidebar-close'].addEventListener('click', closeSidebar);
    el.scrim.addEventListener('click', closeSidebar);

    // ---- Часы --------------------------------------------------------------------
    // Панель считает всё в МСК (бэкенд отдаёт UTC, форматтеры выше переводят), поэтому
    // и часы в подвале — московские: иначе метка «данные на …» и часы разошлись бы.

    var CLOCK = new Intl.DateTimeFormat('ru-RU', {
        timeZone: 'Europe/Moscow', hour: '2-digit', minute: '2-digit', second: '2-digit'
    });

    function tickClock() { el.clock.textContent = CLOCK.format(new Date()); }

    tickClock();
    window.setInterval(tickClock, 1000);

    var searchTimer = null;
    el.search.addEventListener('input', function () {
        var tab = tabById(state.tab);
        var value = el.search.value.trim();
        if (tab.search && tab.search.numeric && value && !/^\d+$/.test(value)) {
            return;
        }
        window.clearTimeout(searchTimer);
        searchTimer = window.setTimeout(function () {
            state.search = value;
            state.page = 0;
            load();
        }, 300);
    });

    el['prev-btn'].addEventListener('click', function () {
        if (state.page > 0) { state.page--; load(); }
    });
    el['next-btn'].addEventListener('click', function () { state.page++; load(); });
    el['refresh-btn'].addEventListener('click', function () { load(); });
    el['drawer-close'].addEventListener('click', closeCard);
    el['drawer-backdrop'].addEventListener('click', closeCard);
    document.addEventListener('keydown', function (event) {
        if (event.key !== 'Escape') return;
        // Карточка лежит поверх сайдбара, поэтому закрывается первой: иначе Escape
        // из открытой карточки убирал бы меню под ней, а карточка оставалась висеть.
        if (!el.drawer.hidden) closeCard();
        else closeSidebar();
    });

    el['logout-btn'].addEventListener('click', function () {
        fetch(API + '/logout', {method: 'POST', credentials: 'same-origin'})
            .then(function () { window.location.replace(LOGIN); })
            .catch(function () { window.location.replace(LOGIN); });
    });

    selectTab((window.location.hash || '').replace('#', '') || 'overview');
}());
