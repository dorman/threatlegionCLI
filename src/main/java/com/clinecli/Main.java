package com.clinecli;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public class Main {

    /** Holds everything needed to construct an LLMProvider once scope is known. */
    private record ProviderSetup(String providerKey, String displayName,
                                  String baseUrl, String model, String apiKey) {}

    public static void main(String[] args) throws Exception {
        Terminal terminal     = TerminalBuilder.builder().system(true).build();
        LineReader lineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .option(LineReader.Option.HISTORY_BEEP, false)
                .build();

        UI.printWelcome();

        // Step 1: Choose provider, model, API key
        ProviderSetup setup = chooseProvider(lineReader);
        if (setup == null) return;

        // Step 2: Confirm authorized scope (ethics layer 5)
        String scope = confirmScope(lineReader);
        if (scope == null) return;

        // Step 3: Build system prompt + provider
        String systemPrompt = Agent.buildSystemPrompt(scope);
        LLMProvider provider = buildProvider(setup, systemPrompt);

        System.out.println(UI.GREEN + "  ✓ Using " + setup.displayName() + " / " + setup.model() + UI.RESET + "\n");

        // Step 4: Run the REPL
        Agent agent = new Agent(provider, lineReader);

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
                    agent.clearHistory();
                    System.out.println(UI.DIM + "  Conversation cleared." + UI.RESET);
                    continue;
                }
                case "history" -> {
                    System.out.println(UI.DIM + "  " + agent.historySize()
                            + " messages in history." + UI.RESET);
                    continue;
                }
            }

            try {
                agent.runTurn(trimmed);
            } catch (Exception e) {
                System.err.println(UI.RED + "\n  Error: " + e.getMessage() + UI.RESET);
            }
        }
    }

    // ── Provider selection ────────────────────────────────────────────────────

    private static final String[][] BUILT_IN_PROVIDERS = {
        // { display name, internal key, base URL (empty = Anthropic SDK), default model }
        { "Anthropic (Claude)", "anthropic",  "",                                 "claude-opus-4-6"           },
        { "OpenAI",             "openai",     "https://api.openai.com/v1",        "gpt-4o"                    },
        { "OpenRouter",         "openrouter", "https://openrouter.ai/api/v1",     "anthropic/claude-opus-4-6" },
        { "Custom (any OpenAI-compatible endpoint)", "custom", "", ""             },
    };

    private static ProviderSetup chooseProvider(LineReader lr) throws Exception {
        String lastProvider = Config.loadLastProvider();

        System.out.println(UI.BOLD + "\n  Select a provider:" + UI.RESET);
        for (int i = 0; i < BUILT_IN_PROVIDERS.length; i++) {
            boolean isLast = BUILT_IN_PROVIDERS[i][1].equals(lastProvider);
            System.out.println(UI.DIM + "  [" + (i + 1) + "] " + BUILT_IN_PROVIDERS[i][0]
                    + (isLast ? UI.GREEN + "  ←" + UI.RESET + UI.DIM : "") + UI.RESET);
        }

        // Default to last-used provider index, or 1
        String defaultChoice = "1";
        for (int i = 0; i < BUILT_IN_PROVIDERS.length; i++) {
            if (BUILT_IN_PROVIDERS[i][1].equals(lastProvider)) {
                defaultChoice = String.valueOf(i + 1);
                break;
            }
        }

        String raw = prompt(lr, "\n  Choice [" + defaultChoice + "]: ", defaultChoice);
        int choice;
        try { choice = Integer.parseInt(raw) - 1; } catch (NumberFormatException e) { choice = 0; }
        if (choice < 0 || choice >= BUILT_IN_PROVIDERS.length) choice = 0;

        String[] p       = BUILT_IN_PROVIDERS[choice];
        String pKey      = p[1];
        String pName     = p[0];
        String pUrl      = p[2];
        String pDefault  = p[3];

        // Custom provider: ask for URL and display name
        if ("custom".equals(pKey)) {
            pUrl  = prompt(lr, "  Base URL (e.g. http://localhost:11434/v1): ", "");
            pName = prompt(lr, "  Provider name: ", "Custom");
        }

        // Model: show saved value, allow override
        String savedModel = Config.loadModel(pKey, pDefault);
        String model = prompt(lr, "  Model [" + savedModel + "]: ", savedModel);
        if (model.isBlank()) model = savedModel;
        Config.saveModel(pKey, model);

        // API key: load saved, prompt if missing
        String apiKey = Config.loadProviderKey(pKey);
        if (apiKey == null) {
            System.out.println(UI.YELLOW + "\n  No API key found for " + pName + "." + UI.RESET);
            apiKey = prompt(lr, "  Enter API key: ", "");
            if (apiKey.isBlank()) {
                System.err.println(UI.RED + "  No key entered. Exiting." + UI.RESET);
                return null;
            }
            Config.saveProviderKey(pKey, apiKey);
            System.out.println(UI.GREEN + "  Key saved to ~/.cline/config.json" + UI.RESET);
        }

        Config.saveLastProvider(pKey);
        return new ProviderSetup(pKey, pName, pUrl, model, apiKey);
    }

    // ── Provider factory ──────────────────────────────────────────────────────

    private static LLMProvider buildProvider(ProviderSetup s, String systemPrompt) {
        if ("anthropic".equals(s.providerKey())) {
            AnthropicClient client = AnthropicOkHttpClient.builder()
                    .apiKey(s.apiKey())
                    .build();
            return new AnthropicProvider(client, s.model(), systemPrompt);
        } else {
            return new OpenAICompatibleProvider(
                    s.displayName(), s.baseUrl(), s.apiKey(), s.model(), systemPrompt);
        }
    }

    // ── Scope confirmation ────────────────────────────────────────────────────

    private static String confirmScope(LineReader lr) {
        System.out.println(UI.YELLOW + UI.BOLD + "\n  SCOPE CONFIRMATION" + UI.RESET);
        System.out.println(UI.DIM
                + "  ThreatLegion only operates on codebases you are authorized to assess.\n"
                + "  Describe the scope for this session.\n"
                + "  Example: /Users/alice/projects/myapp — my repo, authorized for security review\n"
                + UI.RESET);

        String scope = prompt(lr, "  Authorized scope: ", "");
        if (scope.isBlank()) {
            scope = "Current directory: " + System.getProperty("user.dir");
            System.out.println(UI.DIM + "  No scope entered — restricting to current directory." + UI.RESET);
        }

        System.out.println(UI.GREEN + "  ✓ Scope locked: " + scope + UI.RESET);
        System.out.println(UI.DIM   + "  All activity this session is restricted to this scope." + UI.RESET);
        return scope;
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static String prompt(LineReader lr, String message, String defaultValue) {
        try {
            String val = lr.readLine(UI.DIM + message + UI.RESET).trim();
            return val.isBlank() ? defaultValue : val;
        } catch (UserInterruptException | EndOfFileException e) {
            return defaultValue;
        }
    }
}
