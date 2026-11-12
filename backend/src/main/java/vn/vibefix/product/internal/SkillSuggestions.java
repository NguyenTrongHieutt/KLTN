package vn.vibefix.product.internal;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.catalog.Catalog;
import vn.vibefix.catalog.Skill;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.GitHubRepositories;
import vn.vibefix.github.SourceApp;
import vn.vibefix.product.internal.Products.Product;
import vn.vibefix.product.internal.Products.Tool;
import vn.vibefix.product.internal.SourceAppBindings.Bound;
import vn.vibefix.product.internal.SourceAppBindings.Checked;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * CUS-6's suggested Skills (FR-9, FR-72, AD-38): the active Skills whose match keys fit the Source
 * Repository's GitHub languages, read as metadata through the Source App (SG-8, Story 23.5) with a token narrowed
 * to the repository once the binding holds on a fresh read, or the Product's AI tools. When the binding does not
 * hold or GitHub cannot answer, the tools alone suggest.
 */
@Component
class SkillSuggestions {

	/** The catalog's tool match keys (addendum B7) for each AI tool of CUS-3. */
	static final Map<Tool, String> TOOL_KEYS = Map.of(Tool.LOVABLE, "Lovable", Tool.BOLT, "Bolt.new", Tool.REPLIT,
			"Replit", Tool.V0, "v0", Tool.BASE44, "Base44", Tool.CURSOR, "Cursor", Tool.CLAUDE_CODE, "Claude Code",
			Tool.CODEX, "Codex");

	private final Products products;

	private final SourceAppBindings bindings;

	private final SourceApp app;

	private final GitHubRepositories repositories;

	private final Catalog catalog;

	SkillSuggestions(Products products, SourceAppBindings bindings, SourceApp app, GitHubRepositories repositories,
			Catalog catalog) {
		this.products = products;
		this.bindings = bindings;
		this.app = app;
		this.repositories = repositories;
		this.catalog = catalog;
	}

	/** @throws ApiException {@code NOT_FOUND} for a Product that is not the Customer's */
	List<Skill> suggest(UUID customerId, UUID productId) {
		Product product = this.products.find(productId)
			.filter(found -> found.customerId().equals(customerId))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		List<String> languages;
		try {
			languages = languages(product);
		}
		catch (GitHubException | ApiException ex) {
			languages = List.of();
		}
		List<String> tools = product.tools().stream().map(TOOL_KEYS::get).filter(Objects::nonNull).toList();
		return this.catalog.suggest(languages, tools);
	}

	private List<String> languages(Product product) {
		Checked checked = this.bindings.forProduct(product.customerId(), product.id(), product.repositoryId(),
				product.repositoryFullName());
		Bound bound = checked.bound();
		if (bound == null) {
			return List.of();
		}
		String[] name = bound.repositoryFullName().split("/", 2);
		return this.app.withToken(bound.installationId(), bound.repositoryId(), Map.of("metadata", "read"),
				token -> this.repositories.languages(token.value(), name[0], name[1]));
	}

}
