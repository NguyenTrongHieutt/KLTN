/**
 * Layer 1 (AD-3, AD-10): the platform's GitHub App credentials. Each App signs a short JWT with its
 * private key, and every installation token is minted for one operation, narrowed by numeric
 * {@code repository_ids} and by the permissions that operation needs, and revoked after use. There is
 * no machine account and no personal access token. The key files are secrets (AD-22).
 * <p>
 * Public API: {@link vn.vibefix.github.GitHubApps}. Story 2.1's {@code spike-s4} probe is wiring in
 * {@code github.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared" })
package vn.vibefix.github;

import org.springframework.modulith.ApplicationModule;
