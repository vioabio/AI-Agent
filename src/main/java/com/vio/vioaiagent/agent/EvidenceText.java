package com.vio.vioaiagent.agent;

/** Display data as text, never as executable HTML, Markdown links or instructions. */
public final class EvidenceText {
    private EvidenceText() { }
    static String inline(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replaceAll("([`*_\\[\\]|])", "\\\\$1").replaceAll("[\\r\\n\\t]", " ");
    }
    static String quote(String text) {
        return text.lines().map(line -> "> " + inline(line)).collect(java.util.stream.Collectors.joining("\n"));
    }
}
