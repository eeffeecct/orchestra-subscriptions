package com.orchestra.subscriptions.service.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Разбор поля {@code target}, которое присылает Mini App.
 * <p>
 * Проверяется одно свойство: <b>цель либо понята точно, либо не понята вовсе</b>. Молчаливый
 * откат к текущей подписке на непонятном значении означал бы, что человеку показали «зачислится
 * в подписку #2», а деньги ушли в #1 — и заметить это можно было бы только по тикету
 * «куда делись мои дни».
 */
class TargetSpecWireTest {

    @Test
    @DisplayName("Пусто и «current» — текущая подписка (так ходит старый закешированный фронт)")
    void absentTargetMeansCurrent() {
        assertThat(TargetSpec.ofWire(null).kind()).isEqualTo(TargetSpec.Kind.CURRENT);
        assertThat(TargetSpec.ofWire("").kind()).isEqualTo(TargetSpec.Kind.CURRENT);
        assertThat(TargetSpec.ofWire("   ").kind()).isEqualTo(TargetSpec.Kind.CURRENT);
        assertThat(TargetSpec.ofWire("current").kind()).isEqualTo(TargetSpec.Kind.CURRENT);
    }

    @Test
    @DisplayName("«new» — просьба завести отдельную подписку")
    void newMeansSeparateSubscription() {
        assertThat(TargetSpec.ofWire("new").kind()).isEqualTo(TargetSpec.Kind.NEW);
        assertThat(TargetSpec.ofWire("new").subscriptionId()).isNull();
    }

    @Test
    @DisplayName("Число — названная подписка (принадлежность проверит резолвер)")
    void numberMeansNamedSubscription() {
        TargetSpec spec = TargetSpec.ofWire(" 42 ");
        assertThat(spec.kind()).isEqualTo(TargetSpec.Kind.SUB);
        assertThat(spec.subscriptionId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("Непонятное значение — исключение, а не тихий откат к текущей")
    void unknownValueThrows() {
        assertThatThrownBy(() -> TargetSpec.ofWire("nwe"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TargetSpec.ofWire("CURRENT"))
                .as("регистр не прощаем: это не опечатка человека, а баг канала")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TargetSpec.ofWire("12x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
