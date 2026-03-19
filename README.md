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

## Supported Providers

ThreatLegion works with Anthropic, OpenAI, OpenRouter, and any OpenAI-compatible API endpoint.

| # | Provider | Notes |
|---|---|---|
| 1 | **Anthropic (Claude)** | Default. Uses the official Anthropic Java SDK. |
| 2 | **OpenAI** | GPT-4o, o1, etc. via `https://api.openai.com/v1` |
| 3 | **OpenRouter** | Access 200+ models with one API key via `https://openrouter.ai/api/v1` |
| 4 | **Custom** | Any OpenAI-compatible endpoint (Ollama, Together, Groq, local LLMs, etc.) |

On startup you choose a provider, enter the model name, and provide your API key. Keys are saved per-provider to `~/.cline/config.json` so you're only asked once per provider. The last-used provider is remembered and selected by default next time.

**Environment variable overrides** (checked before the config file):

| Provider | Environment variable |
|---|---|
| Anthropic | `ANTHROPIC_API_KEY` |
| OpenAI | `OPENAI_API_KEY` |
| OpenRouter | `OPENROUTER_API_KEY` |
| Custom | `CUSTOM_API_KEY` |

---

## Session Start

Every session follows four setup steps before the agent loop opens:

**1. Provider selection** — choose your LLM backend and model.

**2. API key check** — loads from environment or config file, or prompts you to enter one.

**3. Scope confirmation** — you declare which codebase or directory is authorized for this session:

```
  SCOPE CONFIRMATION
  ThreatLegion only operates on codebases you are authorized to assess.
  Please describe the authorized scope for this session.
  Example: /Users/alice/projects/myapp — owned by me, authorized for security review

  Authorized scope: /Users/alice/projects/myapp
  ✓ Scope locked: /Users/alice/projects/myapp
```

The scope is injected into the system prompt and governs all agent behavior for the session. If you press Enter without entering a scope, ThreatLegion restricts itself to the current working directory.

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

## Ethical Constraint System

ThreatLegion enforces a five-layer defense-in-depth ethics model to ensure it operates safely and within authorized boundaries at all times.

### Layer 1 — Hardcoded System Prompt Rules

The agent's system prompt includes eight non-negotiable constraints that Claude Opus 4.6 is instructed to follow regardless of user input:

1. No exploit or attack payload development
2. No network attacks, port scans, or denial-of-service testing
3. No credential extraction or exfiltration
4. No sending data to external servers
5. No malware, reverse shells, or backdoors
6. No operating outside the authorized scope
7. No persistence mechanisms or startup modifications
8. No privilege escalation

If a request would violate any constraint, the agent refuses and explains why.

### Layer 2 — Policy File (`POLICY.md`)

A `POLICY.md` file in the project root defines the full ethical policy in human-readable form, covering permitted activities, prohibited activities, and guiding principles. This file is loaded at startup and injected verbatim into the system prompt so the model has explicit written policy to reference.

ThreatLegion looks for the policy file in:
1. `<working directory>/POLICY.md`
2. `~/.cline/POLICY.md`

You can customize `POLICY.md` to add organization-specific rules. If no file is found, the hardcoded system prompt rules still apply.

### Layer 3 — Pre-flight Input Validation

Before any user message is sent to Claude, ThreatLegion scans it for prohibited patterns. Blocked phrases include:

```
exploit, payload, reverse shell, bind shell, backdoor, keylogger,
ransomware, exfiltrate, c2, command and control, denial of service,
dos attack, ddos, port scan all, upload to, send to server,
post to http, curl http, wget http
```

If a match is found, the request is rejected immediately with a clear message — Claude never sees it, and the message is removed from conversation history to keep the context clean.

### Layer 4 — Tool-Level Command Guardrails

The `run_command` tool maintains a hardcoded blocklist of prohibited commands that are **never allowed**, even if the user approves the action at the y/N prompt:

```
curl, wget, nc, ncat, netcat, nmap, masscan, nikto, sqlmap, hydra,
python -c, python3 -c, bash -i, sh -i, exec /bin/,
/dev/tcp/, /dev/udp/, mkfifo, mknod
```

These throw a `SecurityException` at the tool level, providing a hard stop independent of the model's judgment.

### Layer 5 — Scope Confirmation at Session Start

Before the agent loop opens, the user is required to explicitly declare the authorized scope for the session (see [Session Start](#session-start)). This scope string is:

- Displayed and confirmed in the terminal
- Injected into the system prompt for the entire session
- Referenced by the model when deciding whether an operation is in bounds

This creates a documented authorization boundary that the model actively enforces.

---

## Architecture

```
src/main/java/com/clinecli/
├── Main.java                    Entry point — provider selection, scope confirmation, JLine REPL
├── Agent.java                   Agentic loop orchestrator, ethics layers 1–3, policy loading
├── LLMProvider.java             Stateful provider interface (stream, addUserMessage, addToolResults…)
├── AnthropicProvider.java       Anthropic SDK streaming implementation (thinking-block aware)
├── OpenAICompatibleProvider.java  OkHttp SSE implementation for OpenAI / OpenRouter / custom
├── Tools.java                   Tool definitions, implementations, layer 4 command guardrails
├── Config.java                  Per-provider API key + model persistence (~/.cline/config.json)
├── StreamResult.java            Result returned by LLMProvider.stream()
├── ToolDefinition.java          Provider-agnostic tool schema
├── ToolCall.java                Tool call (id, name, inputJson)
├── ToolResult.java              Tool execution result (toolUseId, content)
├── GenericMessage.java          (reserved for future use)
└── UI.java                      ANSI terminal formatting

POLICY.md                        Human-readable ethical policy (layer 2), loaded at runtime
```

**Flow:**
1. API key is loaded or prompted
2. Welcome banner is displayed
3. User confirms the authorized scope for this session
4. User enters a task
5. Pre-flight check scans the request for prohibited patterns (layer 3)
6. Claude streams a response with adaptive thinking, optionally calling tools
7. Safe tools execute automatically; destructive tools require y/N approval
8. Blocked commands are rejected at the tool level regardless of approval (layer 4)
9. Tool results are fed back to Claude
10. Loop continues until Claude finishes

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
| Anthropic provider | `com.anthropic:anthropic-java:2.15.0` (with adaptive thinking) |
| OpenAI-compatible providers | `com.squareup.okhttp3:okhttp:4.12.0` (SSE streaming) |
| Terminal input | `org.jline:jline:3.26.3` |
| JSON | `com.fasterxml.jackson.core:jackson-databind:2.17.2` |
| Build | Gradle with Kotlin DSL |
| Language | Java 21 |
