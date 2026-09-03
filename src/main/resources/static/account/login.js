// ====================================================================
//  /account/login — публичная форма запроса magic-link
// ====================================================================
//
// Юзер вводит email, шлём POST /auth/request. Бэк возвращает 200 с тем же
// сообщением и для known и для unknown email (защита от enumeration). На
// 400 invalid_format показываем ошибку под полем. На 429 — текст «много
// запросов, подождите».
//
// После успешной отправки оставляем форму в read-only состоянии: юзер уже
// должен идти в почту. Если письма нет — повторный submit через 1 минуту
// (cooldown в MagicLinkRateLimiter).

const API_BASE = '';

const form = document.getElementById('login-form');
const emailInput = document.getElementById('login-email');
const submitBtn = document.getElementById('login-submit-btn');
const submitLabel = document.getElementById('login-submit-label');
const resultEl = document.getElementById('login-result');
// Исходная подпись кнопки — её надо вернуть после ошибки. Берём из разметки, чтобы
// текст не разъезжался с login.html при правке одного из двух мест.
const SUBMIT_LABEL_IDLE = submitLabel.textContent;

function setResult(text, kind) {
    resultEl.textContent = text;
    resultEl.classList.remove('login-result--ok', 'login-result--err', 'login-result--info');
    if (kind) resultEl.classList.add('login-result--' + kind);
    resultEl.hidden = !text;
}

form.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (submitBtn.hasAttribute('disabled')) return;
    const value = (emailInput.value || '').trim();
    if (!value) {
        setResult('Введите email', 'err');
        return;
    }
    // Inline-валидация по тому же regex что на бэке — UX-helper, не security.
    const re = /^[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}$/;
    if (!re.test(value)) {
        setResult('Адрес указан неверно. Проверьте написание.', 'err');
        return;
    }

    submitBtn.setAttribute('disabled', '');
    submitBtn.classList.add('is-busy');
    submitLabel.textContent = 'Отправка…';
    setResult('Отправка письма…', 'info');

    try {
        const r = await fetch(`${API_BASE}/auth/request`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email: value }),
        });
        const data = await r.json().catch(() => ({}));
        if (r.status === 429) {
            setResult(data.message || 'Слишком много запросов. Повторите попытку позже.', 'err');
        } else if (r.status === 400) {
            setResult(data.message || 'Проверьте написание адреса.', 'err');
        } else if (!r.ok) {
            setResult('Запрос не выполнен. Повторите попытку через минуту.', 'err');
        } else {
            // Generic message — одинаковый для known/unknown email.
            setResult(data.message || 'Если адрес привязан, ссылка для входа отправлена.', 'ok');
            // Оставляем форму в read-only состоянии, юзер идёт в почту.
            emailInput.setAttribute('readonly', '');
            submitLabel.textContent = 'Письмо отправлено';
            return; // не снимаем disabled — повторный отправ не нужен
        }
    } catch (err) {
        console.error('login request failed:', err);
        setResult('Отправить не удалось. Проверьте подключение к интернету.', 'err');
    }

    submitBtn.removeAttribute('disabled');
    submitBtn.classList.remove('is-busy');
    submitLabel.textContent = SUBMIT_LABEL_IDLE;
});
