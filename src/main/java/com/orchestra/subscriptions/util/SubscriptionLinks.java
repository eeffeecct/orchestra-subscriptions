package com.orchestra.subscriptions.util;

/**
 * Построение и форматирование подписочных ссылок для отправки подписчику.
 *
 * <p>Ссылка, которую человек вставляет в клиентское приложение, ведёт на НАШ эндпоинт выдачи
 * ({@code <publicBaseUrl>/sub/<token>}, см. {@code AccessDeliveryController}), а не к провайдеру
 * напрямую. Разница не косметическая: через собственный эндпоинт проходят учёт устройств,
 * проверка срока и заморозка — на прямой ссылке провайдера ничего этого не случилось бы.
 *
 * <p>Токен один и тот же в обеих формах, поэтому перевыпуск ссылки («выйти со всех устройств»)
 * обесценивает и утёкшую копию.
 *
 * <p>Раньше построение этой ссылки жило приватным методом в {@code WebAppController}. Вынесено
 * сюда, чтобы одна и та же ссылка отдавалась и в сообщении после оплаты, и на экране «Моя
 * подписка», и в пробном периоде, и в копии на email — без копипасты.
 */
public final class SubscriptionLinks {

    private SubscriptionLinks() {
    }

    /**
     * Строит адрес нашего эндпоинта выдачи из ссылки провайдера: извлекает токен из
     * {@code https://<host>/sub/<token>} и подставляет его в {@code <publicBaseUrl>/sub/<token>}.
     *
     * @param providerLink  подписочная ссылка из базы (полная или относительная {@code /sub/...})
     * @param publicBaseUrl публичный домен приложения ({@code app.telegram.public-base-url})
     * @return URL вида {@code <publicBaseUrl>/sub/<token>}; пустая строка, если ссылка пустая,
     *         в ней нет {@code /sub/} или не задан publicBaseUrl — тогда вызывающий показывает
     *         ссылку провайдера как есть
     */
    public static String accessLink(String providerLink, String publicBaseUrl) {
        String token = extractToken(providerLink);
        if (token == null) return "";
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) return "";
        // Убираем trailing slash чтобы не получилось //sub
        return stripTrailingSlash(publicBaseUrl) + "/sub/" + token;
    }

    /**
     * Извлекает непрозрачный токен из подписочной ссылки ({@code .../sub/<token>}), отрезая
     * query string и fragment. Тот же токен приходит в {@code /sub/<token>} — по нему
     * {@code DeviceTrackingService} находит подписку ({@code customers.subscription_token},
     * поддерживается в {@code Customer#syncSubscriptionToken}). Null — если ссылка пустая или
     * без {@code /sub/}.
     */
    public static String extractToken(String providerLink) {
        if (providerLink == null || providerLink.isBlank()) return null;
        int subIdx = providerLink.lastIndexOf("/sub/");
        if (subIdx < 0) return null;
        String token = providerLink.substring(subIdx + "/sub/".length());
        int q = token.indexOf('?');
        if (q >= 0) token = token.substring(0, q);
        int h = token.indexOf('#');
        if (h >= 0) token = token.substring(0, h);
        return token.isBlank() ? null : token;
    }

    /**
     * HTML-блок с ОДНОЙ подписочной ссылкой для сообщений Telegram (parseMode=HTML).
     *
     * <p>Ссылка ровно одна намеренно: две почти одинаковые строки заставляют человека гадать,
     * какую копировать, и часть выберет не ту. Если наш адрес построить не удалось, отдаём
     * ссылку провайдера под тем же нейтральным заголовком.
     *
     * @param providerLink  подписочная ссылка из базы
     * @param publicBaseUrl публичный домен для построения нашего адреса
     * @param emoji         рендер значка «ключ» (анимированный, если включено и id задан)
     * @return готовый HTML-фрагмент (без завершающих переводов строк)
     */
    public static String subscriptionLinkHtml(String providerLink, String publicBaseUrl, Emoji emoji) {
        String link = accessLink(providerLink, publicBaseUrl);
        if (link.isEmpty()) {
            // Фолбэк: свой адрес не построить — показываем ссылку провайдера как есть.
            return emoji.e("key") + " <b>Ссылка подписки:</b>\n"
                    + "<code>" + providerLink + "</code>";
        }
        return emoji.e("key") + " <b>Ссылка подписки</b>\n"
                + "<code>" + link + "</code>";
    }

    /**
     * Убирает один trailing-slash у базового URL, чтобы при склейке не получилось «//».
     * {@code null} → {@code null} (вызывающий сам решает, чем заменить).
     */
    public static String stripTrailingSlash(String url) {
        if (url == null) return null;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}