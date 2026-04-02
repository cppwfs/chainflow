package org.springframework.integration.sample.chainflow;

/**
 * Output of the Resolver Agent.
 *
 * @param action the action to take
 * @param reasoning the reasoning for the action
 *
 * @author Glenn Renfro
 */
public record ResolverResult(String action, String reasoning) {
}
