package org.springframework.integration.sample.chainflow;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * Unit tests for {@link SupportAgents} using a mocked {@link ChatModel}.
 *
 * <p>Following Spring AI testing recommendations, a {@link ChatModel} mock is injected
 * into a {@link ChatClient.Builder} so that no real AI provider is called during tests.
 *
 * @author Glenn Renfro
 */
@ExtendWith(MockitoExtension.class)
class SupportAgentsTest {

	@Mock
	private ChatModel chatModel;

	private SupportAgents supportAgents;

	@BeforeEach
	void setUp() {
		ChatClient.Builder builder = ChatClient.builder(chatModel);
		this.supportAgents = new SupportAgents(builder,
				"Classify: %s",
				"Resolve category=%s email=%s",
				"Draft reply for action=%s email=%s");
	}

	// --- classify() ---

	@Test
	void classifySetsBillingCategory() {
		stubChatModel("""
				{"isSupportTicket": true, "category": "BILLING"}
				""");

		SupportTicketContext context = contextWithBody("I was charged twice this month.");
		SupportTicketContext result = supportAgents.classify(context);

		assertThat(result.getCategory()).isEqualTo("BILLING");
	}

	@Test
	void classifySetsIgnoredWhenNotSupportTicket() {
		stubChatModel("""
				{"isSupportTicket": false, "category": "BILLING"}
				""");

		SupportTicketContext context = contextWithBody("Win a free vacation!");
		SupportTicketContext result = supportAgents.classify(context);

		assertThat(result.getCategory()).isEqualTo("IGNORED");
	}

	@Test
	void classifySetsIgnoredWhenResultIsNull() {
		// BeanOutputConverter returns null when content is null
		given(chatModel.call(any(Prompt.class))).willReturn(
				new ChatResponse(List.of(new Generation(new AssistantMessage(null)))));

		SupportTicketContext context = contextWithBody("Newsletter content");
		SupportTicketContext result = supportAgents.classify(context);

		assertThat(result.getCategory()).isEqualTo("IGNORED");
	}

	// --- resolve() ---

	@Test
	void resolveSetsActionAndReasoning() {
		stubChatModel("""
				{"action": "Send replacement", "reasoning": "Delivery issue warrants replacement."}
				""");

		SupportTicketContext context = contextWithCategory("DELIVERY");
		SupportTicketContext result = supportAgents.resolve(context);

		assertThat(result.getResolutionAction()).isEqualTo("Send replacement");
		assertThat(result.getPolicyReasoning()).isEqualTo("Delivery issue warrants replacement.");
	}

	@Test
	void resolveBillingRejectsRefund() {
		stubChatModel("""
				{"action": "Reject refund request", "reasoning": "50 feet or 50 seconds policy applies."}
				""");

		SupportTicketContext context = contextWithCategory("BILLING");
		SupportTicketContext result = supportAgents.resolve(context);

		assertThat(result.getResolutionAction()).isEqualTo("Reject refund request");
		assertThat(result.getPolicyReasoning()).contains("50 feet or 50 seconds");
	}

	// --- draftReply() ---

	@Test
	void draftReplySetsReplyBody() {
		stubChatModel("Dear customer, we are sending a replacement. Regards, Automated Support Team");

		SupportTicketContext context = new SupportTicketContext();
		context.setOriginalEmailBody("My item broke.");
		context.setResolutionAction("Send replacement");

		SupportTicketContext result = supportAgents.draftReply(context);

		assertThat(result.getDraftedReplyBody()).contains("Automated Support Team");
	}

	// --- helpers ---

	private void stubChatModel(String content) {
		ChatResponse response = new ChatResponse(
				List.of(new Generation(new AssistantMessage(content))));
		given(chatModel.call(any(Prompt.class))).willReturn(response);
	}

	private SupportTicketContext contextWithBody(String body) {
		SupportTicketContext context = new SupportTicketContext();
		context.setOriginalEmailBody(body);
		return context;
	}

	private SupportTicketContext contextWithCategory(String category) {
		SupportTicketContext context = new SupportTicketContext();
		context.setOriginalEmailBody("Some email body.");
		context.setCategory(category);
		return context;
	}

}
