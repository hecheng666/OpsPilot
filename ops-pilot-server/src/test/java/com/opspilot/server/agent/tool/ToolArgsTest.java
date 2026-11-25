package com.opspilot.server.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolArgsTest {

    @Test
    @DisplayName("字符串参数去空格，空值与缺失回退默认值")
    void strTrimsAndFallsBack() {
        assertThat(ToolArgs.str(Map.of("k", "  abc "), "k", "def")).isEqualTo("abc");
        assertThat(ToolArgs.str(Map.of("k", "   "), "k", "def")).isEqualTo("def");
        assertThat(ToolArgs.str(Map.of(), "k", "def")).isEqualTo("def");

        Map<String, Object> withNull = new HashMap<>();
        withNull.put("k", null);
        assertThat(ToolArgs.str(withNull, "k", "def")).isEqualTo("def");
    }

    @Test
    @DisplayName("整数参数截断到 [1, max]")
    void intValClampsRange() {
        assertThat(ToolArgs.intVal(Map.of("n", 50), "n", 5, 10)).isEqualTo(10);
        assertThat(ToolArgs.intVal(Map.of("n", 0), "n", 5, 10)).isEqualTo(1);
        assertThat(ToolArgs.intVal(Map.of("n", 7), "n", 5, 10)).isEqualTo(7);
        assertThat(ToolArgs.intVal(Map.of(), "n", 5, 10)).isEqualTo(5);
    }

    @Test
    @DisplayName("整数参数兼容字符串数字")
    void intValParsesString() {
        assertThat(ToolArgs.intVal(Map.of("n", "9"), "n", 5, 10)).isEqualTo(9);
    }

    @Test
    @DisplayName("长整型参数兼容 Number 与字符串，缺失返回 null")
    void longValParsesNumberAndString() {
        assertThat(ToolArgs.longVal(Map.of("id", 77), "id")).isEqualTo(77L);
        assertThat(ToolArgs.longVal(Map.of("id", "88"), "id")).isEqualTo(88L);
        assertThat(ToolArgs.longVal(Map.of(), "id")).isNull();
    }

    @Test
    @DisplayName("非法数字直接抛异常，由调用方校验")
    void longValRejectsInvalidNumber() {
        assertThatThrownBy(() -> ToolArgs.longVal(Map.of("id", "abc"), "id"))
                .isInstanceOf(NumberFormatException.class);
    }
}
