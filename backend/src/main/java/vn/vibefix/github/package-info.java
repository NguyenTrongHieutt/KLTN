/**
 * Layer 1 (AD-3, AD-10): the platform's GitHub App credentials. Each App signs a short JWT with its
 * private key, and every installation token is minted for one operation, narrowed by numeric
 * {@code repository_ids} and by the permissions that operation needs, and revoked after use. There is
 * no machine account and no personal access token. The key files are secrets (AD-22).
 * <p>
 * It also receives both Apps' webhooks at {@code POST /webhooks} (AD-12): the signature is checked over
 * the raw bytes, each delivery ID is accepted once, failed deliveries are redelivered daily, and a daily
 * check compares each App with AD-10 (SG-18). And it serves the Actions token exchange at
 * {@code POST /github/actions/exchange} (AD-44, Story 23.4): a GitHub Actions run it dispatched trades its
 * OIDC ID token for one token narrowed to its correlation's grant, which is revoked when the run ends.
 * <p>
 * Public API: {@link vn.vibefix.github.GitHubApps}, {@link vn.vibefix.github.MergeApp} (the Customers'
 * Merge App installations, Story 8.7), {@link vn.vibefix.github.SourceApp} (the Customers' Source App
 * installations, which have no webhook, the published {@code vibefix.yml}, and its check, Story 23.5),
 * {@link vn.vibefix.github.GitHubRepositories}
 * (read-only metadata with a caller's credential), {@link vn.vibefix.github.OAuthGrants} (revoking a token the
 * linking App issued once vibefix stops keeping it, and the App's grant when an account is deleted, SG-9),
 * {@link vn.vibefix.github.ActionsRuns} (dispatching exchange runs through the Platform App or a Customer's Source App
 * installation, reading them, and their correlations, Stories 23.4 and 23.6a), and the events
 * {@link vn.vibefix.github.GitHubWebhookReceived}, {@link vn.vibefix.github.GitHubConfigurationDrift},
 * {@link vn.vibefix.github.GitHubExchangeIssued}, {@link vn.vibefix.github.GitHubExchangeRefused},
 * {@link vn.vibefix.github.GitHubExchangeRevoked} and {@link vn.vibefix.github.GitHubExchangeRevokeUnknown}, which
 * {@code admin} audits and alerts on. Story 2.1's {@code spike-s4} probe, Spike S6's {@code spike-s6}, Story 23.4's
 * {@code runner-dispatch}, the webhook endpoint and the exchange endpoint are wiring in {@code github.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared" })
package vn.vibefix.github;

import org.springframework.modulith.ApplicationModule;
