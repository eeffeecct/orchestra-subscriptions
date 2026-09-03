package com.orchestra.subscriptions.service.payment;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сторож против одиннадцатого места создания платежа.
 * <p>
 * Весь этап стоит на одном свойстве: у каждой строки платежа с рождения написано, в какую
 * подписку идут деньги. Свойство держится ровно до тех пор, пока строку заводит одна фабрика —
 * новый {@code Payment.builder()} где-нибудь в контроллере вернул бы {@code subscription_id
 * IS NULL}, и заметить это можно было бы только по разбору «куда делись дни». Обычным
 * поведенческим тестом такое не ловится: чужая строка просто не попадёт ни в один сценарий.
 * <p>
 * Поэтому проверка структурная и читает исходники. Она дешёвая и падает ровно в тот момент,
 * когда кто-то (включая будущего меня) заводит платёж в обход фабрики.
 */
class PaymentFactoryGuardTest {

    private static final Pattern PAYMENT_BUILDER = Pattern.compile("Payment\\s*\\.\\s*builder\\s*\\(");

    /** Единственный файл, которому позволено строить платёж. */
    private static final String FACTORY_FILE = "PaymentFactory.java";

    @Test
    void paymentRowsAreBornOnlyInTheFactory() throws IOException {
        Path mainJava = Path.of("src", "main", "java");
        assertThat(Files.isDirectory(mainJava))
                .as("тест читает исходники относительно корня модуля — %s", mainJava.toAbsolutePath())
                .isTrue();

        List<String> offenders;
        try (Stream<Path> files = Files.walk(mainJava)) {
            offenders = files
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals(FACTORY_FILE))
                    .filter(PaymentFactoryGuardTest::buildsPayment)
                    .map(Path::toString)
                    .sorted()
                    .toList();
        }

        assertThat(offenders)
                .as("Payment.builder() вне PaymentFactory: такой платёж родится без цели "
                        + "(subscription_id NULL). Заводите строку через PaymentFactory")
                .isEmpty();
    }

    private static boolean buildsPayment(Path file) {
        try {
            return PAYMENT_BUILDER.matcher(Files.readString(file, StandardCharsets.UTF_8)).find();
        } catch (IOException e) {
            throw new IllegalStateException("Не прочитать " + file, e);
        }
    }
}
