package org.springframework.integration.sample.chainflow;

import jakarta.mail.BodyPart;
import jakarta.mail.Flags;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.NotTerm;

import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.Pollers;
import org.springframework.integration.mail.inbound.ImapMailReceiver;
import org.springframework.integration.mail.dsl.Mail;
import org.springframework.mail.MailMessage;
import org.springframework.mail.SimpleMailMessage;

import java.time.Duration;


/**
 * Configuration for the customer support email flow.
 *
 * @author Glenn Renfro
 */
@Configuration
public class SupportFlowConfig {

	private static final Logger log = LoggerFactory.getLogger(SupportFlowConfig.class);

	@Value("${org.springframework.samples.chainflow.imap.host:imap.example.com}")
	private String imapHost;

	@Value("${org.springframework.samples.chainflow.imap.username:support@example.com}")
	private String imapUsername;

	@Value("${org.springframework.samples.chainflow.smtp.username:support@example.com}")
	private String smtpUsername;

	@Value("${org.springframework.samples.chainflow.password:secret}")
	private String password;

	@Value("${org.springframework.samples.chainflow.smtp.host:smtp.example.com}")
	private String smtpHost;

	@Value("${org.springframework.samples.chainflow.folder:INBOX}")
	private String folder;

	@Bean
	public ImapMailReceiver imapMailReceiver() {
		ImapMailReceiver receiver = new ImapMailReceiver(
				"imaps://"+ imapUsername + ":" + password + "@" + imapHost + "/" + this.folder);
		receiver.setShouldMarkMessagesAsRead(true);
		receiver.setShouldDeleteMessages(false);
		receiver.setMaxFetchSize(10);

		receiver.setAutoCloseFolder(false);
		return receiver;
	}

	/**
	 * Main integration flow for processing support emails.
	 * @param agents the support agents
	 * @return the integration flow
	 */
	@Bean
	public IntegrationFlow supportEmailFlow(SupportAgents agents) {
		return IntegrationFlow
				.from(Mail.imapInboundAdapter(imapMailReceiver()),
						e -> e.poller(Pollers.fixedDelay(Duration.ofSeconds(20))
								.errorChannel("supportErrorChannel")))
				.wireTap(flow -> flow.handle(msg -> log.info("Received mail message: {}", msg.getPayload())))
				.transform(Message.class, this::convertToContext)
				.wireTap(flow -> flow.handle(msg -> log.info("POST Transform: {}",
								((SupportTicketContext)msg.getPayload()).getSubject())))
				.transform(agents::classify)
				.filter(SupportTicketContext.class, context -> !"IGNORED".equals(context.getCategory()),
						filterEndpoint -> filterEndpoint.discardChannel("nullChannel"))
				.transform(agents::resolve)
				.transform(agents::draftReply)
				.transform(SupportTicketContext.class, this::convertToMailMessage)
				.handle(Mail.outboundAdapter(this.smtpHost)
						.credentials(this.smtpUsername, this.password)
						.protocol("smtps"))
				.get();
	}

	@Bean
	public IntegrationFlow supportErrorFlow() {
		return IntegrationFlow.from("supportErrorChannel")
				.handle(message -> {
					Throwable cause = (Throwable) message.getPayload();
					log.error("Error processing support email", cause);
				})
				.get();
	}

	private SupportTicketContext convertToContext(Message message) {
		SupportTicketContext context = new SupportTicketContext();
		try {
			context.setSubject(message.getSubject());
			InternetAddress[] fromAddresses = (InternetAddress[]) message.getFrom();
			if (fromAddresses != null && fromAddresses.length > 0) {
				context.setSenderEmail(fromAddresses[0].getAddress());
			}
			context.setOriginalEmailBody(extractText(message));
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to parse email message", e);
		}
		return context;
	}

	private String extractText(Part part) throws Exception {
		String type = part.isMimeType("text/plain") ? "plain"
				: part.isMimeType("text/html") ? "html"
				: part.isMimeType("multipart/*") ? "multipart"
				: "other";

		return switch (type) {
			case "plain", "html" -> MimeUtility.decodeText((String) part.getContent());
			case "multipart" -> {
				Multipart multipart = (Multipart) part.getContent();
				// Prefer plain text — scan for it first
				for (int i = 0; i < multipart.getCount(); i++) {
					BodyPart bp = multipart.getBodyPart(i);
					if (bp.isMimeType("text/plain")) {
						yield MimeUtility.decodeText((String) bp.getContent());
					}
				}
				// Fall back to recursing into nested parts
				for (int i = 0; i < multipart.getCount(); i++) {
					String result = extractText(multipart.getBodyPart(i));
					if (result != null) {
						yield result;
					}
				}
				yield null;
			}
			default -> null;
		};
	}

	private MailMessage convertToMailMessage(SupportTicketContext context) {
		SimpleMailMessage mailMessage = new SimpleMailMessage();
		mailMessage.setTo(context.getSenderEmail());
		mailMessage.setSubject("Re: " + context.getSubject());
		mailMessage.setText(context.getDraftedReplyBody());
		return mailMessage;
	}

}
