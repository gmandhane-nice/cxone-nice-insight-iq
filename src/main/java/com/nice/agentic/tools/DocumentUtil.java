package com.nice.agentic.tools;

import software.amazon.awssdk.core.document.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DocumentUtil {
    private DocumentUtil() {}

    public static Document toDocument(Object value) {
        if (value == null) return Document.fromNull();
        if (value instanceof Document d) return d;
        if (value instanceof String s) return Document.fromString(s);
        if (value instanceof Boolean b) return Document.fromBoolean(b);
        if (value instanceof Integer i) return Document.fromNumber(i);
        if (value instanceof Long l) return Document.fromNumber(l);
        if (value instanceof Double d) return Document.fromNumber(d);
        if (value instanceof Float f) return Document.fromNumber(f);
        if (value instanceof Map<?, ?> m) {
            Map<String, Document> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(e.getKey().toString(), toDocument(e.getValue()));
            }
            return Document.fromMap(out);
        }
        if (value instanceof List<?> list) {
            List<Document> out = new ArrayList<>();
            for (Object o : list) out.add(toDocument(o));
            return Document.fromList(out);
        }
        return Document.fromString(String.valueOf(value));
    }

    public static String stringArg(Document args, String key, String defaultValue) {
        if (args == null || !args.isMap()) return defaultValue;
        Document v = args.asMap().get(key);
        if (v == null || !v.isString()) return defaultValue;
        return v.asString();
    }
}
