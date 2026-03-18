package com.clinecli;

import java.util.Map;

public final class UI {
    // ANSI escape codes
    public static final String RESET  = "\033[0m";
    public static final String BOLD   = "\033[1m";
    public static final String DIM    = "\033[2m";
    public static final String RED    = "\033[31m";
    public static final String GREEN  = "\033[32m";
    public static final String YELLOW = "\033[33m";
    public static final String CYAN   = "\033[36m";

    private UI() {}

    public static void printWelcome() {
        System.out.println(BOLD + CYAN + "\n  ╔════════════════════════════════════════╗");
        System.out.println(           "  ║     ThreatLegion  •  Terminal Agent v.0.0.1 ║");
        System.out.println(           "  ╚═════════════════════════════════════════════╝" + RESET);
        System.out.println(DIM +      "  Powered by Claude Opus 4.6");
        System.out.println(           "  Commands: exit / quit, clear" + RESET);
        System.out.println(           " Agentic Vulnerability Scanner " + RESET  );
    }

    public static void printToolStart(String name, Map<String, Object> input) {
        // Compact representation — truncate long string values
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : input.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append('"').append(e.getKey()).append("\": ");
            String val = String.valueOf(e.getValue());
            if (val.length() > 80) val = val.substring(0, 80) + "…";
            sb.append('"').append(val.replace("\"", "\\\"")).append('"');
        }
        sb.append("}");
        System.out.println(YELLOW + "\n  ⚙  " + BOLD + name + RESET + YELLOW + "  " + DIM + sb + RESET);
    }

    public static void printToolSuccess(String result) {
        System.out.println(GREEN + "  ✓" + RESET);
        if (!result.isBlank()) {
            String[] lines = result.split("\n");
            int limit = Math.min(lines.length, 8);
            for (int i = 0; i < limit; i++) {
                System.out.println(DIM + "    " + lines[i] + RESET);
            }
            if (lines.length > 8) {
                System.out.println(DIM + "    … (" + (lines.length - 8) + " more lines)" + RESET);
            }
        }
    }

    public static void printToolError(String message) {
        System.out.println(RED + "  ✗ " + message + RESET);
    }

    public static void printToolDenied() {
        System.out.println(RED + "  ✗ Denied by user" + RESET);
    }
}
