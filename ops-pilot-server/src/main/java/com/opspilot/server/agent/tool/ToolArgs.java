package com.opspilot.server.agent.tool;

import java.util.Map;

/** 工具参数解析小工具，统一做类型转换与上限截断。 */
final class ToolArgs {

    private ToolArgs() {}

    static String str(Map<String, Object> args, String key, String def) {
        Object v = args.get(key);
        return v == null || String.valueOf(v).isBlank() ? def : String.valueOf(v).trim();
    }

    /** 整数参数，超出 [1, max] 截断 */
    static Integer intVal(Map<String, Object> args, String key, Integer def, int max) {
        Object v = args.get(key);
        if (v == null) {
            return def;
        }
        int n = v instanceof Number num ? num.intValue() : Integer.parseInt(String.valueOf(v));
        return Math.min(Math.max(n, 1), max);
    }

    static Long longVal(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) {
            return null;
        }
        return v instanceof Number num ? num.longValue() : Long.parseLong(String.valueOf(v));
    }
}
