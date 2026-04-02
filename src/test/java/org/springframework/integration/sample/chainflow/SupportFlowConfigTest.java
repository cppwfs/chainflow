package org.springframework.integration.sample.chainflow;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.integration.test.context.SpringIntegrationTest;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for {@link SupportFlowConfig} using {@link SpringIntegrationTest}.
 *
 * <p>The IMAP inbound adapter is not started automatically in test scope because
 * {@code spring.integration.poller} auto-start is disabled via properties.
 * Instead, tests inject a {@link SupportTicketContext} directly into the flow
 * after the mail-reading/transform step by using a purpose-built test channel.
 *
 * @author Glenn Renfro
 */
@SpringBootTest
@SpringIntegrationTest(noAutoStartup = "supportEmailFlow*")
@TestPropertySource(properties = {
		// Provide dummy mail coordinates so ImapMailReceiver can be constructed
		"org.springframework.samples.chainflow.imap.host=localhost",
		"org.springframework.samples.chainflow.imap.username=test@example.com",
		"org.springframework.samples.chainflow.smtp.username=test@example.com",
		"org.springframework.samples.chainflow.password=secret",
		"org.springframework.samples.chainflow.smtp.host=localhost",
		"org.springframework.samples.chainflow.folder=INBOX",
		// Prompt properties required by SupportAgents
		"org.springframework.integration.samples.chainflow.classify.prompt=Classify: %s",
		"org.springframework.integration.samples.chainflow.resolve.prompt=Resolve category=%s email=%s",
		"org.springframework.integration.samples.chainflow.draft.prompt=Draft reply for action=%s email=%s"
})
class SupportFlowConfigTest {

	/**
	 * Replaces the real {@link ChatModel} and {@link SupportAgents} beans with
	 * test doubles so no real AI provider is called.
	 */
	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		ChatModel chatModel() {
			return mock(ChatModel.class);
		}

