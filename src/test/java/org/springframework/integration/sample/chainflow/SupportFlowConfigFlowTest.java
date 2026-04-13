package org.springframework.integration.sample.chainflow;

import java.util.List;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
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
import org.springframework.context.annotation.Bean;
import org.springframework.integration.test.context.MockIntegrationContext;
import org.springframework.integration.test.context.SpringIntegrationTest;
import org.springframework.integration.test.mock.MockIntegration;
import org.springframework.integration.test.mock.MockMessageHandler;
import org.springframework.mail.MailMessage;
import org.springframework.test.context.TestPropertySource;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * @author Glenn Renfro
 */
@SpringBootTest
@SpringIntegrationTest
@TestPropertySource(properties = {
		"org.springframework.integration.samples.chainflow.classify.prompt=Classify: %s",
		"org.springframework.integration.samples.chainflow.resolve.prompt=Resolve category=%s email=%s",
		"org.springframework.integration.samples.chainflow.draft.prompt=Draft reply for action=%s email=%s"
})
class SupportFlowConfigFlowTest {

	private static final String IMAP_SOURCE_ENDPOINT =
			"supportEmailFlow.org.springframework.integration.config.SourcePollingChannelAdapterFactoryBean#0";

	private static final String SMTP_HANDLER_ENDPOINT =
			"supportEmailFlow.org.springframework.integration.config.ConsumerEndpointFactoryBean#6";

	@Autowired
	private ChatModel chatModel;

	@Autowired
	private MockIntegrationContext mockIntegrationContext;

	@Test
	void supportTicketFlowsToOutboundAdapter() throws Exception {
		given(this.chatModel.call(any(Prompt.class)))
				.willReturn(chatResponse("""
						{"isSupportTicket": true, "category": "TECHNICAL"}
						"""))
				.willReturn(chatResponse("""
						{"action": "Send replacement", "reasoning": "Technical fault detected."}
						"""))
				.willReturn(chatResponse(
						"Dear customer, your replacement is on the way. Regards, Automated Support Team"));

		ArgumentCaptor<org.springframework.messaging.Message<?>> captor = MockIntegration.messageArgumentCaptor();
		MockMessageHandler mockSmtpHandler = MockIntegration.mockMessageHandler(captor)
				.handleNext(msg -> { });
		this.mockIntegrationContext.substituteMessageHandlerFor(SMTP_HANDLER_ENDPOINT, mockSmtpHandler);

		Message mailMessage = buildMockMailMessage("broken@example.com", "My device broke",
				"Nothing works since yesterday.");
		this.mockIntegrationContext.substituteMessageSourceFor(
				IMAP_SOURCE_ENDPOINT, MockIntegration.mockMessageSource(mailMessage), true);

		await().atMost(1000, MILLISECONDS)
				.untilAsserted(() -> assertThat(captor.getAllValues()).isNotEmpty());

		assertThat(captor.getAllValues())
				.hasSize(1)
				.first()
				.satisfies(captured -> {
					assertThat(captured.getPayload())
							.isInstanceOf(MailMessage.class)
							.asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(MailMessage.class))
							.extracting(Object::toString)
							.asString()
							.contains("broken@example.com", "Re: My device broke", "Automated Support Team");
				});
	}

	@Test
	void ignoredTicketIsFilteredAndDoesNotReachOutboundAdapter() throws Exception {
		given(this.chatModel.call(any(Prompt.class)))
				.willReturn(chatResponse("""
						{"isSupportTicket": false, "category": "BILLING"}
						"""));

		ArgumentCaptor<org.springframework.messaging.Message<?>> captor =
				MockIntegration.messageArgumentCaptor();
		MockMessageHandler mockSmtpHandler = MockIntegration.mockMessageHandler(captor)
				.handleNext(msg -> { });

		Message mailMessage = buildMockMailMessage("spam@example.com", "Win a prize", "Click here to claim.");
		this.mockIntegrationContext.substituteMessageHandlerFor(SMTP_HANDLER_ENDPOINT, mockSmtpHandler);
		this.mockIntegrationContext.substituteMessageSourceFor(
				IMAP_SOURCE_ENDPOINT, MockIntegration.mockMessageSource(mailMessage), true);

		await().atMost(1000, MILLISECONDS)
				.untilAsserted(() -> assertThat(captor.getAllValues())
						.isEmpty());
	}

	private Message buildMockMailMessage(String from, String subject, String body)
			throws Exception {
		MimeMessage msg = new MimeMessage((Session) null);
		msg.setFrom(new InternetAddress(from));
		msg.setSubject(subject);
		msg.setText(body);
		return msg;
	}

	private ChatResponse chatResponse(String content) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		ChatModel chatModel() {
			return mock(ChatModel.class);
		}

		@Bean
		SupportAgents supportAgents(ChatModel chatModel) {
			return new SupportAgents(ChatClient.builder(chatModel),
					"Classify: %s", "Resolve category=%s email=%s",
					"Draft reply for action=%s email=%s");
		}
	}
}
