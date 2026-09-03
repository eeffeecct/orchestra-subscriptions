package com.orchestra.subscriptions.bot;

import com.orchestra.subscriptions.config.AppConfig;
import com.orchestra.subscriptions.model.Payment;
import com.orchestra.subscriptions.model.Customer;
import com.orchestra.subscriptions.service.AdminService;
import com.orchestra.subscriptions.service.BroadcastService;
import static com.orchestra.subscriptions.service.BroadcastService.TEXT_LIMIT;
import com.orchestra.subscriptions.service.DynamicConfigService;
import com.orchestra.subscriptions.service.EmailService;
import com.orchestra.subscriptions.service.NotificationService;
import com.orchestra.subscriptions.service.PromoCodeService;
import com.orchestra.subscriptions.util.Entities;
import com.orchestra.subscriptions.util.IkButton;
import com.orchestra.subscriptions.util.IkMarkup;
import com.orchestra.subscriptions.util.IkSendMessage;
import com.orchestra.subscriptions.util.Money;
import com.orchestra.subscriptions.util.TimeFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.Video;
import org.telegram.telegrambots.meta.api.objects.games.Animation;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Owner-only админ-команды и admin-callback'и, вынесенные из god-class SubscriptionBot (PR-2).
 * SubscriptionBot гейтит доступ ({@code isOwner}) и делегирует сюда; данные — из AdminService /
 * PromoCodeService / BroadcastService, отправка — через {@link TelegramSender} (мокается
 * в тестах). Поведение не менялось — это move-not-rewrite.
 * <p>
 * {@code @Lazy} в SubscriptionBot разрывает цикл SubscriptionBot → AdminCommandHandler → NotificationService
 * → SubscriptionBot, поэтому сам хендлер — обычный eager {@code @RequiredArgsConstructor}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminCommandHandler {

    private final AppConfig appConfig;
    private final AdminService adminService;
    private final PromoCodeService promoCodeService;
    private final BroadcastService broadcastService;
    private final NotificationService notificationService;
    private final EmailService emailService;
    private final DynamicConfigService dynamicConfigService;
    private final com.orchestra.subscriptions.service.DeviceTrackingService deviceTrackingService;
    private final com.orchestra.subscriptions.service.OnlineTracker onlineTracker;
    private final com.orchestra.subscriptions.service.SubscriptionLedgerService subscriptionLedgerService;
    private final com.orchestra.subscriptions.service.SubscriptionRegistryService subscriptionRegistryService;
    private final com.orchestra.subscriptions.service.SubscriptionSwitchService subscriptionSwitchService;
    /** Нужен {@code /ledger}: перевести id подписки в её номер, видимый человеку. */
    private final com.orchestra.subscriptions.repository.SubscriptionRepository subscriptionRepository;
    /** Одноразовые ссылки входа в веб-админку ({@code /admin}). */
    private final com.orchestra.subscriptions.service.AdminLoginTokenService adminLoginTokenService;
    private final TelegramSender telegramSender;

    /**
     * Пытается обработать сообщение как админ-команду. Вернёт true если распознал команду
     * (даже если аргументы невалидны — в этом случае отправит usage-месседж). false — пусть
     * дальше обрабатывает обычный switch (может быть, это /start от самого админа).
     */
    public boolean tryHandleAdminCommand(String text, Long chatId) throws TelegramApiException {
        return tryHandleAdminCommand(text, chatId, null);
    }

    /**
     * То же самое, но с исходным сообщением. Оно нужно ровно двум командам — {@code /broadcast}
     * и {@code /broadcast_to}: без него из апдейта не достать ни разметку (в ней живут
     * premium-эмодзи), ни прикреплённую картинку, а по одному тексту их не восстановить.
     * Остальным командам хватает строки, поэтому {@code source} может быть null.
     */
    public boolean tryHandleAdminCommand(String text, Long chatId, Message source) throws TelegramApiException {
        String[] parts = text.split("\\s+", 2);
        String cmd = parts[0];
        String args = parts.length > 1 ? parts[1] : "";
        switch (cmd) {
            case "/admin":
                handleAdminPanelCommand();
                return true;
            case "/stats":
                handleStatsCommand(chatId);
                return true;
            case "/metrics":
                handleMetricsCommand(chatId);
                return true;
            case "/user":
                handleUserCommand(chatId, args);
                return true;
            case "/devices":
                handleDevicesCommand(chatId, args);
                return true;
            case "/extend":
                handleExtendCommand(chatId, args);
                return true;
            case "/extend_all":
                handleExtendAllCommand(chatId, args);
                return true;
            case "/grant":
                handleGrantCommand(chatId, args);
                return true;
            case "/disable":
                handleDisableCommand(chatId, args);
                return true;
            case "/nodes":
                handleNodesCommand(chatId);
                return true;
            case "/online":
                handleOnlineCommand(chatId);
                return true;
            case "/ledger":
                handleLedgerCommand(chatId, args);
                return true;
            case "/subs":
                handleSubsCommand(chatId, args);
                return true;
            case "/subcheck":
                handleSubCheckCommand(chatId);
                return true;
            case "/switchsub":
                handleSwitchSubCommand(chatId, args);
                return true;
            case "/reloadconfig":
                handleReloadConfigCommand(chatId, args);
                return true;
            case "/broadcast":
                handleBroadcastCommand(chatId, args, source);
                return true;
            case "/broadcast_to":
                handleBroadcastToCommand(chatId, args, source);
                return true;
            case "/promo":
                handlePromoCommand(chatId, args);
                return true;
            case "/testmail":
                handleTestMailCommand(chatId, args);
                return true;
            default:
                return false;
        }
    }

    // ========================
    // /admin — ссылка на веб-панель
    // ========================

    /**
     * Выдаёт владельцу ссылку входа в веб-админку. Ссылка уходит ровно тому, чей id стоит в
     * {@code app.telegram.owner-id}, а не отправителю команды: гейт в {@link SubscriptionBot} и так
     * пускает сюда только владельца, но пусть адресат берётся из конфига, а не из апдейта —
     * тогда ошибка в гейте не превращается в выдачу доступа.
     * <p>
     * Предпросмотр ссылки выключен: Telegram сходил бы по ней раньше человека, и в логах
     * появлялся бы вход, которого никто не делал.
     */
    private void handleAdminPanelCommand() throws TelegramApiException {
        Long ownerId = appConfig.getTelegram() == null ? null : appConfig.getTelegram().getOwnerId();
        if (ownerId == null) {
            return;
        }
        String base = appConfig.getTelegram().getPublicBaseUrl();
        String url = (base == null ? "" : base.replaceAll("/+$", ""))
                + "/account/admin/enter?token=" + adminLoginTokenService.issue(ownerId);
        SendMessage msg = new SendMessage(ownerId.toString(),
                "🗂 <b>Админ-панель</b>\n\n"
                + "<a href=\"" + HtmlUtils.htmlEscape(url) + "\">Открыть панель</a>\n\n"
                + "Ссылка действует 10 минут, вход в браузере — 12 часов.\n"
                + "Панель только показывает данные. Продление, отключение, гранты и рассылки "
                + "остаются командами бота.");
        msg.setParseMode("HTML");
        msg.disableWebPagePreview();
        telegramSender.execute(msg);
    }

    /**
     * Владелец прислал боту видео — отвечаем его {@code file_id}, чтобы он попал в
     * {@code HOW_TO_VIDEO_ID} и экран «Как подключиться» начал показывать ролик.
     * <p>
     * Тот же приём, что у {@code /emojiid}: идентификатор существует только внутри Telegram и
     * получить его иначе как через присланное сообщение нельзя. Заливка идёт один раз —
     * дальше бот отправляет видео по этому id, без загрузки файла и без трафика с нашей стороны.
     * <p>
     * Гейт на владельца стоит в {@link SubscriptionBot}: чужое видео бот игнорирует молча, как и
     * админ-команды.
     */
    public void handleVideoFileId(Update update, Long chatId) throws TelegramApiException {
        Video video = update.getMessage().getVideo();
        Animation animation = update.getMessage().getAnimation();
        Document document = update.getMessage().getDocument();

        // Файлом ролик приходит документом, а идентификатор документа отправляется только
        // документом же — на экране это карточка файла, а не проигрыватель. Просим прислать
        // иначе, id не предлагаем: он тут бесполезен.
        if (video == null && animation == null && document != null) {
            SendMessage msg = new SendMessage(chatId.toString(),
                    "📎 Это пришло файлом, а файл на экране показывается карточкой, "
                    + "не проигрывателем.\n\n"
                    + "Отправьте ролик обычным способом — как видео, без галочки «отправить как файл».");
            telegramSender.execute(msg);
            return;
        }
        if (video == null && animation == null) {
            return;
        }

        // mp4 без звуковой дорожки Telegram считает гифкой и кладёт в message.animation.
        // Идентификатор привязан к типу, поэтому в .env он уезжает с пометкой — иначе экран
        // помощи попытается отправить анимацию как видео и промолчит.
        boolean isAnimation = video == null;
        String fileId = isAnimation ? animation.getFileId() : video.getFileId();
        String envLine = "HOW_TO_VIDEO_ID="
                + (isAnimation ? ScreenHandler.HOW_TO_ANIMATION_PREFIX : "") + fileId;

        Integer width = isAnimation ? animation.getWidth() : video.getWidth();
        Integer height = isAnimation ? animation.getHeight() : video.getHeight();
        Integer duration = isAnimation ? animation.getDuration() : video.getDuration();
        Long fileSize = isAnimation ? animation.getFileSize() : video.getFileSize();

        SendMessage msg = new SendMessage(chatId.toString(),
                (isAnimation ? "🎞 <b>Пришло как гифка</b> (в mp4 нет звуковой дорожки)"
                             : "🎬 <b>Пришло как видео</b>") + "\n\n"
                + width + "×" + height + " · "
                + (duration == null ? "?" : duration + " с") + " · "
                + (fileSize == null ? "?" : (fileSize / 1024) + " КБ") + "\n\n"
                + "Строка для <code>/opt/subscription-service/.env</code> целиком:\n\n"
                + "<code>" + HtmlUtils.htmlEscape(envLine) + "</code>\n\n"
                + "После правки перезапустите приложение.\n\n"
                + (isAnimation
                    ? "Гифка проигрывается без звука и по кругу. Нужен обычный проигрыватель — "
                      + "добавьте в файл тихую дорожку и пришлите заново.\n\n"
                    : "")
                + "⚠️ Идентификатор привязан к этому боту: у другого бота он не работает.");
        msg.setParseMode("HTML");
        telegramSender.execute(msg);
    }

    // ========================
    // /online — кто в сети ПРЯМО СЕЙЧАС (то, чего не показывает AccessProvider)
    // ========================

    /**
     * Owner-only. AccessProvider видит только прямые узлы, а ими с августа 2026 почти никто
     * не пользуется (ТСПУ душит) — панель честно показывает «онлайн 0». Реальные люди сидят
     * на узлах, которые живут вне контура провайдера. Эта команда собирает
     * их из {@link com.orchestra.subscriptions.service.OnlineTracker}.
     */
    private void handleOnlineCommand(Long chatId) throws TelegramApiException {
        SendMessage out = new SendMessage(chatId.toString(), formatOnline(
                onlineTracker.recentNodeSessions(),
                onlineTracker.relaySnapshot(),
                appConfig.getNodeNames(),
                appConfig.getRelayInboundNames(),
                java.time.Instant.now()));
        out.setParseMode("HTML");
        telegramSender.execute(out);
    }

    /**
     * Рендер {@code /online}. Package-private + static, без Spring — чистая функция, покрыта
     * юнит-тестом. {@code now} параметром, чтобы тест не зависел от часов.
     */
    static String formatOnline(List<com.orchestra.subscriptions.service.OnlineTracker.NodeSession> nodeSessions,
                               com.orchestra.subscriptions.service.OnlineTracker.RelaySnapshot relay,
                               Map<String, String> nodeNames,
                               Map<String, String> relayInboundNames,
                               java.time.Instant now) {
        StringBuilder sb = new StringBuilder("👥 <b>Кто в сети сейчас</b>\n\n");

        sb.append("⚡ <b>В сети</b> (за 10 мин): ");
        if (nodeSessions.isEmpty()) {
            sb.append("никого\n");
        } else {
            sb.append(nodeSessions.size()).append(" подкл.\n");
            Map<String, List<String>> byNode = new java.util.LinkedHashMap<>();
            for (var s : nodeSessions) {
                String node = nodeNames.getOrDefault(s.nodeIp(), s.nodeIp() == null ? "?" : s.nodeIp());
                byNode.computeIfAbsent(node, k -> new java.util.ArrayList<>()).add(s.username());
            }
            byNode.forEach((node, users) -> {
                sb.append("  ").append(HtmlUtils.htmlEscape(node, "UTF-8"))
                        .append(" — ").append(users.size()).append(": ");
                sb.append(HtmlUtils.htmlEscape(String.join(", ",
                        users.size() > 8 ? users.subList(0, 8) : users), "UTF-8"));
                if (users.size() > 8) sb.append(" и ещё ").append(users.size() - 8);
                sb.append('\n');
            });
        }

        sb.append("\n🔗 <b>Каскад «Mobile»</b>: ");
        if (relay == null) {
            sb.append("нет данных\n<i>вход ещё не присылал отчёт — проверьте крон на релее</i>\n");
        } else {
            long ageSec = java.time.Duration.between(relay.at(), now).getSeconds();
            sb.append(relay.uniqueClients()).append(" клиент(ов)")
                    .append(ageSec > 180 ? " ⚠️ данные устарели" : "")
                    .append(" · ").append(ageSec).append(" с назад\n");
            relay.connectionsByPort().forEach((port, count) ->
                    sb.append("  ").append(HtmlUtils.htmlEscape(
                                    relayInboundNames.getOrDefault(port, "порт " + port), "UTF-8"))
                            .append(" — ").append(count).append(" соед.\n"));
        }

        sb.append("\n<i>Прямые узлы считает AccessProvider — там сейчас пусто, потому что ими "
                + "почти никто не пользуется.</i>");
        return sb.toString();
    }

    // ========================
    // /nodes — состояние флота AccessProvider + внешняя проба :443
    // ========================

    /**
     * Owner-only. Снимок всех нод панели AccessProvider с прямой TCP-пробой :443 к каждой — чтобы
     * проверить флот с телефона, не заходя по SSH и не гоняя {@code diag-node.sh}. Особая
     * ценность — строка-предупреждение, когда панель считает ноду {@code connected}, а :443
     * снаружи закрыт: это признак per-IP DPI (см. {@code docs/node-runbook.md} Часть 2).
     */
    private void handleNodesCommand(Long chatId) throws TelegramApiException {
        List<AdminService.NodeStatus> nodes;
        try {
            nodes = adminService.getNodeStatuses();
        } catch (Exception e) {
            log.error("Admin /nodes failed", e);
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "❌ Не смог опросить ноды: " + e.getMessage()));
            return;
        }
        SendMessage out = new SendMessage(chatId.toString(), formatNodes(nodes));
        out.setParseMode("HTML");
        telegramSender.execute(out);
    }

    /**
     * Рендерит снапшот {@code /nodes} в HTML для Telegram. Package-private + static — чистая
     * функция без сайд-эффектов, покрыта юнит-тестом (без Spring). Весь динамический контент
     * (имена нод, адреса, сообщения панели) экранируется через {@link HtmlUtils#htmlEscape}.
     */
    static String formatNodes(List<AdminService.NodeStatus> nodes) {
        if (nodes.isEmpty()) {
            return "🖥 <b>Ноды AccessProvider</b>\n\n"
                    + "⚠️ Панель не вернула ноды — недоступна или список пуст.\n"
                    + "Проверьте панель на main: <code>docker restart provider-provider-1</code>.";
        }
        StringBuilder sb = new StringBuilder("🖥 <b>Ноды AccessProvider</b>\n\n");
        int dpiSuspects = 0;
        for (AdminService.NodeStatus n : nodes) {
            String status = n.status() == null ? "" : n.status();
            String statusEmoji = switch (status.toLowerCase(Locale.ROOT)) {
                case "connected" -> "✅";
                case "connecting" -> "🟡";
                case "error" -> "❌";
                case "disabled" -> "⚫";
                default -> "❔";
            };
            String portMark = n.port443Open() ? "🟢 :443" : "🔴 :443";
            sb.append(statusEmoji).append(" <b>").append(HtmlUtils.htmlEscape(nz(n.name()))).append("</b>\n")
              .append("   ").append(HtmlUtils.htmlEscape(status.isBlank() ? "?" : status))
              .append(" · <code>").append(HtmlUtils.htmlEscape(nz(n.address()))).append("</code>")
              .append(" · ").append(portMark);
            // Версия ядра — рядом со статусом намеренно: «зелёная» нода со старым ядром
            // ломает подключение у свежих клиентов, и без этой цифры причина не видна.
            if (n.coreVersion() != null && !n.coreVersion().isBlank()) {
                sb.append(" · ядро ").append(HtmlUtils.htmlEscape(n.coreVersion()));
            }
            sb.append('\n');
            // Панель видит ноду connected, а :443 снаружи закрыт → классический per-IP DPI на маршруте.
            if ("connected".equalsIgnoreCase(status) && !n.port443Open()) {
                dpiSuspects++;
                sb.append("   ⚠️ панель connected, но :443 закрыт снаружи — возможен per-IP DPI\n");
            }
            if (n.message() != null && !n.message().isBlank()) {
                sb.append("   └ ").append(HtmlUtils.htmlEscape(n.message())).append('\n');
            }
            sb.append('\n');
        }
        if (dpiSuspects > 0) {
            sb.append("↳ подозрение на DPI: на main <code>scripts/diag-node.sh &lt;IP&gt;</code>; "
                    + "если ICMP чист, а SYN дропается — тикет хостингу на новый IP.");
        }
        return sb.toString().stripTrailing();
    }

    private static String nz(String s) {
        return s == null ? "—" : s;
    }

    // ========================
    // /reloadconfig — горячий конфиг без деплоя
    // ========================

    /**
     * Owner-only. Перечитывает внешний «горячий» конфиг ({@code app.dynamic-config-path} + routing
     * json) и применяет его к живому приложению без пересборки образа. Двухшаговый протокол как у
     * {@code /extend_all}: {@code /reloadconfig} — превью diff (ничего не меняет),
     * {@code /reloadconfig apply} — применяет. См. {@link DynamicConfigService}.
     */
    private void handleReloadConfigCommand(Long chatId, String args) throws TelegramApiException {
        boolean apply = args.trim().equalsIgnoreCase("apply");
        DynamicConfigService.ReloadResult result = apply
                ? dynamicConfigService.apply()
                : dynamicConfigService.preview();
        telegramSender.execute(new SendMessage(chatId.toString(), formatReload(result)));
    }

    /** Рендер результата {@code /reloadconfig} в plain-текст (без HTML — экранировать нечего). */
    static String formatReload(DynamicConfigService.ReloadResult r) {
        if (!r.success()) {
            return "❌ " + r.message()
                    + "\n\nПравь файл на сервере (обычно /opt/subscription-service/config/app-dynamic.yml), "
                    + "затем /reloadconfig.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(r.applied() ? "✅ Конфиг применён" : "👀 Превью (не применено)")
          .append("\nФайл: ").append(r.path()).append("\n\n");
        for (String line : r.lines()) {
            sb.append("• ").append(line).append('\n');
        }
        if (r.applied()) {
            sb.append("\nГотово — изменения уже живут, деплой не нужен.");
        } else {
            sb.append("\nПрименить: /reloadconfig apply");
        }
        return sb.toString();
    }

    /**
     * Тестовая отправка одному юзеру — для проверки рендеринга перед массовым /broadcast.
     * Идёт через {@link BroadcastService#send} (rate limiting для одного не нужен, но рендеринг
     * обязан быть 1-в-1 с настоящей рассылкой — иначе смысл проверки теряется): тот же выбор
     * между фото и текстом, та же разметка, тот же parse mode.
     *
     * <p>Формат: {@code /broadcast_to <telegramId> <html-текст>}. Картинка цепляется так же,
     * как у {@code /broadcast} — командой в подписи к фото или ответом на сообщение с фото.
     */
    private void handleBroadcastToCommand(Long chatId, String args, Message source) throws TelegramApiException {
        String trimmed = args.trim();
        if (trimmed.isEmpty()) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "Использование: /broadcast_to <telegramId> <текст>\n\n"
                    + "Шлёт сообщение одному юзеру (для теста рендеринга перед /broadcast).\n"
                    + "Поддерживается HTML: &lt;b&gt;, &lt;i&gt;, &lt;code&gt;, &lt;a href=...&gt;.\n"
                    + "Фото — командой в подписи к картинке."));
            return;
        }
        // Разделитель — любой пробельный, как и у диспетчера команд: перенос строки между id
        // и текстом Telegram отдаёт как есть, и раньше он превращался в «некорректный id».
        String[] idAndText = trimmed.split("\\s+", 2);
        String idRaw = idAndText[0];
        Long targetId;
        try {
            targetId = Long.parseLong(idRaw);
        } catch (NumberFormatException e) {
            telegramSender.execute(new SendMessage(chatId.toString(), "⚠️ Некорректный telegramId: " + idRaw));
            return;
        }

        BroadcastService.BroadcastContent content = broadcastContent(
                source, idAndText.length > 1 ? idAndText[1] : "", 2);
        if (content.isEmpty()) {
            telegramSender.execute(new SendMessage(chatId.toString(), "⚠️ Нечего отправлять: ни текста, ни фото."));
            return;
        }
        if (content.tooLong()) {
            telegramSender.execute(new SendMessage(chatId.toString(), tooLongMessage(content)));
            return;
        }

        try {
            broadcastService.send(targetId.toString(), content);
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "✅ Отправлено id=" + targetId
                    + " (" + (content.hasPhoto() ? "фото, подпись " : "")
                    + content.visibleLength() + " символов)."));
        } catch (TelegramApiException e) {
            log.warn("broadcast_to failed for telegramId={}", targetId, e);
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "❌ Не доставлено id=" + targetId + ": " + e.getMessage()
                    + "\n\nВозможные причины: юзер не запускал бота, заблокировал бот, "
                    + "невалидный HTML."));
        }
    }

    // ========================
    // /testmail
    // ========================

    /**
     * Тестовая отправка email через {@link EmailService}. Используется при настройке/смене
     * SMTP-провайдера, чтобы убедиться, что:
     * <ul>
     *   <li>SMTP-credentials валидны и Spring Boot их подхватил из ENV;</li>
     *   <li>письмо доходит во Входящие, а не в Спам (значит SPF/DKIM/DMARC настроены правильно);</li>
     *   <li>русский subject/body не превращается в крякозябры (UTF-8 encoding ок).</li>
     * </ul>
     * <p>
     * Формат: {@code /testmail <email>}. Если {@code app.email.enabled=false} (дефолт),
     * увидите DRY-RUN в логах вместо реальной отправки — это нормально.
     */
    private void handleTestMailCommand(Long chatId, String args) throws TelegramApiException {
        String to = args.trim();
        if (to.isEmpty() || !to.contains("@")) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "Использование: /testmail <email>\n\n"
                    + "Шлёт тестовое письмо для проверки SMTP-конфига.\n"
                    + "Если EMAIL_ENABLED=false — увидите DRY-RUN в логах, реальной отправки не будет."));
            return;
        }
        String subject = "Orchestra — тест SMTP";
        String body = "Это тестовое письмо от Orchestra.\n\n"
                + "Если оно у вас во Входящих — SMTP, DKIM, SPF и DMARC настроены правильно.\n"
                + "Если в Спаме — посмотрите в исходниках письма (Show original) статусы\n"
                + "spf=, dkim=, dmarc=. Pass = ок, fail = разбираемся.\n\n"
                + "Время отправки: " + LocalDateTime.now() + "\n";
        emailService.send(to, subject, body);
        telegramSender.execute(new SendMessage(chatId.toString(),
                "✉️ Запрос на отправку поставлен в очередь: " + to + "\n\n"
                + "Проверьте Входящие и Спам.\n"
                + "Если EMAIL_ENABLED=false — реальной отправки не было, посмотрите логи бота."));
    }

    // ========================
    // /emojiid — узнать custom_emoji_id для app.emoji.ids
    // ========================

    /**
     * Owner-only. Возвращает {@code custom_emoji_id} всех premium-эмодзи в команде
     * (или в сообщении, на которое ответили этой командой), чтобы владелец сам заполнил
     * {@code app.emoji.ids}. Premium-эмодзи приходят в {@code message.entities} как сущности
     * типа {@code custom_emoji} только если у аккаунта-владельца есть Telegram Premium.
     */
    public void handleEmojiIdCommand(Update update, Long chatId) throws TelegramApiException {
        Message msg = update.getMessage();
        Message source = msg;
        List<MessageEntity> entities = msg.getEntities();
        boolean hasCustom = entities != null && entities.stream()
                .anyMatch(en -> "custom_emoji".equals(en.getType()));
        // Фолбэк: команда отправлена ответом на сообщение с premium-эмодзи.
        if (!hasCustom && msg.getReplyToMessage() != null) {
            source = msg.getReplyToMessage();
            entities = source.getEntities();
        }

        StringBuilder sb = new StringBuilder("🆔 <b>custom_emoji_id</b>\n\n");
        int found = 0;
        if (entities != null && source.getText() != null) {
            String t = source.getText();
            for (MessageEntity en : entities) {
                if (!"custom_emoji".equals(en.getType()) || en.getCustomEmojiId() == null) {
                    continue;
                }
                int from = en.getOffset();
                int to = Math.min(t.length(), en.getOffset() + en.getLength());
                String glyph = (from >= 0 && from < to) ? t.substring(from, to) : "?";
                sb.append(glyph).append(" → <code>").append(en.getCustomEmojiId()).append("</code>\n");
                found++;
            }
        }

        if (found == 0) {
            sb.append("Не нашёл premium-эмодзи.\n\n")
                    .append("Пришлите <code>/emojiid</code> и в этом же сообщении вставьте нужные "
                            + "анимированные эмодзи (или ответьте командой на сообщение с ними).\n\n")
                    .append("Важно: premium-эмодзи доступны только при Telegram Premium у этого аккаунта.");
        } else {
            sb.append("\nСкопируйте id в <code>app.emoji.ids</code> по ключам из util/Emoji.java ")
                    .append("(dolphin, rocket, chart…) и выставите <code>EMOJI_ENABLED=true</code>.");
        }

        SendMessage out = new SendMessage(chatId.toString(), sb.toString());
        out.setParseMode("HTML");
        telegramSender.execute(out);
    }

    // ========================
    // /promo
    // ========================

    /**
     * Управление промокодами. Поддерживает 4 sub-команды:
     * <ul>
     *   <li>{@code /promo create <CODE> <PERCENT|FIXED> <value> [maxUses] [daysValid]} —
     *       создать. Для PERCENT value=1..99, для FIXED value в РУБЛЯХ (для удобства,
     *       внутри хранятся копейки).</li>
     *   <li>{@code /promo list} — все коды (новые сверху).</li>
     *   <li>{@code /promo info <CODE>} — детали + последние 10 применений.</li>
     *   <li>{@code /promo disable <CODE>} — soft-disable (active=false).</li>
     * </ul>
     */
    private void handlePromoCommand(Long chatId, String args) throws TelegramApiException {
        String trimmed = args.trim();
        if (trimmed.isEmpty()) {
            telegramSender.execute(new SendMessage(chatId.toString(), promoUsage()));
            return;
        }
        String[] tokens = trimmed.split("\\s+");
        String sub = tokens[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "create" -> handlePromoCreate(chatId, tokens);
                case "list" -> handlePromoList(chatId);
                case "info" -> handlePromoInfo(chatId, tokens);
                case "disable" -> handlePromoDisable(chatId, tokens);
                default -> telegramSender.execute(new SendMessage(chatId.toString(), promoUsage()));
            }
        } catch (IllegalArgumentException e) {
            telegramSender.execute(new SendMessage(chatId.toString(), "⚠️ " + e.getMessage()));
        } catch (Exception e) {
            log.error("Admin /promo {} failed", sub, e);
            telegramSender.execute(new SendMessage(chatId.toString(), "❌ Ошибка: " + e.getMessage()));
        }
    }

    private static String promoUsage() {
        return "Использование:\n"
                + "/promo create <CODE> <PERCENT|FIXED> <value> [maxUses] [daysValid]\n"
                + "    PERCENT — value в процентах (1..99)\n"
                + "    FIXED   — value в рублях (фиксированная скидка)\n"
                + "    maxUses — лимит применений (по умолчанию безлимит)\n"
                + "    daysValid — срок жизни в днях (по умолчанию бессрочный)\n"
                + "/promo list — все коды\n"
                + "/promo info <CODE> — детали + последние применения\n"
                + "/promo disable <CODE> — отключить\n\n"
                + "Примеры:\n"
                + "  /promo create WELCOME20 PERCENT 20\n"
                + "  /promo create SUMMER100 FIXED 100 50 30\n"
                + "  /promo create VIP10 PERCENT 10 1";
    }

    private void handlePromoCreate(Long chatId, String[] tokens) throws TelegramApiException {
        // tokens: [create, CODE, TYPE, value, [maxUses], [daysValid]]
        if (tokens.length < 4) {
            telegramSender.execute(new SendMessage(chatId.toString(), promoUsage()));
            return;
        }
        String code = tokens[1];
        com.orchestra.subscriptions.model.PromoCode.DiscountType type;
        try {
            type = com.orchestra.subscriptions.model.PromoCode.DiscountType.valueOf(tokens[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Тип должен быть PERCENT или FIXED, получено: " + tokens[2]);
        }
        long value;
        try {
            value = Long.parseLong(tokens[3]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Значение должно быть числом, получено: " + tokens[3]);
        }
        // Для FIXED-типа: юзер вводит рубли, конвертируем в копейки.
        long internalValue = type == com.orchestra.subscriptions.model.PromoCode.DiscountType.FIXED
                ? value * 100
                : value;
        Integer maxUses = parsePromoInt(tokens, 4, "Лимит применений");
        Integer daysValid = parsePromoInt(tokens, 5, "Срок действия в днях");

        com.orchestra.subscriptions.model.PromoCode promo = promoCodeService.createPromo(
                code, type, internalValue, maxUses, daysValid, chatId);

        StringBuilder sb = new StringBuilder();
        sb.append("✅ Промокод создан\n\n")
                .append("Код: <code>").append(promo.getCode()).append("</code>\n")
                .append("Скидка: ").append(formatPromoDiscount(promo)).append("\n")
                .append("Лимит: ").append(promo.getMaxUses() == null ? "∞" : promo.getMaxUses()).append("\n")
                .append("Срок: ").append(promo.getExpiresAt() == null
                        ? "бессрочно" : "до " + TimeFormat.mskDateTime(promo.getExpiresAt()));
        SendMessage msg = new SendMessage(chatId.toString(), sb.toString());
        msg.setParseMode("HTML");
        telegramSender.execute(msg);
    }

    /**
     * Необязательный числовой аргумент {@code /promo create}. Отсутствует — {@code null}.
     * Разбор с тем же понятным текстом ошибки, что и у обязательных аргументов выше: без него
     * опечатка в лимите прилетала владельцу как «⚠️ For input string: ...».
     */
    private static Integer parsePromoInt(String[] tokens, int index, String what) {
        if (tokens.length <= index) {
            return null;
        }
        try {
            return Integer.parseInt(tokens[index]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " должен быть числом, получено: " + tokens[index]);
        }
    }

    private void handlePromoList(Long chatId) throws TelegramApiException {
        List<com.orchestra.subscriptions.model.PromoCode> all = promoCodeService.listAll();
        if (all.isEmpty()) {
            telegramSender.execute(new SendMessage(chatId.toString(), "Промокодов нет. Создайте через /promo create."));
            return;
        }
        StringBuilder sb = new StringBuilder("🎟 <b>Промокоды (").append(all.size()).append(")</b>\n\n");
        for (com.orchestra.subscriptions.model.PromoCode p : all) {
            sb.append(p.isActive() ? "✅ " : "🚫 ")
                    .append("<code>").append(p.getCode()).append("</code> · ")
                    .append(formatPromoDiscount(p)).append(" · ")
                    .append("использован ").append(p.getUsedCount());
            if (p.getMaxUses() != null) sb.append("/").append(p.getMaxUses());
            if (p.getExpiresAt() != null) {
                if (p.getExpiresAt().isBefore(LocalDateTime.now())) {
                    sb.append(" · ⏰ истёк");
                } else {
                    sb.append(" · до ").append(TimeFormat.mskDate(p.getExpiresAt()));
                }
            }
            sb.append("\n");
        }
        SendMessage msg = new SendMessage(chatId.toString(), sb.toString());
        msg.setParseMode("HTML");
        telegramSender.execute(msg);
    }

    private void handlePromoInfo(Long chatId, String[] tokens) throws TelegramApiException {
        if (tokens.length < 2) {
            telegramSender.execute(new SendMessage(chatId.toString(), "Использование: /promo info <CODE>"));
            return;
        }
        Optional<com.orchestra.subscriptions.model.PromoCode> opt = promoCodeService.findByCode(tokens[1]);
        if (opt.isEmpty()) {
            telegramSender.execute(new SendMessage(chatId.toString(), "⚠️ Промокод не найден: " + tokens[1]));
            return;
        }
        com.orchestra.subscriptions.model.PromoCode p = opt.get();
        List<com.orchestra.subscriptions.model.PromoUsage> usages = promoCodeService.recentUsages(p.getId());

        StringBuilder sb = new StringBuilder();
        sb.append("🎟 <b>").append(p.getCode()).append("</b> ")
                .append(p.isActive() ? "✅" : "🚫").append("\n\n")
                .append("Скидка: ").append(formatPromoDiscount(p)).append("\n")
                .append("Использован: ").append(p.getUsedCount());
        if (p.getMaxUses() != null) sb.append(" / ").append(p.getMaxUses());
        sb.append("\n");
        sb.append("Срок: ").append(p.getExpiresAt() == null
                ? "бессрочно" : TimeFormat.mskDateTime(p.getExpiresAt())).append("\n");
        sb.append("Создан: ").append(TimeFormat.mskDateTime(p.getCreatedAt())).append("\n");

        if (!usages.isEmpty()) {
            sb.append("\n<b>Последние применения:</b>\n");
            for (com.orchestra.subscriptions.model.PromoUsage u : usages) {
                sb.append("• ").append(TimeFormat.mskDateTime(u.getUsedAt()))
                        .append(" · id=").append(u.getTelegramId())
                        .append(" · -").append(Money.rub(u.getDiscountKopeks())).append(" ₽");
                if (u.getRevertedAt() != null) sb.append(" · ↩ возврат");
                sb.append("\n");
            }
        }

        SendMessage msg = new SendMessage(chatId.toString(), sb.toString());
        msg.setParseMode("HTML");
        telegramSender.execute(msg);
    }

    private void handlePromoDisable(Long chatId, String[] tokens) throws TelegramApiException {
        if (tokens.length < 2) {
            telegramSender.execute(new SendMessage(chatId.toString(), "Использование: /promo disable <CODE>"));
            return;
        }
        boolean ok = promoCodeService.disable(tokens[1]);
        telegramSender.execute(new SendMessage(chatId.toString(), ok
                ? "🚫 Промокод отключён: " + tokens[1].toUpperCase(Locale.ROOT)
                : "⚠️ Не найден или уже отключён: " + tokens[1]));
    }

    private static String formatPromoDiscount(com.orchestra.subscriptions.model.PromoCode p) {
        return switch (p.getDiscountType()) {
            case PERCENT -> "−" + p.getDiscountValue() + "%";
            case FIXED   -> "−" + (p.getDiscountValue() / 100) + " ₽";
        };
    }

    private void handleStatsCommand(Long chatId) throws TelegramApiException {
        AdminService.StatsSnapshot stats = adminService.buildStats();
        StringBuilder sb = new StringBuilder();
        sb.append("📊 <b>Статистика бота</b>\n\n")
                .append("👥 <b>Пользователи</b>\n")
                .append("• Всего: ").append(stats.totalUsers()).append("\n")
                .append("• Активных: ").append(stats.activeUsers()).append("\n")
                .append("• Отключённых: ").append(stats.disabledUsers()).append("\n")
                .append("• На триале: ").append(stats.trialOnlyUsers()).append("\n")
                .append("• Оплативших (уникальных): ").append(stats.paidUniqueUsers()).append("\n\n")
                .append("📈 <b>Рост</b>\n")
                .append("• Новых за 7д: ").append(stats.newUsersLast7d()).append("\n")
                .append("• Новых за 30д: ").append(stats.newUsersLast30d()).append("\n\n")
                .append("🎯 <b>Воронка</b>\n")
                .append("• Триалов всего: ").append(stats.trialsTakenAllTime()).append("\n")
                .append("• Из них оплатили: ").append(stats.trialsConvertedAllTime())
                .append(" (").append(String.format(Locale.ROOT, "%.1f", stats.trialConversionPct())).append("%)\n\n")
                .append("💰 <b>Выручка за 30 дней</b>\n")
                .append("• Всего: ").append(formatRub(stats.revenue30dKopeks())).append(" ₽ ")
                .append("(").append(stats.successfulPayments30d()).append(" платежей)\n")
                .append("• Средний чек: ").append(formatRub(stats.averagePaymentKopeks30d())).append(" ₽\n");

        for (Map.Entry<Payment.Provider, Long> e : stats.revenue30dByProviderKopeks().entrySet()) {
            sb.append("  • ").append(e.getKey().name())
                    .append(": ").append(formatRub(e.getValue())).append(" ₽\n");
        }

        sb.append("\n🔄 <b>Удержание</b>\n")
                .append("• Платных подписок истекло за 30д: ").append(stats.paidUsersExpiredLast30d()).append("\n");

        SendMessage msg = new SendMessage(chatId.toString(), sb.toString());
        msg.setParseMode("HTML");
        telegramSender.execute(msg);
    }

    /** Форматирует копейки → рубли без дробной части (1 руб точности хватает для /stats). */
    private static String formatRub(long kopeks) {
        return String.valueOf(kopeks / 100);
    }

    /**
     * Бизнес-метрики subscription-сервиса. В отличие от /stats (плоский снапшот цифр)
     * показывает производные показатели: MRR/ARPU/LTV/churn/retention. На 20-30 юзерах
     * многие будут шумные (1 отписка = 5% churn) — это нормально, метрики масштабируются.
     */
    private void handleMetricsCommand(Long chatId) throws TelegramApiException {
        AdminService.MetricsSnapshot m = adminService.buildMetrics();
        StringBuilder sb = new StringBuilder();

        sb.append("📊 <b>Бизнес-метрики</b>\n\n")
                .append("💵 <b>Recurring revenue</b>\n")
                .append("• MRR: ").append(formatRub(m.mrrKopeks())).append(" ₽/мес\n")
                .append("• Активных платных: ").append(m.activePayingUsers()).append("\n")
                .append("• ARPU: ").append(formatRub(m.arpuKopeks())).append(" ₽/мес\n\n");

        sb.append("🔄 <b>Удержание (30 дн)</b>\n")
                .append("• Истекло без продления: ").append(m.paidUsersExpiredLast30d()).append("\n")
                .append("• Churn rate: ")
                .append(String.format(Locale.ROOT, "%.1f", m.churnRate30dPct())).append("%\n")
                .append("• LTV: ");
        if (m.ltvDefined()) {
            sb.append(formatRub(m.ltvKopeks())).append(" ₽");
        } else {
            sb.append("— <i>(нужен ≥1 churn для расчёта)</i>");
        }
        sb.append("\n\n");

        sb.append("🎯 <b>Воронка</b>\n")
                .append("• Триал → платный: ")
                .append(String.format(Locale.ROOT, "%.1f", m.trialConversionPct())).append("% (")
                .append(m.trialsConvertedAllTime()).append(" из ")
                .append(m.trialsTakenAllTime()).append(")\n\n");

        sb.append("📦 <b>Активные подписки по тарифам</b>\n");
        long totalActive = m.activePayingByPlan().values().stream().mapToLong(Long::longValue).sum();
        for (Map.Entry<String, Long> e : m.activePayingByPlan().entrySet()) {
            if (e.getValue() == 0) continue;
            long pct = totalActive == 0 ? 0 : e.getValue() * 100 / totalActive;
            String name = appConfig.getPlans().stream()
                    .filter(p -> p.getId().equals(e.getKey()))
                    .map(AppConfig.Plan::getName)
                    .findFirst().orElse(e.getKey());
            sb.append("• ").append(name).append(": ").append(e.getValue())
                    .append(" (").append(pct).append("%)\n");
        }
        if (totalActive == 0) {
            sb.append("• <i>нет активных подписок</i>\n");
        }
        sb.append("\n");

        sb.append("📅 <b>Когорты (по месяцу первой оплаты)</b>\n");
        for (AdminService.CohortRow row : m.cohorts()) {
            sb.append("• ").append(row.monthLabel()).append(": ");
            if (row.cohortSize() == 0) {
                sb.append("<i>0</i>\n");
            } else {
                sb.append(row.stillPayingNow()).append("/").append(row.cohortSize())
                        .append(" активны (")
                        .append(String.format(Locale.ROOT, "%.0f", row.retentionPct())).append("%)\n");
            }
        }

        SendMessage msg = new SendMessage(chatId.toString(), sb.toString());
        msg.setParseMode("HTML");
        telegramSender.execute(msg);
    }

    /**
     * Owner-only. Устройства юзера (HWID-учёт с подписочного эндпоинта) — для разбора тикетов
     * «не подключается» и проверки алертов об аномалиях: видно, сколько железа сидит на
     * подписке, что заблокировано лимитом, когда что появлялось.
     */
    private void handleDevicesCommand(Long chatId, String args) throws TelegramApiException {
        if (args.isBlank()) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "Использование: /devices <telegramId | @username>"));
            return;
        }
        Optional<Customer> userOpt = adminService.findUserBySubject(args);
        if (userOpt.isEmpty()) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "⚠️ Пользователь не найден: " + args.trim()));
            return;
        }
        Customer user = userOpt.get();
        List<com.orchestra.subscriptions.model.UserDevice> devices =
                deviceTrackingService.listDevices(user.getTelegramId()).stream()
                        .sorted(java.util.Comparator
                                .comparing(com.orchestra.subscriptions.model.UserDevice::getLastSeenAt).reversed())
                        .toList();

        // htmlEscape с UTF-8: только базовые сущности (&<>"') — именованные вроде &hellip;
        // (появляется из «…» в маске HWID) Telegram-парсер не принимает и режет сообщение.
        StringBuilder sb = new StringBuilder();
        String tag = user.getTelegramUsername() != null && !user.getTelegramUsername().isBlank()
                ? "@" + HtmlUtils.htmlEscape(user.getTelegramUsername(), "UTF-8")
                : "id=" + user.getTelegramId();
        sb.append("📱 <b>Устройства ").append(tag).append("</b>\n");
        sb.append("Лимит: ").append(user.getDeviceLimit() != null && user.getDeviceLimit() > 0
                        ? deviceTrackingService.usedSlots(user.getTelegramId()) + " из " + user.getDeviceLimit()
                        : "не задан (legacy)")
                .append(user.getDeviceTierId() != null ? " · ступень " + user.getDeviceTierId() : "")
                .append("\n");

        if (devices.isEmpty()) {
            sb.append("\nУстройств не замечено (HWID шлют только клиентское приложение-совместимые клиенты).");
        }
        for (com.orchestra.subscriptions.model.UserDevice d : devices) {
            String status = switch (d.getStatus()) {
                case ACTIVE -> "✅";
                case BLOCKED -> "⛔️";
                case REVOKED -> "🚫";
            };
            String named = d.getCustomName() != null && !d.getCustomName().isBlank()
                    ? "«" + HtmlUtils.htmlEscape(d.getCustomName(), "UTF-8") + "» — " : "";
            sb.append("\n").append(status).append(" ").append(named)
                    .append(HtmlUtils.htmlEscape(com.orchestra.subscriptions.util.DeviceNames.label(
                            d.getDeviceModel(), d.getDeviceOs(), d.getOsVersion()), "UTF-8"))
                    .append("\n    hwid <code>").append(HtmlUtils.htmlEscape(maskHwid(d.getHwid()), "UTF-8"))
                    .append("</code> · ").append(TimeFormat.mskDate(d.getFirstSeenAt()))
                    .append(" → ").append(TimeFormat.mskDate(d.getLastSeenAt()));
        }

        SendMessage msg = new SendMessage(chatId.toString(), sb.toString());
        msg.setParseMode("HTML");
        msg.disableWebPagePreview();
        telegramSender.execute(msg);
    }

    /** HWID — квази-секрет идентичности устройства: в чат показываем только края. */
    private static String maskHwid(String hwid) {
        if (hwid == null || hwid.length() < 12) return hwid;
        return hwid.substring(0, 8) + "…" + hwid.substring(hwid.length() - 4);
    }

    private void handleUserCommand(Long chatId, String args) throws TelegramApiException {
        Long targetId = parseTelegramId(args);
        if (targetId == null) {
            telegramSender.execute(new SendMessage(chatId.toString(), "Использование: /user <telegramId>"));
            return;
        }
        Optional<AdminService.UserCard> card = adminService.findUser(targetId);
        if (card.isEmpty()) {
            telegramSender.execute(new SendMessage(chatId.toString(), "⚠️ Пользователь не найден: " + targetId));
            return;
        }
        SendMessage msg = new SendMessage(chatId.toString(), formatUserCard(card.get()));
        msg.setParseMode("HTML");
        msg.disableWebPagePreview();
        telegramSender.execute(msg);
    }

    private void handleExtendCommand(Long chatId, String args) throws TelegramApiException {
        String[] tokens = args.isBlank() ? new String[0] : args.split("\\s+");
        if (tokens.length < 2) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "Использование: /extend <telegramId | @username | username> <days>"));
            return;
        }
        // Идентификатор оставляем как строку — AdminService разберётся (число / @username / username)
        String subject = tokens[0];
        Integer days = parseDays(tokens[1]);
        if (days == null) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "Использование: /extend <telegramId | @username | username> <days>\n"
                    + "Дни: целое число от 1 до 3650."));
            return;
        }
        try {
            Customer user = adminService.extendSubscriptionBySubject(subject, days);
            String expiry = TimeFormat.mskDate(user.getExpiresAt());
            SendMessage msg = new SendMessage(chatId.toString(),
                    "✅ Продлено: +" + days + " дн.\nНовый срок: " + expiry);
            msg.setParseMode("HTML");
            telegramSender.execute(msg);
        } catch (IllegalArgumentException e) {
            telegramSender.execute(new SendMessage(chatId.toString(), "⚠️ " + e.getMessage()));
        } catch (Exception e) {
            log.error("Admin /extend failed for subject={} days={}", subject, days, e);
            telegramSender.execute(new SendMessage(chatId.toString(), "❌ Ошибка продления: " + e.getMessage()));
        }
    }

    /**
     * Массовое продление всем активным платящим юзерам — для выдачи компенсации после
     * инцидента. Двухшаговый протокол: без CONFIRM показывает превью (сколько юзеров
     * будет затронуто), с CONFIRM — выполняет. Защищает от случайного запуска: массовая
     * операция через AccessProvider API необратима без обратного продления на тот же срок.
     */
    private void handleExtendAllCommand(Long chatId, String args) throws TelegramApiException {
        String[] tokens = args.isBlank() ? new String[0] : args.split("\\s+");
        if (tokens.length < 1) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "Использование: /extend_all <days> [CONFIRM]\n\n"
                    + "Продлевает всех активных платящих юзеров (status=ACTIVE + есть SUCCEEDED-платёж).\n"
                    + "Без CONFIRM — показывает превью, сколько юзеров будет затронуто.\n"
                    + "Используется для выдачи компенсации после инцидента."));
            return;
        }
        Integer days = parseDays(tokens[0]);
        if (days == null) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "Использование: /extend_all <days> [CONFIRM]\n"
                    + "Дни: целое число от 1 до 3650."));
            return;
        }
        boolean confirmed = tokens.length >= 2 && "CONFIRM".equals(tokens[1]);
        if (!confirmed) {
            int count = adminService.countActivePayingUsers();
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "⚠️ Будет продлено " + count + " активным платящим юзерам по +" + days + " дн.\n\n"
                    + "Для подтверждения отправьте:\n/extend_all " + days + " CONFIRM"));
            return;
        }
        telegramSender.execute(new SendMessage(chatId.toString(),
                "⏳ Запущено массовое продление… (это может занять несколько минут)"));
        try {
            AdminService.BulkExtendResult result = adminService.extendAllActivePayingUsers(days);
            StringBuilder msg = new StringBuilder("✅ Массовое продление завершено\n")
                    .append("Всего: ").append(result.total()).append("\n")
                    .append("Успешно (+").append(days).append(" дн.): ").append(result.succeeded());
            if (result.failed() > 0) {
                msg.append("\n❌ Ошибок: ").append(result.failed()).append(" (см. логи)");
            }
            telegramSender.execute(new SendMessage(chatId.toString(), msg.toString()));
        } catch (IllegalArgumentException e) {
            telegramSender.execute(new SendMessage(chatId.toString(), "⚠️ " + e.getMessage()));
        } catch (Exception e) {
            log.error("Admin /extend_all failed: days={}", days, e);
            telegramSender.execute(new SendMessage(chatId.toString(), "❌ Ошибка: " + e.getMessage()));
        }
    }

    /**
     * Подарочные дни победителю конкурса. Отличается от {@code /extend} тем, что
     * работает с юзерами, которых ещё нет в БД: создаёт отложенный {@code PendingGrant},
     * активация произойдёт автоматически при первом /start или открытии Mini App.
     * <p>
     * Также шлёт DM с поздравлением — для immediate-кейса прямо отсюда, для pending-кейса
     * отправит {@code PendingGrantService.applyIfAny} в момент активации.
     */
    private void handleGrantCommand(Long chatId, String args) throws TelegramApiException {
        String[] tokens = args.isBlank() ? new String[0] : args.split("\\s+");
        if (tokens.length < 2) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "Использование: /grant <@username | username | telegramId> <days>\n\n"
                    + "Отличие от /extend: если юзера ещё нет в БД, приз резервируется "
                    + "и применяется при первом открытии бота. Юзер получит DM с поздравлением."));
            return;
        }
        String subject = tokens[0];
        Integer days = parseDays(tokens[1]);
        if (days == null) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "Дни: целое число от 1 до 3650."));
            return;
        }
        try {
            AdminService.GrantResult result = adminService.grantSubscription(subject, days, chatId);
            if (result.applied()) {
                String expiry = TimeFormat.mskDate(result.expiresAt());
                telegramSender.execute(new SendMessage(chatId.toString(),
                        "✅ Применено: +" + days + " дн.\nНовый срок: " + expiry
                        + "\nDM с поздравлением отправлен юзеру."));
                // Сам DM шлём отсюда, чтобы AdminService не зависел от NotificationService
                // (цикл с SubscriptionBot). PendingGrantService шлёт свой DM при apply.
                notificationService.sendGrantApplied(result.telegramId(), days, result.expiresAt());
            } else {
                telegramSender.execute(new SendMessage(chatId.toString(),
                        "⏳ Зарезервировано для @" + result.pendingUsername() + " (+" + days + " дн.)\n"
                        + "Юзера пока нет в БД. Приз активируется автоматически, когда он "
                        + "впервые откроет бот или Mini App."));
            }
        } catch (IllegalArgumentException e) {
            telegramSender.execute(new SendMessage(chatId.toString(), "⚠️ " + e.getMessage()));
        } catch (Exception e) {
            log.error("Admin /grant failed for subject={} days={}", subject, days, e);
            telegramSender.execute(new SendMessage(chatId.toString(), "❌ Ошибка: " + e.getMessage()));
        }
    }

    private void handleDisableCommand(Long chatId, String args) throws TelegramApiException {
        Long targetId = parseTelegramId(args);
        if (targetId == null) {
            telegramSender.execute(new SendMessage(chatId.toString(), "Использование: /disable <telegramId>"));
            return;
        }
        boolean disabled = adminService.disableSubscription(targetId);
        String reply = disabled
                ? "✅ Подписка отключена: " + targetId
                : "⚠️ Пользователь не найден: " + targetId;
        telegramSender.execute(new SendMessage(chatId.toString(), reply));
    }

    // ========================
    // /subs, /subcheck — состояние зеркала подписок
    // ========================

    /**
     * Owner-only. Подписки конкретного человека. Работает независимо от того, включено ли
     * зеркалирование: поддержка не должна быть слепой ни в одном режиме.
     */
    private void handleSubsCommand(Long chatId, String args) throws TelegramApiException {
        Optional<Customer> userOpt = args.isBlank() ? Optional.empty() : adminService.findUserBySubject(args);
        if (userOpt.isEmpty()) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    args.isBlank()
                            ? "Использование: /subs <telegramId | @username>"
                            : "⚠️ Пользователь не найден: " + args.trim()));
            return;
        }
        Customer user = userOpt.get();
        SendMessage out = new SendMessage(chatId.toString(),
                formatSubscriptions(user, subscriptionRegistryService.listFor(user.getTelegramId())));
        out.setParseMode("HTML");
        telegramSender.execute(out);
    }

    /**
     * Owner-only. Сверка зеркала подписок по требованию. Не просто показывает расхождения,
     * а перезаписывает зеркало из основной таблицы и докладывает, что осталось: чинить
     * руками тут нечего — данные производные.
     */
    private void handleSubCheckCommand(Long chatId) throws TelegramApiException {
        List<Long> divergent = subscriptionRegistryService.reconcileDivergent();
        long mirrored = subscriptionRegistryService.mirroredCount();
        var ledgerLink = subscriptionRegistryService.linkOrphanLedgerRows();
        StringBuilder sb = new StringBuilder("🔍 <b>Сверка подписок</b>\n\n")
                .append("Зеркальных строк: ").append(mirrored).append('\n');
        if (ledgerLink.total() > 0) {
            sb.append("Журнал: адресовано ").append(ledgerLink.linked());
            if (ledgerLink.skipped() > 0) {
                sb.append(", без адреса ").append(ledgerLink.skipped())
                        .append(" (несколько подписок у человека — адрес не угадываем)");
            }
            sb.append('\n');
        }
        if (divergent.isEmpty()) {
            sb.append("Расхождений: нет ✅");
        } else {
            sb.append("Не удалось починить: <b>").append(divergent.size()).append("</b>\n")
                    .append(divergent.stream().limit(15).map(String::valueOf)
                            .collect(java.util.stream.Collectors.joining(", ")))
                    .append("\n\n<i>Перезапись зеркала не помогла. ⚠️ Из таблицы подписок "
                            + "читаются кандидаты планировщиков и выдача подписки — у этих "
                            + "людей сейчас неверный срок или статус. Детали — /subs &lt;id&gt;.</i>");
        }
        SendMessage out = new SendMessage(chatId.toString(), sb.toString());
        out.setParseMode("HTML");
        telegramSender.execute(out);
    }

    /**
     * Owner-only. Переключение человека на другую его подписку: текущая замораживается
     * (остаток встаёт), выбранная оттаивает.
     * <p>
     * Пока это ЕДИНСТВЕННЫЙ вход в переключение — кнопок у юзеров нет намеренно: механику
     * сначала обкатывают на своём аккаунте, включая обрыв посреди операции, и только потом
     * отдают людям.
     */
    private void handleSwitchSubCommand(Long chatId, String args) throws TelegramApiException {
        String[] parts = args.isBlank() ? new String[0] : args.trim().split("\\s+");
        if (parts.length < 2) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "Использование: /switchsub <telegramId | @username> <id подписки>\n"
                            + "Список подписок с их id — /subs <telegramId>"));
            return;
        }
        Optional<Customer> userOpt = adminService.findUserBySubject(parts[0]);
        if (userOpt.isEmpty()) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "⚠️ Пользователь не найден: " + parts[0]));
            return;
        }
        Long subscriptionId;
        try {
            subscriptionId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "⚠️ id подписки должен быть числом. Список — /subs " + parts[0]));
            return;
        }

        Customer user = userOpt.get();
        com.orchestra.subscriptions.service.SubscriptionSwitchService.Result result;
        try {
            // Ловим СНАРУЖИ транзакции переключения: нарушение уникального индекса
            // «одна текущая подписка» отравляет транзакцию Postgres, и обрабатывать его
            // внутри было бы нельзя.
            result = subscriptionSwitchService.switchTo(user.getTelegramId(), subscriptionId);
        } catch (Exception e) {
            log.error("Переключение подписки не удалось: telegramId={} subId={}",
                    user.getTelegramId(), subscriptionId, e);
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "❌ Переключить не удалось: " + e.getClass().getSimpleName()
                            + ". Состояние не изменилось — проверьте /subs " + parts[0]));
            return;
        }

        String reply = switch (result.outcome()) {
            case NOT_FOUND -> "⚠️ У этого человека нет подписки с id " + subscriptionId
                    + ". Список — /subs " + parts[0];
            case ALREADY_CURRENT -> "ℹ️ Эта подписка и так текущая — ничего не менял.";
            // Аварийный тормоз и суточный лимит — гейты ПОЛЬЗОВАТЕЛЬСКОГО пути (switchByUser).
            // Админская команда зовёт switchTo напрямую именно затем, чтобы чинить состояние,
            // когда пользовательский путь заперт. Сюда попасть нельзя — но молчать, если
            // попали, значит однажды разбирать «команда ничего не ответила».
            case PAUSED, RATE_LIMITED -> "⚠️ Неожиданный ответ сервиса (" + result.outcome()
                    + "): админское переключение не должно проходить через пользовательские "
                    + "гейты. Состояние не изменилось.";
            case EMPTY -> "⚠️ На подписке id=" + subscriptionId + " не осталось оплаченных дней. "
                    + "Включить её значит отключить человеку доступ — сначала пополните её. "
                    + "Список — /subs " + parts[0];
            case SWITCHED -> {
                // Панель обновляем ПОСЛЕ фиксации в базе: недоступная панель не должна
                // отменять уже принятое решение, её догонит реконсилер.
                subscriptionSwitchService.pushToPanelAfterCommit(result);
                yield "✅ Переключено на подписку id=" + result.activatedSubscriptionId()
                        + "\nОсталось на ней: "
                        + com.orchestra.subscriptions.service.SubscriptionTime
                                .displayDays(result.activatedRemainingSeconds()) + " дн."
                        + (result.parkedSubscriptionId() != null
                                ? "\nЗаморожена: id=" + result.parkedSubscriptionId() : "")
                        + "\n\nПанель обновляется отдельно — если она была недоступна, "
                        + "реконсилер добьёт в течение минуты.";
            }
        };
        telegramSender.execute(new SendMessage(chatId.toString(), reply));
    }

    /**
     * Рендер {@code /subs}. Делегирует общему форматтеру: админский экран и юзерский «Мои
     * подписки» обязаны описывать одно состояние одними словами, иначе разбор жалобы
     * превращается в сверку двух разных описаний.
     */
    static String formatSubscriptions(Customer user, List<com.orchestra.subscriptions.model.Subscription> subs) {
        return com.orchestra.subscriptions.util.SubscriptionFormat.adminList(user, subs);
    }

    // ========================
    // /ledger — куда делись дни у конкретного юзера
    // ========================

    /**
     * Owner-only. История изменений оплаченного срока: что, когда и на сколько его двигало.
     * Раньше ответ на «у меня пропали дни» собирался по логам, которые ротируются, — теперь
     * это один запрос.
     */
    private void handleLedgerCommand(Long chatId, String args) throws TelegramApiException {
        Optional<Customer> userOpt = args.isBlank() ? Optional.empty() : adminService.findUserBySubject(args);
        if (userOpt.isEmpty()) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    args.isBlank()
                            ? "Использование: /ledger <telegramId | @username>"
                            : "⚠️ Пользователь не найден: " + args.trim()));
            return;
        }
        Customer user = userOpt.get();
        // id → номер подписки: в истории должно стоять то же «#2», которое видит человек
        // на экране «Мои подписки» — иначе разбор жалобы превращается в сопоставление id.
        java.util.Map<Long, Integer> seqById = new java.util.HashMap<>();
        for (com.orchestra.subscriptions.model.Subscription sub
                : subscriptionRepository.findByTelegramIdOrderBySeqAsc(user.getTelegramId())) {
            seqById.put(sub.getId(), sub.getSeq());
        }
        SendMessage out = new SendMessage(chatId.toString(), formatLedger(
                user, subscriptionLedgerService.history(user.getTelegramId()), seqById));
        out.setParseMode("HTML");
        telegramSender.execute(out);
    }

    /**
     * Рендер {@code /ledger}. Package-private + static — чистая функция под юнит-тест.
     *
     * @param seqById номер подписки по её id — чтобы в истории стояло «#2», а не «id=57».
     *                Владелец разбирает жалобу по тем же номерам, которые видит человек.
     */
    static String formatLedger(Customer user, List<com.orchestra.subscriptions.model.SubscriptionLedger> rows,
                               java.util.Map<Long, Integer> seqById) {
        String tag = user.getTelegramUsername() != null && !user.getTelegramUsername().isBlank()
                ? "@" + HtmlUtils.htmlEscape(user.getTelegramUsername(), "UTF-8")
                : "id=" + user.getTelegramId();
        StringBuilder sb = new StringBuilder("🧾 <b>История срока</b> — ").append(tag).append("\n\n");
        if (rows.isEmpty()) {
            sb.append("<i>Записей нет: журнал ведётся только с 2026-08-12, более ранние "
                    + "изменения срока в него не попали.</i>");
            return sb.toString();
        }
        for (com.orchestra.subscriptions.model.SubscriptionLedger row : rows) {
            sb.append(TimeFormat.mskDate(row.getOccurredAt())).append(" · <b>")
                    .append(row.getKind()).append("</b> ")
                    .append(signedDays(row.getDeltaSeconds()))
                    .append(" → остаток ")
                    .append(com.orchestra.subscriptions.service.SubscriptionTime.displayDays(
                            row.getBalanceAfterSeconds()))
                    .append(" дн.");
            // Адрес события. У человека с одной подпиской пометка только шумела бы;
            // у человека с несколькими без неё историю не разобрать вообще.
            if (row.getSubscriptionId() != null && seqById.size() > 1) {
                Integer seq = seqById.get(row.getSubscriptionId());
                sb.append(seq != null ? " · подписка #" + seq
                        : " · подписка id=" + row.getSubscriptionId());
            }
            if (row.getNote() != null && !row.getNote().isBlank()) {
                sb.append("\n<i>").append(HtmlUtils.htmlEscape(row.getNote(), "UTF-8")).append("</i>");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** «+30 дн.» / «−12 дн.» / «+4 ч» — часы, когда сутки округлились бы в ноль и потеряли смысл. */
    private static String signedDays(long deltaSeconds) {
        if (deltaSeconds == 0) {
            return "±0";
        }
        String sign = deltaSeconds > 0 ? "+" : "−";
        long abs = Math.abs(deltaSeconds);
        if (abs < com.orchestra.subscriptions.service.SubscriptionTime.SECONDS_PER_DAY) {
            return sign + Math.max(1, abs / 3600) + " ч";
        }
        return sign + (abs / com.orchestra.subscriptions.service.SubscriptionTime.SECONDS_PER_DAY) + " дн.";
    }

    /** Парсит первое слово как Long. Пустая/невалидная строка → null. */
    private static Long parseTelegramId(String raw) {
        if (raw == null) return null;
        String first = raw.trim().split("\\s+", 2)[0];
        if (first.isEmpty()) return null;
        try {
            return Long.parseLong(first);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Парсит дни в диапазоне [1..3650]. Иначе null. */
    private static Integer parseDays(String raw) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 1 || value > 3650) return null;
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ========================
    // /broadcast
    // ========================

    /**
     * Черновик рассылки. Содержимое собирается из самого сообщения владельца, а не из одной
     * строки: там же лежат картинка и разметка, а в разметке — premium-эмодзи, которые иначе
     * не пережили бы пересылку (см. {@link Entities}).
     */
    private void handleBroadcastCommand(Long chatId, String args, Message source) throws TelegramApiException {
        BroadcastService.BroadcastContent content = broadcastContent(source, args, 1);
        if (content.isEmpty()) {
            telegramSender.execute(new SendMessage(chatId.toString(), broadcastUsage()));
            return;
        }
        if (content.tooLong()) {
            telegramSender.execute(new SendMessage(chatId.toString(), tooLongMessage(content)));
            return;
        }

        BroadcastService.BroadcastDraft draft = broadcastService.prepare(content);

        // Превью — не пересказ черновика, а он сам: тот же вызов, что уйдёт людям. Расписать
        // рассылку словами тут нельзя в принципе: premium-эмодзи не имеет текстового вида, и
        // «показать» его можно только отправив. Заодно это репетиция запроса — кривой HTML
        // или протухший file_id падают здесь, на владельце, а не на первом получателе.
        try {
            broadcastService.send(chatId.toString(), content);
        } catch (TelegramApiException e) {
            broadcastService.consumeDraft(draft.id()); // черновик, который не показать, не нужен
            log.warn("Broadcast preview failed", e);
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "❌ Черновик не отправился даже вам: " + e.getMessage() + "\n\n"
                    + (content.html()
                        ? "Похоже на невалидный HTML — проверьте теги или экранируйте < и >."
                        : "Проверьте вложение и разметку.")
                    + "\nРассылка не создана."));
            return;
        }

        String preview = "📢 <b>Черновик рассылки</b>\n"
                + "Выше — ровно то, что получат люди.\n\n"
                + "Формат: " + (content.hasPhoto() ? "фото с подписью" : "текст") + "\n"
                + "Разметка: " + (content.html() ? "HTML" : "как в вашем сообщении")
                + (content.customEmojiCount() > 0
                        ? " · premium-эмодзи: " + content.customEmojiCount() : "") + "\n"
                + "Символов: " + content.visibleLength() + " из " + content.limit() + "\n\n"
                + "Активных: " + draft.activeCount() + "\n"
                + "Отключившихся: " + draft.disabledCount() + "\n"
                + "Всего: " + draft.totalCount();

        InlineKeyboardButton activeBtn = new IkButton();
        activeBtn.setText("📨 Активным (" + draft.activeCount() + ")");
        activeBtn.setCallbackData("admin:broadcast:active:" + draft.id());

        InlineKeyboardButton disabledBtn = new IkButton();
        disabledBtn.setText("↩️ Отключившимся (" + draft.disabledCount() + ")");
        disabledBtn.setCallbackData("admin:broadcast:disabled:" + draft.id());

        InlineKeyboardButton allBtn = new IkButton();
        allBtn.setText("📢 Всем (" + draft.totalCount() + ")");
        allBtn.setCallbackData("admin:broadcast:all:" + draft.id());

        InlineKeyboardButton cancelBtn = new IkButton();
        cancelBtn.setText("❌ Отмена");
        cancelBtn.setCallbackData("admin:broadcast:cancel:" + draft.id());

        InlineKeyboardMarkup markup = new IkMarkup();
        // «Всем» отдельной строкой, а не рядом с остальными: это самая широкая и самая
        // необратимая кнопка, и промах пальцем по соседней не должен её задевать.
        markup.setKeyboard(List.of(
                new InlineKeyboardRow(activeBtn, disabledBtn),
                new InlineKeyboardRow(allBtn),
                new InlineKeyboardRow(cancelBtn)
        ));

        SendMessage msg = new IkSendMessage();
        msg.setChatId(chatId.toString());
        msg.setText(preview);
        msg.setParseMode("HTML");
        msg.disableWebPagePreview();
        msg.setReplyMarkup(markup);
        telegramSender.execute(msg);
    }

    /**
     * Владелец прислал боту картинку. Фото — единственный способ занести в рассылку
     * изображение: {@code file_id} существует только внутри Telegram, а загружать файл заново
     * на каждого получателя было бы тысячей закачек вместо одной ссылки.
     * <p>
     * Команда читается из подписи к картинке: {@code /broadcast <текст>} — обычный черновик,
     * {@code /broadcast_to <id> <текст>} — проверка на одном человеке. Подпись без команды —
     * отвечаем подсказкой и {@code file_id}, как и на присланный ролик.
     * <p>
     * Альбом рассылкой не станет: Telegram присылает его по картинке на апдейт и вешает
     * подпись только на первую, так что уедет именно она — одна.
     * <p>
     * Гейт на владельца стоит в {@link SubscriptionBot}: чужие картинки бот игнорирует молча.
     */
    public void handleOwnerPhoto(Update update, Long chatId) throws TelegramApiException {
        Message msg = update.getMessage();
        String caption = msg.getCaption() == null ? "" : msg.getCaption();
        String[] parts = caption.split("\\s+", 2);
        String args = parts.length > 1 ? parts[1] : "";
        switch (parts[0]) {
            case "/broadcast" -> handleBroadcastCommand(chatId, args, msg);
            case "/broadcast_to" -> handleBroadcastToCommand(chatId, args, msg);
            default -> {
                // Альбом Telegram присылает по одной картинке на апдейт, а подпись кладёт
                // только на первую. Отвечать подсказкой на каждую из остальных — три-четыре
                // одинаковых сообщения в ответ на одно действие; молчим.
                if (msg.getMediaGroupId() != null) {
                    return;
                }
                SendMessage hint = new SendMessage(chatId.toString(),
                        "🖼 <b>Картинка получена</b>\n\n"
                        + "Чтобы разослать её — пришлите картинку с подписью:\n"
                        + "<code>/broadcast Текст под фото</code>\n\n"
                        + "Или ответьте на неё командой <code>/broadcast Текст</code>.\n\n"
                        + "Проверить на себе: <code>/broadcast_to "
                        + chatId + " Текст</code>\n\n"
                        + "file_id: <code>" + HtmlUtils.htmlEscape(
                                largestPhotoId(msg) == null ? "—" : largestPhotoId(msg))
                        + "</code>");
                hint.setParseMode("HTML");
                telegramSender.execute(hint);
            }
        }
    }

    private static String broadcastUsage() {
        return "Использование: /broadcast <текст>\n\n"
                + "С картинкой: пришлите фото и напишите команду в подписи к нему —\n"
                + "/broadcast Текст под фото\n"
                + "(или ответьте командой на сообщение с фото).\n\n"
                + "Premium-эмодзи, жирный, ссылки — вставляйте прямо в сообщение: разметка\n"
                + "уедет получателям как есть.\n"
                + "Если ничем из этого не пользоваться, текст разбирается как HTML:\n"
                + "<b>, <i>, <code>, <a href=...>.";
    }

    /**
     * Текст не влезает в лимит Telegram — отправка упала бы у всех сразу, а не у одного.
     * <p>
     * Подсказка называет и выход из положения: картинка цепляется не только вложением, но и
     * ответом на сообщение с ней, а вместе с картинкой приезжает вчетверо меньший лимит. Совет
     * «разошлите без фото» без объяснения, как именно, отправлял бы искать несуществующий ключ.
     */
    private static String tooLongMessage(BroadcastService.BroadcastContent content) {
        return "⚠️ " + (content.hasPhoto() ? "Подпись к фото" : "Текст")
                + " длиннее лимита Telegram: " + content.visibleLength()
                + " из " + content.limit() + " символов.\n\n"
                + (content.hasPhoto()
                    ? "У подписи к картинке лимит вчетверо меньше, чем у обычного сообщения ("
                      + TEXT_LIMIT + "). Сократите подпись — или разошлите текст отдельным "
                      + "сообщением: командой без картинки и не ответом на сообщение с ней."
                    : "Сократите текст.");
    }

    /**
     * Что рассылать. Разбирает три формы, в порядке приоритета:
     * <ol>
     *   <li>команда в подписи к картинке — фото плюс подпись без команды;</li>
     *   <li>обычная команда с текстом — текст без команды, картинка (если есть) из сообщения,
     *       на которое отвечают;</li>
     *   <li>команда без текста ответом на сообщение — берём то сообщение целиком, как есть.</li>
     * </ol>
     * {@code source == null} — вызов без апдейта (тесты, внутренние вызовы): остаётся голый
     * текст в HTML-режиме.
     *
     * @param tokensToDrop сколько первых слов срезать: 1 у {@code /broadcast},
     *                     2 у {@code /broadcast_to} — там ещё id получателя
     */
    static BroadcastService.BroadcastContent broadcastContent(Message source, String args,
                                                              int tokensToDrop) {
        if (source == null) {
            return BroadcastService.BroadcastContent.html(args.trim());
        }
        if (source.hasPhoto()) {
            return compose(source.getCaption(), source.getCaptionEntities(),
                    tokensToDrop, largestPhotoId(source));
        }
        Message reply = source.getReplyToMessage();
        String replyPhotoId = largestPhotoId(reply);
        BroadcastService.BroadcastContent own =
                compose(source.getText(), source.getEntities(), tokensToDrop, replyPhotoId);
        if (!own.text().isEmpty() || reply == null) {
            return own;
        }
        // Команда без собственного текста, но ответом на сообщение — рассылаем то сообщение:
        // и картинку, и подпись с её разметкой. Срезать в нём нечего, команды там нет.
        //
        // HTML здесь выключен принудительно, даже если разметки в сообщении нет вовсе: его
        // писали как текст, а не как разметку для бота, и трактовать чужие угловые скобки
        // как теги — значит либо испортить смысл («цена < 100»), либо вовсе не отправить.
        boolean replyIsText = reply.getText() != null;
        return compose(replyIsText ? reply.getText() : reply.getCaption(),
                replyIsText ? reply.getEntities() : reply.getCaptionEntities(),
                0, replyPhotoId, false);
    }

    /**
     * Срезает {@code tokensToDrop} первых слов (команду и, для {@code /broadcast_to}, id),
     * переносит на остаток разметку и решает, HTML это или сущности.
     * <p>
     * Package-private и static — чистая функция, покрыта юнит-тестом: смещения сущностей
     * ошибаются молча (жирным становится соседнее слово, эмодзи «съезжает» на символ),
     * и заметить это в проде можно только по уже отправленной рассылке.
     */
    static BroadcastService.BroadcastContent compose(String raw, List<MessageEntity> rawEntities,
                                                     int tokensToDrop, String photoFileId) {
        return compose(raw, rawEntities, tokensToDrop, photoFileId, true);
    }

    /**
     * @param htmlAllowed можно ли трактовать текст как HTML. false — для содержимого, взятого
     *                    из чужого сообщения: его писали не для бота, и разбирать его разметку
     *                    мы не вправе.
     */
    static BroadcastService.BroadcastContent compose(String raw, List<MessageEntity> rawEntities,
                                                     int tokensToDrop, String photoFileId,
                                                     boolean htmlAllowed) {
        String source = raw == null ? "" : raw;
        int cut = 0;
        for (int token = 0; token < tokensToDrop; token++) {
            while (cut < source.length() && !Character.isWhitespace(source.charAt(cut))) cut++;
            while (cut < source.length() && Character.isWhitespace(source.charAt(cut))) cut++;
        }
        int end = source.length();
        while (end > cut && Character.isWhitespace(source.charAt(end - 1))) end--;
        String text = cut >= end ? "" : source.substring(cut, end);
        List<MessageEntity> entities = Entities.reanchor(rawEntities, cut, text.length());
        // Подсветка самой команды разметкой не считается. Если владелец ничего не форматировал
        // средствами Telegram, разбираем текст как HTML — это исторический контракт команды, и
        // набранный руками <b> обязан продолжать работать.
        boolean html = htmlAllowed && !Entities.hasFormatting(entities);
        return new BroadcastService.BroadcastContent(text, entities, photoFileId, html);
    }

    /**
     * {@code file_id} самого крупного варианта картинки. Telegram присылает лесенку размеров;
     * берём максимальный — рассылка не должна выглядеть как превью из списка чатов.
     */
    private static String largestPhotoId(Message message) {
        List<PhotoSize> sizes = message == null ? null : message.getPhoto();
        if (sizes == null) {
            return null;
        }
        PhotoSize best = null;
        for (PhotoSize size : sizes) {
            if (size == null || size.getFileId() == null) {
                continue;
            }
            if (best == null || pixels(size) > pixels(best)) {
                best = size;
            }
        }
        return best == null ? null : best.getFileId();
    }

    private static long pixels(PhotoSize size) {
        long width = size.getWidth() == null ? 0L : size.getWidth();
        long height = size.getHeight() == null ? 0L : size.getHeight();
        return width * height;
    }

    public void handleAdminCallback(Long chatId, String data) throws TelegramApiException {
        // admin:broadcast:{active|all|cancel}:{draftId}
        String[] parts = data.split(":", 4);
        if (parts.length < 4 || !"broadcast".equals(parts[1])) {
            return;
        }
        String action = parts[2];
        String draftId = parts[3];

        if ("cancel".equals(action)) {
            broadcastService.consumeDraft(draftId); // просто удаляем
            telegramSender.execute(new SendMessage(chatId.toString(), "🚫 Рассылка отменена."));
            return;
        }

        BroadcastService.BroadcastContent content = broadcastService.consumeDraft(draftId);
        if (content == null) {
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "⚠️ Черновик не найден или истёк. Создайте новый через /broadcast."));
            return;
        }
        // Сопоставление явное и «отказ по умолчанию». Раньше здесь стоял тернарник, где всё,
        // что не «active», означало ALL. С двумя кнопками это работало, но с появлением
        // третьей любая опечатка в callback-данных стала бы рассылкой по всей базе — а это
        // единственное действие в боте, которое нельзя отменить.
        BroadcastService.Target target = switch (action) {
            case "active" -> BroadcastService.Target.ACTIVE;
            case "disabled" -> BroadcastService.Target.DISABLED;
            case "all" -> BroadcastService.Target.ALL;
            default -> null;
        };
        if (target == null) {
            log.warn("Broadcast callback с неизвестным действием '{}' — рассылка не запущена", action);
            telegramSender.execute(new SendMessage(chatId.toString(),
                    "⚠️ Непонятная кнопка рассылки. Черновик погашен, создайте новый через /broadcast."));
            return;
        }

        String audience = switch (target) {
            case ACTIVE -> "активным";
            case DISABLED -> "отключившимся";
            case ALL -> "всем";
        };
        telegramSender.execute(new SendMessage(chatId.toString(),
                "🚀 Рассылка запущена (" + audience + (content.hasPhoto() ? ", с фото" : "")
                + "). Жду результата…"));

        broadcastService.execute(content, target).whenComplete((stats, err) -> {
            String report;
            if (err != null) {
                log.error("Broadcast failed", err);
                report = "❌ Рассылка прервана: " + err.getMessage();
            } else {
                report = "✅ Рассылка завершена\n"
                        + "Успешно: " + stats.success() + "\n"
                        + "Не доставлено: " + stats.failed() + "\n"
                        + "Длительность: " + stats.duration().getSeconds() + "с";
            }
            try {
                telegramSender.execute(new SendMessage(chatId.toString(), report));
            } catch (TelegramApiException e) {
                log.warn("Failed to send broadcast completion report", e);
            }
        });
    }

    private String formatUserCard(AdminService.UserCard card) {
        Customer u = card.user();
        StringBuilder sb = new StringBuilder();
        sb.append("👤 <b>Юзер ").append(u.getTelegramId()).append("</b>\n");
        if (u.getTelegramUsername() != null && !u.getTelegramUsername().isBlank()) {
            sb.append("@").append(u.getTelegramUsername()).append("\n");
        }
        sb.append("Статус: ").append(u.getStatus() == Customer.Status.ACTIVE ? "✅ ACTIVE" : "❌ DISABLED").append("\n");
        sb.append("Истекает: ")
                .append(TimeFormat.mskDateTime(u.getExpiresAt()));
        if (u.getExpiresAt() != null) {
            long daysLeft = java.time.Duration.between(LocalDateTime.now(), u.getExpiresAt()).toDays();
            sb.append(" (").append(daysLeft >= 0 ? "+" : "").append(daysLeft).append("д)");
        }
        sb.append("\n");
        if (u.getCreatedAt() != null) {
            sb.append("Создан: ").append(TimeFormat.mskDateTime(u.getCreatedAt())).append("\n");
        }
        if (u.getProviderUsername() != null) {
            sb.append("providerUsername: <code>").append(u.getProviderUsername()).append("</code>\n");
        }
        if (u.getSubscriptionLink() != null) {
            sb.append("Ссылка: <code>").append(u.getSubscriptionLink()).append("</code>\n");
        }

        if (!card.recentPayments().isEmpty()) {
            sb.append("\n💰 <b>Последние платежи (").append(card.recentPayments().size()).append("):</b>\n");
            for (Payment p : card.recentPayments()) {
                LocalDateTime when = p.getPaidAt() != null ? p.getPaidAt() : p.getCreatedAt();
                sb.append("• ")
                        .append(TimeFormat.mskDateTime(when))
                        .append(" · ").append(Money.rub(p.getAmountKopeks())).append(" ₽")
                        .append(" · ").append(p.getProvider() != null ? p.getProvider().name() : "—")
                        .append(" · ").append(p.getStatus() != null ? p.getStatus().name() : "—")
                        .append(" · ").append(p.getPlanId())
                        .append("\n");
            }
        } else {
            sb.append("\n💰 Платежей нет.\n");
        }
        return sb.toString();
    }
}
