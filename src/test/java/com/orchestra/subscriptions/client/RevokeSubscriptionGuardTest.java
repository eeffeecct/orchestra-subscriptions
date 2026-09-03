package com.orchestra.subscriptions.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сторож инварианта 13: <b>{@code revokeSubscription} зовётся ровно из одного места —
 * {@code AccessService.rotateSubscriptionLink}</b>.
 * <p>
 * Отзыв токена в панели необратим: старая ссылка мгновенно начинает отвечать 404 на всех
 * устройствах человека. Пока вызов один, у этого события есть ровно один осознанный повод —
 * человек сам нажал «сменить ссылку». Второй вызов, добавленный где-нибудь в обработке
 * возврата, переключения или чистки, оборвал бы доступ там, где человек ничего не просил, и
 * выглядел бы для него как поломка сервиса.
 * <p>
 * Проверка структурная и читает исходники: поведенческим тестом «нигде больше не зовут» не
 * выразить — чужой вызов просто не попадёт ни в один сценарий. Тот же приём и по той же
 * причине, что в {@code PaymentFactoryGuardTest}.
 */
class RevokeSubscriptionGuardTest {

    /** Вызов метода (не его объявление в клиенте панели). */
    private static final Pattern CALL = Pattern.compile("\\.\\s*revokeSubscription\\s*\\(");

    /** Строка, похожая на объявление метода верхнего уровня класса. */
    private static final Pattern METHOD_SIGNATURE =
            Pattern.compile("^ {4}(public|protected|private)\\s.*\\(");

    /** Единственный файл, которому позволено звать отзыв. */
    private static final String CALLER_FILE = "AccessService.java";
    /** И единственный метод внутри него. */
    private static final String CALLER_METHOD = "rotateSubscriptionLink";

    @Test
    @DisplayName("Отзыв подписочной ссылки зовётся только из смены ссылки")
    void revokeIsCalledFromRotateOnly() throws IOException {
        Path mainJava = Path.of("src", "main", "java");
        assertThat(Files.isDirectory(mainJava))
                .as("тест читает исходники относительно корня модуля — %s", mainJava.toAbsolutePath())
                .isTrue();

        List<String> callSites = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mainJava)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".java")).toList()) {
                // AccessProvider объявляет метод и зовёт его внутри себя — это не «второе место».
                if (file.getFileName().toString().equals("AccessProvider.java")) {
                    continue;
                }
                for (String enclosing : enclosingMethodsOfCalls(file)) {
                    callSites.add(file.getFileName() + "#" + enclosing);
                }
            }
        }

        assertThat(callSites)
                .as("revokeSubscription отзывает ссылку человека необратимо — вызов обязан быть один, "
                        + "из %s#%s", CALLER_FILE, CALLER_METHOD)
                .hasSize(1);
        assertThat(callSites.get(0))
                .startsWith(CALLER_FILE + "#")
                .contains(CALLER_METHOD);
    }

    /**
     * Имена методов, внутри которых встретился вызов. Метод определяем по ближайшей строке
     * выше, похожей на объявление на уровне класса (отступ ровно в четыре пробела) — этого
     * достаточно, чтобы отличить «зовут из смены ссылки» от «зовут откуда-то ещё», и не
     * требует разбора Java.
     */
    private static List<String> enclosingMethodsOfCalls(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String> found = new ArrayList<>();
        String currentMethod = "<вне метода>";
        for (String line : lines) {
            if (METHOD_SIGNATURE.matcher(line).find()) {
                currentMethod = line.trim();
            }
            if (CALL.matcher(line).find()) {
                found.add(currentMethod);
            }
        }
        return found;
    }
}
