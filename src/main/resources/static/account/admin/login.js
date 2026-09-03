/*
 * Экран входа в админ-панель. Единственная задача — объяснить, почему сюда попали:
 * причина приезжает в ?e= от AdminPanelController. Inline-скрипта нет намеренно,
 * CSP страниц /account/* не разрешает 'unsafe-inline'.
 */
(function () {
    var REASONS = {
        token: 'Ссылка недействительна или истекла. Запросите новую командой /admin в боте.',
        rate: 'Слишком много попыток входа. Подождите несколько минут.',
        session: 'Сессия закончилась. Запросите новую ссылку командой /admin в боте.'
    };
    var code = new URLSearchParams(window.location.search).get('e');
    var text = REASONS[code];
    if (!text) return;
    var box = document.getElementById('gate-reason');
    box.textContent = text;
    box.hidden = false;
}());
