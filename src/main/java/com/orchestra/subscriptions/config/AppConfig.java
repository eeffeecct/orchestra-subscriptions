package com.orchestra.subscriptions.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Data
@Configuration
@ConfigurationProperties(prefix = "app")
public class AppConfig {

    private AccessProviderConfig provider;
    private TelegramConfig telegram;
    private YooKassaConfig yookassa;
    private CryptoBotConfig cryptobot;
    private EmailConfig email = new EmailConfig();
    private EmojiConfig emoji = new EmojiConfig();
    private List<Plan> plans;
    /**
     * Тарифные ступени по числу устройств (1/2/3 + семейные до 5/до 10). Итоговая цена =
     * {@link #tierPriceKopeks} (цена плана × множитель, округление до 10 ₽). Пустой список /
     * не задано — фича выключена: боты/Mini App продают планы как раньше, лимит устройств
     * не назначается. Как и {@link #plans}, НЕ входит в горячий набор /reloadconfig —
     * цены остаются за деплоем/ревью.
     */
    private List<DeviceTier> deviceTiers;
    /**
     * Окно активности устройства в днях: устройство, не обновлявшее подписку дольше этого
     * срока, не занимает слот лимита (телефон продали/сбросили — слот освобождается сам).
     */
    private int deviceInactivityDays = 30;
    /**
     * Лимит устройств для триала. Назначается новому trial-юзеру при создании.
     * 0 = без лимита. Дефолт кода — 0 (фича выключена, поведение как до неё);
     * прод-значение задаётся в application.yml ({@code app.trial-device-limit}).
     */
    private int trialDeviceLimit = 0;
    /**
     * Лимит для юзеров без явного {@code customers.device_limit} (купили до фичи).
     * 0 = не ограничивать legacy-юзеров (безопасный дефолт — старые обещания «до 5 устройств»
     * не нарушаем, enforcement включится для них после первой покупки с выбором тарифа).
     */
    private int defaultDeviceLimit = 0;
    /**
     * Порог owner-алерта «аномально много HWID на одной подписке» (перепродажа/утечка ссылки):
     * при регистрации устройства с общим числом строк ≥ порога владельцу уходит DM
     * (не чаще 1/час на юзера). 0 = алерт выключен.
     */
    private int deviceAnomalyThreshold = 10;
    /** Докупка «+1 устройство» к активной подписке (до конца оплаченного срока). */
    private DeviceAddonConfig deviceAddon = new DeviceAddonConfig();
    /**
     * Адреса собственных прокси, стоящих ПЕРЕД приложением (кроме локального реверс-прокси).
     * Используется {@link com.orchestra.subscriptions.web.api.ClientIpResolver}: такие адреса
     * пропускаются при поиске реального IP клиента в {@code X-Forwarded-For}, иначе все
     * пользователи, пришедшие через фронт, схлопываются в один адрес и упираются в общий
     * rate-limit. Пусто — берётся самый правый элемент XFF.
     */
    private List<String> trustedProxyIps = new java.util.ArrayList<>();
    /**
     * Токен, которым узел-релей подписывает отчёты о живых соединениях
     * ({@code POST /api/internal/node-stats}, см. {@link com.orchestra.subscriptions.web.api.NodeStatsController}).
     * Не задан — эндпоинт отвечает 404, отчёты не принимаются. Задаётся env {@code NODE_STATS_TOKEN}.
     */
    private String nodeStatsToken;
    /**
     * Человеческие имена входов релея: порт на релее → как показывать в {@code /online}.
     * Порт не найден в карте — покажем сам порт.
     */
    private Map<String, String> relayInboundNames = new HashMap<>();
    /**
     * Человеческие имена узлов раздачи: адрес узла → как показывать в {@code /online}.
     * Узлы спрашивают авторизацию со своего адреса, по нему и определяем, кто где сидит.
     */
    private Map<String, String> nodeNames = new HashMap<>();
    /** Персональная авторизация подписчиков на узлах раздачи. */
    private NodeAuthConfig nodeAuth = new NodeAuthConfig();
    /** Откуда берётся статус нод для экрана «Статус серверов» — публичная страница Uptime Kuma. */
    private StatusPageConfig statusPage = new StatusPageConfig();
    /**
     * Путь к внешнему «горячему» конфигу (монтируется на прод-сервере, напр.
     * {@code /opt/subscription-service/config/app-dynamic.yml}). Файл содержит те же ключи {@code app.*}, что и
     * volatile-поля ниже ({@code servers}).
     * Правится вживую → owner-команда {@code /reloadconfig} перечитывает файл и копирует значения в
     * <b>этот</b> бин без пересборки образа (см. {@link com.orchestra.subscriptions.service.DynamicConfigService}).
     * Null / файла нет → используются запечённые в образ значения (dev/local работает как раньше).
     */
    private String dynamicConfigPath;
    /** ↓ Volatile: правится вживую через {@link com.orchestra.subscriptions.service.DynamicConfigService}
     *  по {@code /reloadconfig}. {@code volatile} — чтобы потоки Tomcat сразу видели новые значения. */
    private volatile List<ServerConfig> servers;
    public Plan findPlan(String id) {
        return findPlanOptional(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown plan: " + id));
    }

    /**
     * «Мягкий» поиск тарифа для не-валидационных путей (карточки юзера, статус платежа,
     * история): пусто, если план отсутствует или удалён из конфига. Для жёсткой валидации
     * пользовательского ввода — {@link #findPlan}, который бросает {@link IllegalArgumentException}.
     */
    public Optional<Plan> findPlanOptional(String id) {
        if (plans == null || id == null) {
            return Optional.empty();
        }
        return plans.stream()
                .filter(p -> p.getId().equals(id))
                .findFirst();
    }

    /** Фича «тарифы по устройствам» активна: список ступеней задан и непуст. */
    public boolean deviceTiersEnabled() {
        return deviceTiers != null && !deviceTiers.isEmpty();
    }

    /**
     * Базовая ступень — та, чей множитель 1.0, т.е. её цена РАВНА цене плана
     * ({@link #tierPriceKopeks}). Нужна там, где ступень явно не выбирают, но списывают
     * базовую цену (web-ЛК): юзер должен получить ровно столько устройств, за сколько
     * заплатил. Фича выключена / базовой ступени нет — {@code empty}.
     */
    public Optional<DeviceTier> baseDeviceTier() {
        if (deviceTiers == null) {
            return Optional.empty();
        }
        return deviceTiers.stream()
                .filter(t -> t.getPriceMultiplier() == 1.0)
                .findFirst();
    }

    /** «Мягкий» поиск ступени — для карточек/истории, где ступень могла быть удалена из конфига. */
    public Optional<DeviceTier> findDeviceTierOptional(String id) {
        if (deviceTiers == null || id == null) {
            return Optional.empty();
        }
        return deviceTiers.stream()
                .filter(t -> t.getId().equals(id))
                .findFirst();
    }

    /**
     * Цена план×ступень в копейках: {@code plan.priceKopeks × tier.priceMultiplier},
     * округление до 5 ₽ (500 копеек). Множитель 1.0 (базовая ступень, d2 «2 устройства»)
     * возвращает цену плана КАК ЕСТЬ — «психологические» цены типа 179 ₽ не должны
     * превращаться в 180 округлением. {@code tier == null} (legacy-покупка без выбора
     * устройств) — тоже базовая цена плана.
     * Stars отдельно не считаем: их выводит {@link com.orchestra.subscriptions.util.StarsAmount#compute}
     * пропорционально от этой же суммы — так pre-checkout сверка остаётся консистентной.
     * ⚠️ Формула продублирована в Mini App (script.js, tierPriceRub) — менять синхронно.
     */
    public long tierPriceKopeks(Plan plan, DeviceTier tier) {
        if (tier == null || tier.getPriceMultiplier() == 1.0) {
            return plan.getPriceKopeks();
        }
        long rounded = Math.round(plan.getPriceKopeks() * tier.getPriceMultiplier() / 500.0) * 500L;
        return Math.max(500L, rounded);
    }

    @Data
    public static class AccessProviderConfig {
        private String baseUrl;
        private String username;
        private String password;
        /**
         * Публичный URL-префикс подписочных ссылок. Провайдер может вернуть относительный путь
         * {@code /sub/<token>} — приложение дописывает этот префикс при создании аккаунта и
         * сохраняет полный URL в базе ({@code customers.subscription_link}). По нему же клиент
         * потом ходит в {@link com.orchestra.subscriptions.web.AccessDeliveryController}, поэтому
         * домен обязан отдавать {@code /sub/*} наружу — иначе подписка приходит пустой.
         */
        private String subscriptionUrlPrefix;
        /**
         * Максимум одновременных устройств на подписку по умолчанию. Передаётся провайдеру
         * при создании и продлении аккаунта; ступени тарифа перекрывают его своим значением.
         * <p>
         * ⚠️ Это подсказка провайдеру, а НЕ гарантия: поддерживает ли он такой лимит, зависит
         * от реализации, и молчаливое игнорирование поля выглядит как работающее ограничение.
         * Реальный потолок держит учёт устройств в приложении ({@code DeviceTrackingService}).
         */
        private int deviceLimit = 5;
    }

    @Data
    public static class TelegramConfig {
        private String botToken;
        private String botUsername;
        /** Ссылка на Telegraph-статью-инструкцию (Mini App «Инструкции» + письмо после оплаты). */
        private String guideUrl;
        /**
         * Telegram ID владельца бота (единственный админ). Используется для авторизации
         * админских команд (/stats, /user, /extend, /disable, /broadcast).
         * Null = админ-команды никому не доступны (silent ignore).
         */
        private Long ownerId;
        /**
         * URL публичной оферты. Команда /terms и кнопка «Оферта» отдают эту ссылку.
         * Null/пусто — кнопка скрыта, /terms показывает «оферта в подготовке».
         */
        private String offerUrl;
        /**
         * Метка редакции оферты — обычно дата, «2026-08-21». Пишется в
         * {@code offer_acceptances.offer_revision} при акцепте.
         * <p>
         * Нужна потому, что {@link #offerUrl} редакцию не фиксирует: адрес на Telegraph
         * постоянный, а текст по нему правится на месте, и «согласился по этому адресу» после
         * первой же правки перестаёт что-либо доказывать. ⚠️ Менять ОДНОВРЕМЕННО с текстом
         * оферты — иначе метка врёт увереннее, чем её отсутствие.
         */
        private String offerRevision;
        /**
         * Публичный base URL бэкенда: из него строятся абсолютные ссылки на наши эндпоинты —
         * выдача доступа, magic-link, веб-кабинет, адрес возврата после оплаты. Значение по
         * умолчанию синхронизировано со списком разрешённых origin в {@code WebAppCorsConfig}.
         */
        private String publicBaseUrl = "https://example.com";
        /**
         * Base URL ТОЛЬКО для кнопок открытия Mini App (кнопка-меню ☰ и инлайн «Открыть
         * Orchestra»). Вынесен отдельно от {@link #publicBaseUrl}, чтобы можно было увести
         * ОДИН лишь Mini App на другой домен, не трогая выдачу доступа, веб-кабинет и
         * magic-link — они остаются на {@link #publicBaseUrl}. Null/пусто — фолбэк на
         * publicBaseUrl. Задаётся env {@code MINI_APP_BASE_URL}.
         */
        private String miniAppBaseUrl;

        /**
         * {@code file_id} ролика «как подключиться», который бот прикладывает к экрану помощи.
         * Пусто — экран работает как раньше, просто без видео.
         * <p>
         * Именно {@code file_id}, а не путь к файлу: залитое в Telegram видео получает
         * постоянный идентификатор, и дальше бот отправляет его без загрузки — мгновенно и без
         * трафика. Иначе каждый показ экрана означал бы выгрузку мегабайтов с нашего сервера.
         * Узнать идентификатор: прислать боту ролик — владельцу он ответит готовой строкой
         * (см. {@code AdminCommandHandler.handleVideoFileId}). Задаётся env {@code HOW_TO_VIDEO_ID}.
         * <p>
         * ⚠️ Значение может нести префикс {@code animation:} — mp4 без звуковой дорожки Telegram
         * считает гифкой, и её {@code file_id} через {@code sendVideo} не отправляется. Разбор
         * префикса — в {@code ScreenHandler.HOW_TO_ANIMATION_PREFIX}.
         */
        private String howToVideoId;

        /**
         * Base URL для кнопок Mini App: {@link #miniAppBaseUrl}, а если не задан — фолбэк на
         * {@link #publicBaseUrl}. Кастомный геттер — Lombok его не переопределяет.
         */
        public String getMiniAppBaseUrl() {
            return (miniAppBaseUrl == null || miniAppBaseUrl.isBlank())
                    ? publicBaseUrl
                    : miniAppBaseUrl;
        }
        /**
         * Telegram-канал с новостями сервиса. Если задан — в /start появляется кнопка
         * «📢 Новости» и в Mini App Support экране тоже линк. Null/пусто — кнопки скрыты.
         */
        private String newsChannelUrl;
        /**
         * Канал, подписка на который обязательна для входа в бот: {@code @username} или
         * числовой id ({@code -100…}) для закрытого. Пусто — гейт выключен, бот работает
         * как раньше. Задаётся env {@code REQUIRED_CHANNEL}.
         * <p>
         * ⚠️ Проверка ({@code getChatMember}) работает, ТОЛЬКО пока бот — <b>администратор</b>
         * канала. Снимут права — проверка начнёт падать, и гейт пропустит всех (fail-open,
         * см. {@code ChannelMembershipService}). Признак в логах:
         * {@code Channel membership check failed}.
         * <p>
         * ⚠️ Не входит в горячий набор {@code /reloadconfig} — смена требует рестарта.
         */
        private String requiredChannel;
        /**
         * Ссылка кнопки «Подписаться» на экране гейта. Пусто — выводится из
         * {@link #requiredChannel} ({@code @name} → {@code https://t.me/name}), а для
         * числового id — фолбэк на {@link #newsChannelUrl}. Задавать вручную нужно для
         * закрытого канала: туда ведёт invite-ссылка, которую из id не собрать.
         * Задаётся env {@code REQUIRED_CHANNEL_URL}.
         */
        private String requiredChannelUrl;

        /**
         * Куда ведёт кнопка «Подписаться»: {@link #requiredChannelUrl}, иначе адрес,
         * выведенный из {@code @username}, иначе {@link #newsChannelUrl}. Кастомный
         * геттер — Lombok его не переопределяет.
         * <p>
         * Пустой результат означает, что человеку некуда нажать, и гейт в этом случае
         * считается выключенным (см. {@code ChannelGateHandler.allowed}) — экран-тупик хуже
         * открытого входа.
         */
        public String getRequiredChannelJoinUrl() {
            if (requiredChannelUrl != null && !requiredChannelUrl.isBlank()) {
                return requiredChannelUrl;
            }
            if (requiredChannel != null && requiredChannel.startsWith("@") && requiredChannel.length() > 1) {
                return "https://t.me/" + requiredChannel.substring(1);
            }
            return newsChannelUrl;
        }
        /**
         * Контакт поддержки для экрана «Поддержка» в боте (кнопка «Написать в поддержку»).
         * Обычно ссылка на support-бота или личку: https://t.me/&lt;username&gt;. Null/пусто —
         * кнопка скрыта, показывается только текст.
         */
        private String supportUrl;
    }

    @Data
    public static class YooKassaConfig {
        private String shopId;
        private String secretKey;
        private String returnUrl;
        /**
         * Поддерживает ли магазин рекуррентные платежи (save_payment_method=true плюс списания
         * по инициативе магазина). Новым магазинам эквайринг обычно закрывает это 403-м до
         * отдельной заявки. Пока флаг false — UI автопродления скрыт, планировщик не списывает.
         */
        private boolean recurrentEnabled = false;
        /**
         * HTTP-прокси до API эквайринга. Пусто = ходим напрямую, это обычный режим.
         * Нужен только там, где прямой сетевой путь до платёжного шлюза недоступен
         * и трафик приходится выпускать через отдельный узел.
         */
        private String proxyHost;
        private int proxyPort = 3128;
    }

    @Data
    public static class CryptoBotConfig {
        /** API base URL. Flip to https://testnet-pay.crypt.bot/api/ for testnet. */
        private String baseUrl = "https://pay.crypt.bot/api/";
        /** Crypto Pay API token (from @CryptoBot → Crypto Pay → My Apps). */
        private String token;
        /** Accepted assets, comma-separated. Passed as-is in createInvoice.accepted_assets. */
        private String acceptedAssets = "USDT,TON,BTC,ETH";
        /**
         * Deep-link shown on CryptoBot's success screen — users tap it to return to our bot.
         * Typically https://t.me/&lt;bot_username&gt;.
         */
        private String paidBtnUrl;
    }

    @Data
    public static class EmailConfig {
        /**
         * From-адрес исходящих писем. Domain должен быть верифицирован у выбранного SMTP-провайдера
         * (SPF + DKIM + DMARC). Меняется через ENV
         * EMAIL_FROM при переезде между провайдерами + обязательно перевыпуск DKIM-ключа в DNS.
         */
        private String from = "noreply@example.com";
        /**
         * Master-switch. false (дефолт) — EmailService логирует «would send», но не шлёт
         * через SMTP. Безопасный режим для dev: случайные письма при локальных тестах исключены.
         * На проде задайте EMAIL_ENABLED=true, иначе ни одна email-фича не работает.
         */
        private boolean enabled = false;
    }

    /**
     * Анимированные Telegram-эмодзи (custom emoji) в сообщениях бота. Рендерятся через
     * {@link com.orchestra.subscriptions.util.Emoji}. Работают только при Telegram Premium у аккаунта-
     * владельца бота; у остальных показывается обычный unicode-фолбэк.
     */
    @Data
    public static class EmojiConfig {
        /** Мастер-switch. false (дефолт) — везде обычные эмодзи, никаких {@code <tg-emoji>}. */
        private boolean enabled = false;
        /**
         * semantic-ключ → custom_emoji_id. Ключи см. в {@link com.orchestra.subscriptions.util.Emoji}
         * (dolphin, rocket, chart…). Пустая карта / отсутствие ключа = обычный эмодзи.
         * id берётся owner-командой {@code /emojiid}.
         */
        private Map<String, Long> ids = new HashMap<>();
    }

    @Data
    public static class Plan {
        private String id;
        private String name;
        private int days;
        private long priceKopeks;
        /**
         * Цена в Telegram Stars. Выставляется с наценкой ~40% к {@link #priceKopeks},
         * чтобы компенсировать 30% комиссию Telegram/Apple/Google. Stars выводятся через @Fragment
         * (TON), ещё ~1-2% на конверсию. Нетто-выручка со Stars ≈ сумме в ₽ с обычной оплаты.
         * 0 = оплата Stars для этого плана отключена.
         */
        private long priceStars;
        private String description;
        /** Бейдж «Популярный» в витрине Mini App (conversion-фокус: middle-tier bias). Цену не меняет. */
        private boolean popular = false;
    }

    /**
     * Ступень тарифа по числу устройств. Цена ступени НЕ хранится напрямую — она выводится
     * из цены плана через {@link #tierPriceKopeks} (множитель; ×1.0 = база как есть,
     * остальные округляются до 5 ₽), чтобы не поддерживать матрицу 4×5 руками.
     */
    @Data
    public static class DeviceTier {
        private String id;
        /** Название для кнопок/карточек: «1 устройство», «Семейная · до 5 устройств». */
        private String name;
        /** Сколько HWID-слотов даёт ступень (= {@code customers.device_limit} после оплаты). */
        private int devices;
        /** Множитель к цене плана. 1.0 = базовая цена (без округления). */
        private double priceMultiplier = 1.0;
        /** Семейная ступень — для бейджа/группировки в UI. */
        private boolean family = false;
        /** Рекомендуемая ступень: предвыбор в Mini App + пометка в бот-меню. */
        private boolean popular = false;
        /**
         * Явный AccessProvider {@code ip_limit} для этой ступени. Null — дефолт {@code devices + 1}
         * (устройство при смене сети может коротко светиться с двух IP).
         * ⚠️ AccessProvider 0.8.4 поле игнорирует (см. {@code AccessProviderConfig#ipLimit}) — никакой
         * страховки на уровне соединений это не даёт, лимит держит только HWID-учёт.
         */
        private Integer providerDeviceLimit;

        /** Эффективный AccessProvider ip_limit ступени: явный override или {@code devices + 1}. */
        public int effectiveProviderDeviceLimit() {
            return providerDeviceLimit != null ? providerDeviceLimit : devices + 1;
        }
    }

    /**
     * Докупка «+1 устройство» к УЖЕ активной подписке — pro-rata до конца оплаченного срока.
     * При продлении лимит выставляется заново из ступени тарифа, докупленные слоты сгорают
     * (это прямо написано в UI). Доступна только юзерам с выбранной ступенью
     * ({@code device_tier_id != null}) — legacy/триал сначала покупают тариф.
     */
    @Data
    public static class DeviceAddonConfig {
        /** Цена слота в копейках за 30 дней. 0 = фича выключена. */
        private long priceKopeksPerMonth = 0;
        /**
         * Stars-эквивалент месячной цены (та же наценка ~40%/1.17, что у планов).
         * Итог считается пропорционально через {@code StarsAmount.compute} от той же
         * рублёвой суммы — pre-checkout сверка сходится автоматически.
         */
        private long priceStarsPerMonth = 0;
        /** Сколько слотов можно докупить СВЕРХ ступени тарифа. */
        private int maxExtraDevices = 5;

        public boolean enabled() {
            return priceKopeksPerMonth > 0;
        }

        /**
         * Цена слота на {@code remainingDays} дней: pro-rata от месячной, округление до 1 ₽
         * (сохраняет «психологические» 39 ₽ на полном месяце), минимум 10 ₽ (совсем
         * бесплатные хвосты не продаём — комиссия провайдера съест).
         */
        public long priceKopeks(long remainingDays) {
            long days = Math.max(1, remainingDays);
            long rounded = Math.round(priceKopeksPerMonth * days / 30.0 / 100.0) * 100L;
            return Math.max(1000L, rounded);
        }
    }

    /**
     * Персональная авторизация подписчиков на узлах раздачи (см.
     * {@link com.orchestra.subscriptions.service.NodeAuthService}). Пустой {@code authSecret} —
     * фича выключена: узлы проверяют подписчиков собственными средствами, а эндпоинт
     * {@code /node/auth/*} отвечает 404.
     */
    @Data
    public static class NodeAuthConfig {
        /** Ключ HMAC, из которого выводятся учётные данные подписчика. */
        private String authSecret;
        /**
         * Неугадываемый сегмент пути эндпоинта авторизации: узел может не уметь слать
         * кастомные заголовки, поэтому «свой» подтверждается самим адресом.
         */
        private String authPathSecret;
    }

    /**
     * Публичная статус-страница Uptime Kuma как источник статуса нод.
     * <p>
     * Своё мнение о живости нод сервис больше не имеет: TCP-проба с main не умела проверять
     * UDP-протоколом и подменялась флагом «всегда активна». Подробности — в {@code application.yml}
     * рядом с ключом и в {@code docs/monitoring.md}.
     */
    @Data
    public static class StatusPageConfig {
        /** Выключено — экран покажет «нет данных», а не выдуманное «Активен». */
        private boolean enabled = true;
        /** Адрес Kuma. app работает в network_mode: host, поэтому это localhost хоста. */
        private String baseUrl;
        /** Slug публичной страницы: {@code /api/status-page/<slug>}. */
        private String slug;
        /** Какую группу мониторов показывать людям; пусто — все. */
        private String nodeGroup;
    }

    @Data
    public static class ServerConfig {
        /** Отображаемое название сервера (страна), например «Регион A». */
        private String name;
        /** Эмодзи-флаг для строки в списке. */
        private String flag;
        /**
         * Адрес ноды. Инвентарь: статус экрана «Статус серверов» приходит из Uptime Kuma, код
         * это поле не читает (см. {@link com.orchestra.subscriptions.service.ServerStatusService}).
         */
        private String host;
        /** Порт ноды. Как и {@link #host} — инвентарь, кодом не используется. */
        private int port = 443;
    }
}
