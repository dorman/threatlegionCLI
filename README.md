# ThreatLegion

**Agentic Vulnerability Scanner** — a terminal-based AI agent powered by Claude Opus 4.6 that autonomously reads, searches, edits, and executes code to help identify and remediate security vulnerabilities.

---

## Requirements

- Java 21+
- Gradle 8+
- An [Anthropic API key](https://console.anthropic.com/)

---

## Setup & Running

**1. Clone the repo and navigate to the project:**
```bash
git clone <repo-url>
cd ClineCLI-Java
```

**2. Run:**
```bash
gradle run
```

On first launch, ThreatLegion will prompt you for your Anthropic API key and save it to `~/.cline/config.json` — you won't be asked again.

---

## API Key

ThreatLegion looks for your key in this order:

| Priority | Source |
|---|---|
| 1 | `ANTHROPIC_API_KEY` environment variable |
| 2 | `~/.cline/config.json` (saved on first run) |
| 3 | Interactive prompt (saved for next time) |

To override the stored key, set the environment variable:
```bash
export ANTHROPIC_API_KEY=sk-ant-...
gradle run
```

To reset the stored key, edit or delete `~/.cline/config.json`.

---

## Commands

| Command | Description |
|---|---|
| `exit` / `quit` | Exit the agent |
| `clear` | Clear conversation history |
| `history` | Show number of messages in context |

---

## Tools

ThreatLegion has access to the following tools. Destructive operations require your explicit **y/N approval** before executing.

| Tool | Safe / Requires Approval | Description |
|---|---|---|
| `read_file` | Safe | Read file contents with optional line range |
| `list_directory` | Safe | List files and directories |
| `search_files` | Safe | Grep for patterns across a codebase |
| `write_file` | Requires approval | Create or overwrite a file |
| `edit_file` | Requires approval | Targeted string replacement in a file |
| `run_command` | Requires approval | Execute a shell command |
| `delete_file` | Requires approval | Delete a file |

---

## Architecture

```
src/main/java/com/clinecli/
├── Main.java          Entry point — JLine REPL, API key loading
├── Agent.java         Streaming agentic loop with tool execution
├── Tools.java         Tool definitions and implementations
├── Config.java        API key persistence (~/.cline/config.json)
└── UI.java            ANSI terminal formatting
```

**Flow:**
1. User enters a task
2. Claude streams a response, optionally calling tools
3. Safe tools execute automatically; destructive tools require approval
4. Tool results are fed back to Claude
5. Loop continues until Claude finishes

---

## Building a Fat Jar

To build a standalone jar with all dependencies bundled:

```bash
gradle jar
java -jar build/libs/cline-cli-1.0.0.jar
```

---

## Tech Stack

| Component | Library |
|---|---|
| AI Model | Claude Opus 4.6 (Anthropic) |
| Anthropic SDK | `com.anthropic:anthropic-java:2.15.0` |
| Terminal input | `org.jline:jline:3.26.3` |
| JSON | `com.fasterxml.jackson.core:jackson-databind:2.17.2` |
| Build | Gradle with Kotlin DSL |
| Language | Java 21 |
