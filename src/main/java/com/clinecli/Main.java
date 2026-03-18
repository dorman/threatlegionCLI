package com.clinecli;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageParam;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.util.ArrayList;
import java.util.List;

public class Main {

    public static void main(String[] args) throws Exception {
        Terminal terminal     = TerminalBuilder.builder().system(true).build();
        LineReader lineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .option(LineReader.Option.HISTORY_BEEP, false)
                .build();

        // Load API key — prompt and save if not found
        String apiKey = Config.loadApiKey();
        if (apiKey == null) {
            System.out.println(UI.YELLOW + "\n  No Anthropic API key found." + UI.RESET);
            System.out.println(UI.DIM + "  Your key will be saved to ~/.cline/config.json\n" + UI.RESET);
            apiKey = lineReader.readLine(UI.BOLD + "  Enter your Anthropic API key: " + UI.RESET).trim();
            if (apiKey.isBlank()) {
                System.err.println(UI.RED + "  No key entered. Exiting." + UI.RESET);
                System.exit(1);
            }
            Config.saveApiKey(apiKey);
            System.out.println(UI.GREEN + "  Key saved to ~/.cline/config.json\n" + UI.RESET);
        }

        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();

        UI.printWelcome();

        // ── Scope confirmation (Layer 5) ──────────────────────────────────────
        System.out.println(UI.YELLOW + UI.BOLD + "\n  SCOPE CONFIRMATION" + UI.RESET);
        System.out.println(UI.DIM + "  ThreatLegion only operates on codebases you are authorized to assess.");
        System.out.println("  Please describe the authorized scope for this session.");
        System.out.println("  Example: /Users/alice/projects/myapp — owned by me, authorized for security review" + UI.RESET);
        System.out.println();

        String authorizedScope;
        try {
            authorizedScope = lineReader.readLine(UI.BOLD + "  Authorized scope: " + UI.RESET).trim();
        } catch (org.jline.reader.UserInterruptException | org.jline.reader.EndOfFileException e) {
            System.out.println(UI.YELLOW + "\n  Goodbye!" + UI.RESET);
            return;
        }

        if (authorizedScope.isBlank()) {
            System.out.println(UI.DIM + "  No scope entered — restricting to current directory." + UI.RESET);
            authorizedScope = "Current directory: " + System.getProperty("user.dir");
        }

        System.out.println(UI.GREEN + "  ✓ Scope locked: " + authorizedScope + UI.RESET);
        System.out.println(UI.DIM + "  All activity this session is restricted to this scope." + UI.RESET);
        System.out.println();

        List<MessageParam> messages = new ArrayList<>();
        Agent agent = new Agent(client, messages, lineReader, authorizedScope);

        //noinspection InfiniteLoopStatement
        while (true) {
            String input;
            try {
                input = lineReader.readLine(UI.BOLD + UI.CYAN + "\n❯ " + UI.RESET);
            } catch (UserInterruptException | EndOfFileException e) {
                System.out.println(UI.YELLOW + "\n  Goodbye!" + UI.RESET);
                break;
            }

            if (input == null) break;
            String trimmed = input.trim();
            if (trimmed.isEmpty()) continue;

            switch (trimmed.toLowerCase()) {
                case "exit", "quit" -> {
                    System.out.println(UI.YELLOW + "  Goodbye!" + UI.RESET);
                    return;
                }
                case "clear" -> {
                    messages.clear();
                    System.out.println(UI.DIM + "  Conversation cleared." + UI.RESET);
                    continue;
                }
                case "history" -> {
                    System.out.println(UI.DIM + "  " + messages.size() + " messages in history." + UI.RESET);
                    continue;
                }
            }

            // Add user message to conversation history
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(trimmed)
                    .build());

            try {
                agent.runTurn();
            } catch (Exception e) {
                System.err.println(UI.RED + "\n  Error: " + e.getMessage() + UI.RESET);
                // Roll back the user message so the conversation stays consistent
                if (!messages.isEmpty()) messages.removeLast();
            }
        }
    }
}
