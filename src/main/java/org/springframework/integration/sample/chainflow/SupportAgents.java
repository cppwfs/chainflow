package org.springframework.integration.sample.chainflow;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Agents for customer support email processing.
 *
 * @author Glenn Renfro
 */
@Component
public class SupportAgents {

	private final String classifyPrompt;

	private final String resolvePrompt;

	private final String draftPrompt;

	private final ChatClient chatClient;

	public SupportAgents(ChatClient.Builder chatClientBuilder,
			@Value("${org.springframework.integration.samples.chainflow.classify.prompt}") String classifyPrompt,
			@Value("${org.springframework.integration.samples.chainflow.resolve.prompt}") String resolvePrompt,
			@Value("${org.springframework.integration.samples.chainflow.draft.prompt}") String draftPrompt) {
		this.chatClient = chatClientBuilder.build();
		this.classifyPrompt = classifyPrompt;
		this.resolvePrompt = resolvePrompt;
		this.draftPrompt = draftPrompt;
	}

	/**
	 * Classifies the support ticket.
	 * @param context the support ticket context
	 * @return the updated context
	 */
	public SupportTicketContext classify(SupportTicketContext context) {
		ClassifierResult result = this.chatClient.prompt()
				.user(String.format(classifyPrompt, context.getOriginalEmailBody()))
				.call()
				.entity(new BeanOutputConverter<>(ClassifierResult.class));

		if (result != null) {
			context.setCategory(result.isSupportTicket() ? result.category() : "IGNORED");
		}
		else {
			context.setCategory("IGNORED");
		}
		return context;
	}

	/**
	 * Resolves the support ticket based on its category.
	 * @param context the support ticket context
	 * @return the updated context
	 */
	public SupportTicketContext resolve(SupportTicketContext context) {;

		ResolverResult result = this.chatClient.prompt()
				.user(String.format(this.resolvePrompt, context.getCategory(), context.getOriginalEmailBody()))
				.call()
				.entity(new BeanOutputConverter<>(ResolverResult.class));

		if (result != null) {
			context.setResolutionAction(result.action());
			context.setPolicyReasoning(result.reasoning());
		}
		return context;
	}

	/**
	 * Drafts a reply to the customer.
	 * @param context the support ticket context
	 * @return the updated context
	 */
	public SupportTicketContext draftReply(SupportTicketContext context) {

		String reply = this.chatClient.prompt()
				.user(String.format(this.draftPrompt, context.getResolutionAction(), context.getOriginalEmailBody()))
				.call()
				.content();

		context.setDraftedReplyBody(reply);
		return context;
	}

}
