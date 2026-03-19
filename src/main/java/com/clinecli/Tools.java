package com.clinecli;

import com.anthropic.models.messages.Tool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class Tools {

    private static final Set<String> DESTRUCTIVE = Set.of(
            "write_file", "edit_file", "run_command", "delete_file"
    );

    /** Command prefixes/tokens that are never allowed, even with user approval. */
    private static final List<String> BLOCKED_COMMANDS = List.of(
            "curl ", "wget ", "nc ", "ncat ", "netcat ",
            "nmap ", "masscan ", "nikto ", "sqlmap ", "hydra ",
            "python -c ", "python3 -c ",   // common one-liner shell spawns
            "bash -i", "sh -i",            // interactive shells (often used for reverse shells)
            "exec /bin/", "exec /usr/bin/sh", "exec /usr/bin/bash",
            "/dev/tcp/", "/dev/udp/",      // bash TCP/UDP redirection
            "mkfifo ", "mknod "            // named pipes used in reverse shells
    );

    private Tools() {}

    public static boolean isDestructive(String name) {
        return DESTRUCTIVE.contains(name);
    }

    public static String approvalLabel(String name, Map<String, Object> input) {
        return switch (name) {
            case "write_file"  -> "Write to " + input.get("path") + "?";
            case "edit_file"   -> "Edit " + input.get("path") + "?";
            case "run_command" -> "Run: " + input.get("command") + "?";
            case "delete_file" -> "Delete " + input.get("path") + "?";
            default            -> "Allow " + name + "?";
        };
    }

    /** Provider-agnostic tool definitions used by all LLMProvider implementations. */
    public static List<ToolDefinition> getToolDefinitions() {
        return List.of(
            new ToolDefinition("read_file",
                "Read the contents of a file. Optionally specify a line range.",
                Map.of(
                    "path",       Map.of("type", "string", "description", "File path to read"),
                    "start_line", Map.of("type", "number", "description", "Optional: first line to read (1-indexed)"),
                    "end_line",   Map.of("type", "number", "description", "Optional: last line to read (1-indexed)")
                ),
                List.of("path")),

            new ToolDefinition("write_file",
                "Write content to a file, creating or overwriting it.",
                Map.of(
                    "path",    Map.of("type", "string", "description", "File path to write"),
                    "content", Map.of("type", "string", "description", "Full content to write")
                ),
                List.of("path", "content")),

            new ToolDefinition("edit_file",
                "Make a targeted edit to a file by replacing an exact string. Prefer this over write_file for small changes.",
                Map.of(
                    "path",       Map.of("type", "string", "description", "File path to edit"),
                    "old_string", Map.of("type", "string", "description", "Exact text to find and replace (must be unique)"),
                    "new_string", Map.of("type", "string", "description", "Text to replace it with")
                ),
                List.of("path", "old_string", "new_string")),

            new ToolDefinition("list_directory",
                "List files and directories at a given path.",
                Map.of(
                    "path", Map.of("type", "string", "description", "Directory path to list")
                ),
                List.of("path")),

            new ToolDefinition("search_files",
                "Search for a text pattern in files using grep.",
                Map.of(
                    "pattern",          Map.of("type", "string",  "description", "Text or regex pattern"),
                    "path",             Map.of("type", "string",  "description", "File or directory to search in"),
                    "file_pattern",     Map.of("type", "string",  "description", "Optional: glob to filter files, e.g. '*.java'"),
                    "case_insensitive", Map.of("type", "boolean", "description", "Optional: case-insensitive search")
                ),
                List.of("pattern", "path")),

            new ToolDefinition("run_command",
                "Execute a shell command and return its output.",
                Map.of(
                    "command",    Map.of("type", "string", "description", "Shell command to execute"),
                    "cwd",        Map.of("type", "string", "description", "Optional: working directory"),
                    "timeout_ms", Map.of("type", "number", "description", "Optional: timeout in milliseconds (default 30000)")
                ),
                List.of("command")),

            new ToolDefinition("delete_file",
                "Delete a file.",
                Map.of(
                    "path", Map.of("type", "string", "description", "Path to the file to delete")
                ),
                List.of("path"))
        );
    }

    /** All tool definitions sent to the API. */
    public static List<Tool> getTools() {
        return List.of(
            tool("read_file",
                "Read the contents of a file. Optionally specify a line range.",
                Map.of(
                    "path",       Map.of("type", "string", "description", "File path to read"),
                    "start_line", Map.of("type", "number", "description", "Optional: first line to read (1-indexed)"),
                    "end_line",   Map.of("type", "number", "description", "Optional: last line to read (1-indexed)")
                ),
                List.of("path")),

            tool("write_file",
                "Write content to a file, creating or overwriting it.",
                Map.of(
                    "path",    Map.of("type", "string", "description", "File path to write"),
                    "content", Map.of("type", "string", "description", "Full content to write")
                ),
                List.of("path", "content")),

            tool("edit_file",
                "Make a targeted edit to a file by replacing an exact string. Prefer this over write_file for small changes.",
                Map.of(
                    "path",       Map.of("type", "string", "description", "File path to edit"),
                    "old_string", Map.of("type", "string", "description", "Exact text to find and replace (must be unique)"),
                    "new_string", Map.of("type", "string", "description", "Text to replace it with")
                ),
                List.of("path", "old_string", "new_string")),

            tool("list_directory",
                "List files and directories at a given path.",
                Map.of(
                    "path", Map.of("type", "string", "description", "Directory path to list")
                ),
                List.of("path")),

            tool("search_files",
                "Search for a text pattern in files using grep.",
                Map.of(
                    "pattern",          Map.of("type", "string", "description", "Text or regex pattern"),
                    "path",             Map.of("type", "string", "description", "File or directory to search in"),
                    "file_pattern",     Map.of("type", "string", "description", "Optional: glob to filter files, e.g. '*.java'"),
                    "case_insensitive", Map.of("type", "boolean", "description", "Optional: case-insensitive search")
                ),
                List.of("pattern", "path")),

            tool("run_command",
                "Execute a shell command and return its output.",
                Map.of(
                    "command",    Map.of("type", "string", "description", "Shell command to execute"),
                    "cwd",        Map.of("type", "string", "description", "Optional: working directory"),
                    "timeout_ms", Map.of("type", "number", "description", "Optional: timeout in milliseconds (default 30000)")
                ),
                List.of("command")),

            tool("delete_file",
                "Delete a file.",
                Map.of(
                    "path", Map.of("type", "string", "description", "Path to the file to delete")
                ),
                List.of("path"))
        );
    }

    /** Execute a tool and return its string result. */
    public static String execute(String name, Map<String, Object> input) throws Exception {
        return switch (name) {
            case "read_file"      -> readFile(input);
            case "write_file"     -> writeFile(input);
            case "edit_file"      -> editFile(input);
            case "list_directory" -> listDirectory(input);
            case "search_files"   -> searchFiles(input);
            case "run_command"    -> runCommand(input);
            case "delete_file"    -> deleteFile(input);
            default               -> throw new IllegalArgumentException("Unknown tool: " + name);
        };
    }

    // ── Implementations ──────────────────────────────────────────────────────

    private static String readFile(Map<String, Object> input) throws Exception {
        Path path = Path.of((String) input.get("path"));
        List<String> allLines = Files.readAllLines(path);
        int total = allLines.size();

        int start = input.containsKey("start_line") ? toInt(input.get("start_line")) : 1;
        int end   = input.containsKey("end_line")   ? toInt(input.get("end_line"))   : total;

        start = Math.max(1, start);
        end   = Math.min(total, end);

        StringBuilder sb = new StringBuilder();
        sb.append("File: ").append(path).append(" (lines ").append(start).append("-").append(end)
          .append(" of ").append(total).append(")\n\n");

        for (int i = start - 1; i < end; i++) {
            sb.append(String.format("%4d: %s%n", i + 1, allLines.get(i)));
        }
        return sb.toString().stripTrailing();
    }

    private static String writeFile(Map<String, Object> input) throws Exception {
        Path path    = Path.of((String) input.get("path"));
        String content = (String) input.get("content");
        Files.createDirectories(path.getParent() != null ? path.getParent() : Path.of("."));
        Files.writeString(path, content);
        long lines = content.chars().filter(c -> c == '\n').count() + 1;
        return "Written " + lines + " lines to " + path;
    }

    private static String editFile(Map<String, Object> input) throws Exception {
        Path path      = Path.of((String) input.get("path"));
        String oldStr  = (String) input.get("old_string");
        String newStr  = (String) input.get("new_string");
        String content = Files.readString(path);

        int count = countOccurrences(content, oldStr);
        if (count == 0) throw new IllegalArgumentException("String not found in " + path);
        if (count > 1)  throw new IllegalArgumentException("String appears " + count + " times — be more specific");

        Files.writeString(path, content.replace(oldStr, newStr));
        return "Edited " + path + ": replaced 1 occurrence";
    }

    private static String listDirectory(Map<String, Object> input) throws Exception {
        Path dir = Path.of((String) input.get("path"));
        try (Stream<Path> entries = Files.list(dir)) {
            List<String> lines = new ArrayList<>();
            for (Path entry : entries.sorted().toList()) {
                boolean isDir = Files.isDirectory(entry);
                lines.add((isDir ? "📁 " : "📄 ") + entry.getFileName() + (isDir ? "/" : ""));
            }
            return "Contents of " + dir + "/ (" + lines.size() + " items):\n\n" + String.join("\n", lines);
        }
    }

    private static String searchFiles(Map<String, Object> input) throws Exception {
        String pattern  = (String) input.get("pattern");
        String path     = (String) input.get("path");
        Object fp       = input.get("file_pattern");
        Object ci       = input.get("case_insensitive");

        List<String> cmd = new ArrayList<>(List.of("grep", "-r", "-n", "--color=never"));
        if (Boolean.TRUE.equals(ci)) cmd.add("-i");
        if (fp != null) { cmd.add("--include=" + fp); }
        cmd.add(pattern);
        cmd.add(path);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process proc = pb.start();
        String stdout = new String(proc.getInputStream().readAllBytes());
        proc.waitFor(10, TimeUnit.SECONDS);

        if (stdout.isBlank()) return "No matches found";

        String[] lines = stdout.split("\n");
        if (lines.length > 50) {
            return String.join("\n", List.of(lines).subList(0, 50))
                    + "\n\n… (" + (lines.length - 50) + " more matches)";
        }
        return stdout.stripTrailing();
    }

    private static String runCommand(Map<String, Object> input) throws Exception {
        String command  = (String) input.get("command");
        String cwd      = input.containsKey("cwd") ? (String) input.get("cwd") : System.getProperty("user.dir");
        int timeoutMs   = input.containsKey("timeout_ms") ? toInt(input.get("timeout_ms")) : 30_000;

        // Tool-level guardrail: block prohibited commands regardless of user approval
        String commandLower = command.toLowerCase();
        for (String blocked : BLOCKED_COMMANDS) {
            if (commandLower.contains(blocked)) {
                throw new SecurityException(
                    "Command blocked by ethical policy: contains prohibited token \""
                    + blocked.strip() + "\". Network tools and shell spawning are not permitted.");
            }
        }

        ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
        pb.directory(Path.of(cwd).toFile());
        pb.redirectErrorStream(false);

        Process proc = pb.start();
        // Read both streams concurrently to avoid blocking
        String stdout, stderr;
        try (var outReader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
             var errReader = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
            stdout = outReader.lines().collect(Collectors.joining("\n"));
            stderr = errReader.lines().collect(Collectors.joining("\n"));
        }
        boolean finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            proc.destroyForcibly();
            return "Error: command timed out after " + timeoutMs + "ms";
        }

        List<String> parts = new ArrayList<>();
        if (!stdout.isBlank()) parts.add("stdout:\n" + stdout.stripTrailing());
        if (!stderr.isBlank()) parts.add("stderr:\n" + stderr.stripTrailing());
        return parts.isEmpty() ? "(no output)" : String.join("\n\n", parts);
    }

    private static String deleteFile(Map<String, Object> input) throws Exception {
        Path path = Path.of((String) input.get("path"));
        Files.delete(path);
        return "Deleted " + path;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Tool tool(String name, String desc, Map<String, Object> props, List<String> required) {
        var propsBuilder = Tool.InputSchema.Properties.builder();
        for (Map.Entry<String, Object> e : props.entrySet()) {
            propsBuilder.putAdditionalProperty(e.getKey(), com.anthropic.core.JsonValue.from(e.getValue()));
        }
        return Tool.builder()
                .name(name)
                .description(desc)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(propsBuilder.build())
                        .required(required)
                        .build())
                .build();
    }

    private static int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(o));
    }

    private static int countOccurrences(String text, String sub) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) { count++; idx += sub.length(); }
        return count;
    }
}
