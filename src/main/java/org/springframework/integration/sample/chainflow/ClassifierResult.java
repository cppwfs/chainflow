package org.springframework.integration.sample.chainflow;

/**
 * Output of the Classifier Agent.
 *
 * @param isSupportTicket whether the email is a support ticket
 * @param category the category of the ticket
 *
 * @author Glenn Renfro
 */
public record ClassifierResult(boolean isSupportTicket, String category) {
}
