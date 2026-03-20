package com.clinecli;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;

import java.io.InputStream;
import java.io.PrintWriter;
import java.util.List;

/**
 * Arrow-key navigable menu using JLine's native terminal APIs.
 */
public final class InteractiveMenu {

    private InteractiveMenu() {}

    /**
     * Display an interactive menu and return the selected index.
     * Returns -1 if the user presses Ctrl-C or Ctrl-D.
     */
    public static int select(Terminal terminal, String title, List<String> items, int defaultIndex) {
        PrintWriter out = terminal.writer();
        int selected = Math.max(0, Math.min(defaultIndex, items.size() - 1));

        if (title != null && !title.isBlank()) {
            out.print(UI.BOLD + "\n  " + title + "\n" + UI.RESET);
        }
        render(out, items, selected, false);
        out.flush();

        // Save current attributes and enter raw mode via JLine
        Attributes saved = terminal.enterRawMode();

        try {
            InputStream in = terminal.input();
            while (true) {
                int ch = in.read();

                if (ch == -1 || ch == 3 || ch == 4) {   // EOF / Ctrl-C / Ctrl-D
                    out.print("\r\n");
                    out.flush();
                    return -1;
                }

                if (ch == '\r' || ch == '\n') {
                    out.print("\r\n");
                    out.flush();
                    return selected;
                }

                if (ch == 27) {                          // ESC — arrow key prefix
                    int ch2 = in.read();
                    if (ch2 == '[' || ch2 == 'O') {      // CSI or SS3
                        int ch3 = in.read();
                        if (ch3 == 'A') {                // Up arrow
                            selected = (selected - 1 + items.size()) % items.size();
                            render(out, items, selected, true);
                            out.flush();
                        } else if (ch3 == 'B') {         // Down arrow
                            selected = (selected + 1) % items.size();
                            render(out, items, selected, true);
                            out.flush();
                        }
                    }
                }
                // All other characters are ignored
            }
        } catch (Exception e) {
            return selected;
        } finally {
            terminal.setAttributes(saved);
        }
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private static void render(PrintWriter out, List<String> items, int selected, boolean redraw) {
        if (redraw) {
            for (int i = 0; i < items.size(); i++) {
                out.print("\033[1A");   // cursor up one line
            }
        }
        for (int i = 0; i < items.size(); i++) {
            out.print("\r\033[2K");    // carriage return, erase line
            if (i == selected) {
                out.print(UI.BOLD + UI.CYAN + "  ▶  " + items.get(i) + UI.RESET);
            } else {
                out.print(UI.DIM  + "     " + items.get(i) + UI.RESET);
            }
            out.print("\r\n");
        }
    }
}
