# Chainflow

An agentic customer support email automation sample built with **Spring Integration** and **Spring AI**.

Chainflow polls an IMAP mailbox, classifies each incoming email using an AI model, resolves it according to a policy, drafts a reply, and sends it — all without human intervention.

## How It Works

```
IMAP Inbox
    │
    ▼
[wireTap: log received]
    │
    ▼
transform: jakarta.mail.Message → SupportTicketContext
    │
    ▼
[wireTap: log subject]
    │
    ▼
transform: classify()      ← AI agent — BILLING | DELIVERY | TECHNICAL | IGNORED
    │
    ▼
filter: drop IGNORED tickets → nullChannel
    │
    ▼
transform: resolve()       ← AI agent — determines action + policy reasoning
    │
    ▼
transform: draftReply()    ← AI agent — drafts polite reply email
    │
    ▼
transform: SupportTicketContext → SimpleMailMessage
    │
    ▼
SMTP outbound adapter (sends reply to customer)
```

Errors at any step are routed to `supportErrorChannel` and logged.

## AI Agents

Three AI agents are implemented in `SupportAgents`:

| Agent | Method | Output |
|---|---|---|
| Classifier | `classify(context)` | Sets `category` to `BILLING`, `DELIVERY`, `TECHNICAL`, or `IGNORED` |
| Resolver | `resolve(context)` | Sets `resolutionAction` and `policyReasoning` based on category |
| Drafter | `draftReply(context)` | Sets `draftedReplyBody` with a customer-facing reply |

All three agents use the Anthropic Claude model via Spring AI's `ChatClient`.

## Prerequisites

- Java 17+
- An Anthropic API key
- An IMAP mailbox (e.g. Gmail with App Passwords)
- An SMTP server for sending replies

## Configuration

Set the following properties in `application.properties` or as environment variables:

```properties
# IMAP (incoming mail)
org.springframework.samples.chainflow.imap.host=imap.example.com
org.springframework.samples.chainflow.imap.username=support@example.com
org.springframework.samples.chainflow.imap.folder=INBOX

# SMTP (outgoing mail)
org.springframework.samples.chainflow.smtp.host=smtp.example.com
org.springframework.samples.chainflow.smtp.username=support@example.com

# Shared password used for both IMAP and SMTP
org.springframework.samples.chainflow.password=secret

# Anthropic API key (consumed by Spring AI autoconfiguration)
spring.ai.anthropic.api-key=sk-ant-...
```

The AI prompts are also configurable and defined in `application.properties` under:

```
org.springframework.integration.samples.chainflow.classify.prompt
org.springframework.integration.samples.chainflow.resolve.prompt
org.springframework.integration.samples.chainflow.draft.prompt
```

## Running

```bash
./gradlew bootRun
```

The application polls the configured IMAP folder every 20 seconds, processing up to 10 messages per poll.

## Project Structure

```
src/main/java/.../chainflow/
├── ChainflowApplication.java     # Spring Boot entry point
├── SupportFlowConfig.java        # Spring Integration flow definition
├── SupportAgents.java            # AI agents (classify, resolve, draftReply)
├── SupportTicketContext.java     # Message payload passed through the flow
├── ClassifierResult.java         # AI output record for the classifier agent
└── ResolverResult.java           # AI output record for the resolver agent

src/test/java/.../chainflow/
├── SupportAgentsTest.java        # Unit tests for AI agents (mocked ChatModel)
├── SupportFlowConfigTest.java    # Spring context + agent pipeline tests
└── SupportFlowConfigFlowTest.java # End-to-end flow tests using MockIntegrationContext
```

## Testing

```bash
./gradlew test
```

Tests use a mocked `ChatModel` so no real AI provider is called. The integration flow tests use Spring Integration's `MockIntegrationContext` to substitute the IMAP source and SMTP outbound adapter, allowing the full pipeline to run without any mail server.

| Test Class | Description |
|---|---|
| `SupportAgentsTest` | Unit tests for each agent method with stubbed AI responses |
| `SupportFlowConfigTest` | Spring Boot context loads; agent beans wired correctly |
| `SupportFlowConfigFlowTest` | End-to-end flow: mock IMAP → classify → resolve → draft → captured `MailMessage` |

## Technologies

- [Spring Boot 4.1](https://spring.io/projects/spring-boot)
- [Spring Integration](https://spring.io/projects/spring-integration) — mail adapters, DSL flow definition
- [Spring AI](https://spring.io/projects/spring-ai) — `ChatClient`, Anthropic model integration
- [Spring Integration Test](https://docs.spring.io/spring-integration/docs/current/reference/html/testing.html) — `MockIntegrationContext`, `MockIntegration`
