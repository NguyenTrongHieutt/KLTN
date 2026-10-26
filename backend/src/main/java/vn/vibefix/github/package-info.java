/**
 * Layer 1 (AD-3, AD-10): the platform's GitHub App credentials. Each App signs a short JWT with its
 * private key, and every installation token is minted for one operation, narrowed by numeric
 * {@code repository_ids} and by the permissions that operation needs, and revoked after use. There is
 * no machine account and no personal access token. The key files are secrets (AD-22).
 * <p>
 * It also receives both Apps' webhooks at {@code POST /webhooks} (AD-12): the signature is checked over
 * the raw bytes, each delivery ID is accepted once, failed deliveries are redelivered daily, and a daily
 * check compares each App with AD-10 (SG-18).
 * <p>
 * Public API: {@link vn.vibefix.github.GitHubApps}, {@link vn.vibefix.github.MergeApp} (the Customers'
 * Merge App installations, Story 8.7), {@link vn.vibefix.github.GitHubRepositories}
 * (read-only metadata with a caller's credential), {@link vn.vibefix.github.OAuthGrants} (revoking a token the
 * linking App issued once vibefix stops keeping it, and the App's grant when an account is deleted, SG-9), and the events
 * {@link vn.vibefix.github.GitHubWebhookReceived} and {@link vn.vibefix.github.GitHubConfigurationDrift}.
 * Story 2.1's {@code spike-s4} probe and the webhook endpoint are wiring in {@code github.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared" })
package vn.vibefix.github;

import org.springframework.modulith.ApplicationModule;
