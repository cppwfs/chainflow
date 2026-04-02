package org.springframework.integration.sample.chainflow;

/**
 * Context object for the automated agentic customer support email flow.
 *
 * @author Glenn Renfro
 */
public class SupportTicketContext {

	private String senderEmail;

	private String subject;

	private String originalEmailBody;

	private String category;

	private String resolutionAction;

	private String policyReasoning;

	private String draftedReplyBody;

	public String getSenderEmail() {
		return this.senderEmail;
	}

	public void setSenderEmail(String senderEmail) {
		this.senderEmail = senderEmail;
	}

	public String getSubject() {
		return this.subject;
	}

	public void setSubject(String subject) {
		this.subject = subject;
	}

	public String getOriginalEmailBody() {
		return this.originalEmailBody;
	}

	public void setOriginalEmailBody(String originalEmailBody) {
		this.originalEmailBody = originalEmailBody;
	}

	public String getCategory() {
		return this.category;
	}

	public void setCategory(String category) {
		this.category = category;
	}

	public String getResolutionAction() {
		return this.resolutionAction;
	}

	public void setResolutionAction(String resolutionAction) {
		this.resolutionAction = resolutionAction;
	}

	public String getPolicyReasoning() {
		return this.policyReasoning;
	}

	public void setPolicyReasoning(String policyReasoning) {
		this.policyReasoning = policyReasoning;
	}

	public String getDraftedReplyBody() {
		return this.draftedReplyBody;
	}

	public void setDraftedReplyBody(String draftedReplyBody) {
		this.draftedReplyBody = draftedReplyBody;
	}

}
