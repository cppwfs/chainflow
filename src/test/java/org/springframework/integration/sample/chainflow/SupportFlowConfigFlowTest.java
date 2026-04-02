package org.springframework.integration.sample.chainflow;

import java.util.List;

import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.integration.test.context.MockIntegrationContext;
import org.springframework.integration.test.context.SpringIntegrationTest;
import org.springframework.integration.test.mock.MockIntegration;
import org.springframework.integration.test.mock.MockMessageHandler;
import org.springframework.mail.MailMessage;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Integration tests that exercise the {@code supportEmailFlow} bean end-to-end.
 *
 * <p>The IMAP inbound adapter is replaced by a {@link MockIntegration#mockMessageSource}
 * that produces a mocked {@link jakarta.mail.Message}, matching the type expected by
 * the first {@code .transform(Message.class, this::convertToContext)} step.
 * The terminal SMTP outbound handler is replaced by a {@link MockMessageHandler} with an
 * {@link ArgumentCaptor} so the outbound {@link MailMessage} can be inspected.
 *
 * <p>Bean names for the DSL-generated endpoints follow the pattern
 * {@code <flowBeanName>.<FactoryBeanClass>#<index>}. The source polling adapter is
 * index #0; the SMTP outbound adapter is the last {@code ConsumerEndpointFactoryBean}
 * in the flow (#6).
 *
 * @author Glenn Renfro
 */
@SpringBootTest
@SpringIntegrationTest(noAutoStartup =
		"supportEmailFlow.org.springframework.integration.config.SourcePollingChannelAdapterFactoryBean#0")
@TestPropertySource(properties = {
		"org.springframework.samples.chainflow.imap.host=localhost",
		"org.springframework.samples.chainflow.imap.username=test@example.com",
		"org.springframework.samples.chainflow.smtp.username=test@example.com",
		"org.springframework.samples.chainflow.password=secret",
		"org.springframework.samples.chainflow.smtp.host=localhost",
		"org.springframework.samples.chainflow.folder=INBOX",
		"org.springframework.integration.samples.chainflow.classify.prompt=Classify: %s",
		"org.springframework.integration.samples.chainflow.resolve.prompt=Resolve category=%s email=%s",
		"org.springframework.integration.samples.chainflow.draft.prompt=Draft reply for action=%s email=%s"
})
class SupportFlowConfigFlowTest {

	/** DSL-generated bean name for the IMAP SourcePollingChannelAdapter. */
	private static final String IMAP_SOURCE_ENDPOINT =
			"supportEmailFlow.org.springframework.integration.config.SourcePollingChannelAdapterFactoryBean#0";

	/** DSL-generated bean name for the terminal SMTP outbound ConsumerEndpoint. */
	private static final String SMTP_HANDLER_ENDPOINT =
			"supportEmailFlow.org.springframework.integration.config.ConsumerEndpointFactoryBean#6";

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
			return new SupportAgents(ChatClient.builder(chatModel),
					"Classify: %s",
					"Resolve category=%s email=%s",
					"Draft reply for action=%s email=%s");
		}
	}

	@Autowired
	private ChatModel chatModel;

	@Autowired
	private MockIntegrationContext mockIntegrationContext;

	@AfterEach
	void resetMocks() {
		mockIntegrationContext.resetBeans();
	}

	// -------------------------------------------------------------------------
	// End-to-end: support ticket flows through classify -> resolve -> draftReply
	// -> outbound adapter
	// -------------------------------------------------------------------------

	@Test
	void supportTicketFlowsToOutboundAdapter() throws Exception {
		given(chatModel.call(any(Prompt.class)))
				.willReturn(chatResponse("""
						{"isSupportTicket": true, "category": "TECHNICAL"}
						"""))
				.willReturn(chatResponse("""
						{"action": "Send replacement", "reasoning": "Technical fault detected."}
						"""))
				.willReturn(chatResponse(
						"Dear customer, your replacement is on the way. Regards, Automated Support Team"));

		// Substitute SMTP handler first so it is in place before the source fires
		ArgumentCaptor<org.springframework.messaging.Message<?>> captor =
				MockIntegration.messageArgumentCaptor();
		MockMessageHandler mockSmtpHandler = MockIntegration.mockMessageHandler(captor)
				.handleNext(msg -> { /* accept and drop — no real SMTP */ });
		mockIntegrationContext.substituteMessageHandlerFor(SMTP_HANDLER_ENDPOINT, mockSmtpHandler);

		// Substitute the IMAP source with a mock jakarta.mail.Message and start
		Message mailMessage = buildMockMailMessage("broken@example.com", "My device broke",
				"Nothing works since yesterday.");
		mockIntegrationContext.substituteMessageSourceFor(
				IMAP_SOURCE_ENDPOINT, MockIntegration.mockMessageSource(mailMessage), true);

		// Allow the poller to fire and process the message
		Thread.sleep(500);

		org.springframework.messaging.Message<?> captured = captor.getValue();
		assertThat(captured).isNotNull();
		Object payload = captured.getPayload();
		assertThat(payload).isInstanceOf(MailMessage.class);
		MailMessage mail = (MailMessage) payload;
		assertThat(mail.toString()).contains("broken@example.com");
		assertThat(mail.toString()).contains("Re: My device broke");
		assertThat(mail.toString()).contains("Automated Support Team");
	}

	@Test
	void ignoredTicketIsFilteredAndDoesNotReachOutboundAdapter() throws Exception {
		given(chatModel.call(any(Prompt.class)))
				.willReturn(chatResponse("""
						{"isSupportTicket": false, "category": "BILLING"}
						"""));

		ArgumentCaptor<org.springframework.messaging.Message<?>> captor =
				MockIntegration.messageArgumentCaptor();
		MockMessageHandler mockSmtpHandler = MockIntegration.mockMessageHandler(captor)
				.handleNext(msg -> { });
		mockIntegrationContext.substituteMessageHandlerFor(SMTP_HANDLER_ENDPOINT, mockSmtpHandler);

		Message mailMessage = buildMockMailMessage("spam@example.com", "Win a prize",
				"Click here to claim.");
		mockIntegrationContext.substituteMessageSourceFor(
				IMAP_SOURCE_ENDPOINT, MockIntegration.mockMessageSource(mailMessage), true);

		Thread.sleep(500);

		// The filter discards IGNORED tickets to nullChannel — the outbound handler must not fire
		try {
			org.springframework.messaging.Message<?> captured = captor.getValue();
			assertThat(captured)
					.as("IGNORED ticket should not reach the outbound adapter").isNull();
		}
		catch (org.mockito.exceptions.base.MockitoException e) {
			// No value was captured — this is the expected, correct path
		}
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	/**
	 * Builds a Mockito mock of {@link jakarta.mail.Message} that satisfies the
	 * field accesses performed by {@link SupportFlowConfig#convertToContext}.
	 */
	private Message buildMockMailMessage(String from, String subject, String body)
			throws Exception {
		Message msg = mock(Message.class);
		given(msg.getSubject()).willReturn(subject);
		given(msg.getFrom()).willReturn(new InternetAddress[]{ new InternetAddress(from) });
		// isMimeType("text/plain") -> true so extractText returns (String) getContent()
		given(msg.isMimeType("text/plain")).willReturn(true);
		given(msg.getContent()).willReturn(body);
		return msg;
	}

	private ChatResponse chatResponse(String content) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
	}
}
