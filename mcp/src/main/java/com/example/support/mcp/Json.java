package com.example.support.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

final class Json {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(String s) {
        try {
            Object o = MAPPER.readValue(s, Object.class);
            return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    static String write(Object o) {
        return MAPPER.writeValueAsString(o);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object o) {
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static long num(Object o, long dflt) {
        return o instanceof Number n ? n.longValue() : dflt;
    }
}