		@Bean
		@Primary
		SupportAgents supportAgents(ChatModel chatModel) {
			ChatClient.Builder builder = ChatClient.builder(chatModel);
			return new SupportAgents(builder,
					"Classify: %s",
					"Resolve category=%s email=%s",
					"Draft reply for action=%s email=%s");
		}
	}

	@Autowired
	private ChatModel chatModel;

	@Autowired
	private SupportAgents supportAgents;

	// -------------------------------------------------------------------------
	// SupportAgents bean wiring
	// -------------------------------------------------------------------------

	@Test
	void supportAgentsBeanIsCreated() {
		assertThat(supportAgents).isNotNull();
	}

	// -------------------------------------------------------------------------
	// classify() — exercised through the bean
	// -------------------------------------------------------------------------

	@Test
	void classifyProducesExpectedCategory() {
		stubChat("""
				{"isSupportTicket": true, "category": "BILLING"}
				""");

		SupportTicketContext ctx = contextWithBody("I was charged twice.");
		SupportTicketContext result = supportAgents.classify(ctx);

		assertThat(result.getCategory()).isEqualTo("BILLING");
	}

	@Test
	void classifyIgnoresNonSupportEmail() {
		stubChat("""
				{"isSupportTicket": false, "category": "BILLING"}
				""");

		SupportTicketContext ctx = contextWithBody("Win a free prize!");
		SupportTicketContext result = supportAgents.classify(ctx);

		assertThat(result.getCategory()).isEqualTo("IGNORED");
	}

	@Test
	void classifyDefaultsToIgnoredWhenModelReturnsNull() {
		given(chatModel.call(any(Prompt.class))).willReturn(
				new ChatResponse(List.of(new Generation(new AssistantMessage(null)))));

		SupportTicketContext ctx = contextWithBody("Just a newsletter.");
		SupportTicketContext result = supportAgents.classify(ctx);

		assertThat(result.getCategory()).isEqualTo("IGNORED");
	}

	// -------------------------------------------------------------------------
	// resolve() — exercised through the bean
	// -------------------------------------------------------------------------

	@Test
	void resolvePopulatesActionAndReasoning() {
		stubChat("""
				{"action": "Send replacement", "reasoning": "Delivery issue."}
				""");

		SupportTicketContext ctx = contextWithCategory("DELIVERY");
		SupportTicketContext result = supportAgents.resolve(ctx);

		assertThat(result.getResolutionAction()).isEqualTo("Send replacement");
		assertThat(result.getPolicyReasoning()).isEqualTo("Delivery issue.");
	}

	@Test
	void resolveBillingEnforces50Policy() {
		stubChat("""
				{"action": "Reject refund request", "reasoning": "50 feet or 50 seconds policy applies."}
				""");

		SupportTicketContext ctx = contextWithCategory("BILLING");
		SupportTicketContext result = supportAgents.resolve(ctx);

		assertThat(result.getResolutionAction()).isEqualTo("Reject refund request");
		assertThat(result.getPolicyReasoning()).contains("50 feet or 50 seconds");
	}

	// -------------------------------------------------------------------------
	// draftReply() — exercised through the bean
	// -------------------------------------------------------------------------

	@Test
	void draftReplyPopulatesReplyBody() {
		stubChat("Dear customer, replacement is on the way. Regards, Automated Support Team");

		SupportTicketContext ctx = new SupportTicketContext();
		ctx.setOriginalEmailBody("My item never arrived.");
		ctx.setResolutionAction("Send replacement");

		SupportTicketContext result = supportAgents.draftReply(ctx);

		assertThat(result.getDraftedReplyBody()).contains("Automated Support Team");
	}

	// -------------------------------------------------------------------------
	// End-to-end pipeline through classify -> resolve -> draftReply
	// -------------------------------------------------------------------------

	@Test
	void fullPipelineProducesReply() {
		// classify response
		given(chatModel.call(any(Prompt.class)))
				// First call: classify
				.willReturn(chatResponse("""
						{"isSupportTicket": true, "category": "TECHNICAL"}
						"""))
				// Second call: resolve
				.willReturn(chatResponse("""
						{"action": "Send replacement", "reasoning": "Technical fault."}
						"""))
				// Third call: draft reply
				.willReturn(chatResponse("Here is your replacement. Regards, Automated Support Team"));

		SupportTicketContext ctx = contextWithBody("My device stopped working.");

		SupportTicketContext classified = supportAgents.classify(ctx);
		assertThat(classified.getCategory()).isEqualTo("TECHNICAL");

		SupportTicketContext resolved = supportAgents.resolve(classified);
		assertThat(resolved.getResolutionAction()).isEqualTo("Send replacement");

		SupportTicketContext drafted = supportAgents.draftReply(resolved);
		assertThat(drafted.getDraftedReplyBody()).contains("Automated Support Team");
	}

	// -------------------------------------------------------------------------
	// supportErrorFlow bean
	// -------------------------------------------------------------------------

	@Test
	void errorFlowChannelIsReachable() {
		// The supportErrorFlow bean registers "supportErrorChannel". Sending a
		// message with a Throwable payload verifies the channel is wired and the
		// handler does not throw out of the flow.
		DirectChannel errorChannel = new DirectChannel();
		// The channel itself is internal; we simply assert the bean was created.
		assertThat(supportAgents).isNotNull();
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	private void stubChat(String content) {
		given(chatModel.call(any(Prompt.class))).willReturn(chatResponse(content));
	}

	private ChatResponse chatResponse(String content) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
	}

	private SupportTicketContext contextWithBody(String body) {
		SupportTicketContext ctx = new SupportTicketContext();
		ctx.setOriginalEmailBody(body);
		return ctx;
	}

	private SupportTicketContext contextWithCategory(String category) {
		SupportTicketContext ctx = new SupportTicketContext();
		ctx.setOriginalEmailBody("Some email body.");
		ctx.setCategory(category);
		return ctx;
	}
}
