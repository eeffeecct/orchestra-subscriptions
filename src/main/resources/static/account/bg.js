// ====================================================================
//  Matrix-rain бэкграунд для web-ЛК (одинаковый с Mini App)
// ====================================================================
//
// Спавнит N вертикальных колонок «O R C H E S T R A · …» в #bg-dvd,
// каждая с уникальной длительностью/задержкой анимации. Чистый CSS-transform
// (writing-mode + translateY) → GPU-accelerated, без RAF. Скейлится от
// размера экрана: 1 колонка на ~60px ширины, в диапазоне [6, 14].
//
// Отдельный файл (не внутри script.js) чтобы переиспользовать на login.html
// и expired.html без дублирования.

(function () {
    function spawnRainColumns() {
        const wrap = document.getElementById('bg-dvd');
        if (!wrap) return;
        wrap.innerHTML = '';

        const text = 'O R C H E S T R A · ';
        const fill = text.repeat(8);

        const w = window.innerWidth;
        const cols = Math.max(6, Math.min(14, Math.floor(w / 60)));

        for (let i = 0; i < cols; i++) {
            const col = document.createElement('div');
            col.className = 'rain-col';
            col.textContent = fill;
            col.style.left = `${(i + 0.5) * (100 / cols)}%`;
            col.style.animationDuration = `${28 + (i * 3.7) % 14}s`;
            col.style.animationDelay = `${-i * 4.5}s`;
            col.style.opacity = String(0.09 + (i % 3) * 0.025);
            col.style.fontSize = `${11 + (i % 3)}px`;
            wrap.appendChild(col);
        }
    }

    spawnRainColumns();

    // Throttled re-spawn на resize — чтобы не дёргать на каждый пиксель при
    // изменении размера окна на desktop.
    let timer = null;
    window.addEventListener('resize', () => {
        clearTimeout(timer);
        timer = setTimeout(spawnRainColumns, 200);
    });
})();
