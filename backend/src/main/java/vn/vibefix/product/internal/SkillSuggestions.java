package vn.vibefix.product.internal;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.catalog.Catalog;
import vn.vibefix.catalog.Skill;
import vn.vibefix.github.GitHubException;
import vn.vibefix.identity.CustomerRepositories;
import vn.vibefix.product.internal.Products.Product;
import vn.vibefix.product.internal.Products.Tool;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * CUS-6's suggested Skills (FR-9, FR-72, AD-38): the active Skills whose match keys fit the Source
 * Repository's GitHub languages, read as metadata with the Customer's token (SG-8), or the Product's
 * AI tools. When GitHub cannot answer, the tools alone suggest.
 */
@Component
class SkillSuggestions {

	/** The catalog's tool match keys (addendum B7) for each AI tool of CUS-3. */
	static final Map<Tool, String> TOOL_KEYS = Map.of(Tool.LOVABLE, "Lovable", Tool.BOLT, "Bolt.new", Tool.REPLIT,
			"Replit", Tool.V0, "v0", Tool.BASE44, "Base44", Tool.CURSOR, "Cursor", Tool.CLAUDE_CODE, "Claude Code",
			Tool.CODEX, "Codex");

	private final Products products;

	private final CustomerRepositories repositories;

	private final Catalog catalog;

	SkillSuggestions(Products products, CustomerRepositories repositories, Catalog catalog) {
		this.products = products;
		this.repositories = repositories;
		this.catalog = catalog;
	}

	/** @throws ApiException {@code NOT_FOUND} for a Product that is not the Customer's */
	List<Skill> suggest(UUID customerId, UUID productId) {
		Product product = this.products.find(productId)
			.filter(found -> found.customerId().equals(customerId))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		String[] repository = product.repositoryFullName().split("/", 2);
		List<String> languages;
		try {
			languages = this.repositories.languages(customerId, repository[0], repository[1]);
		}
		catch (GitHubException | ApiException ex) {
			languages = List.of();
		}
		List<String> tools = product.tools().stream().map(TOOL_KEYS::get).filter(Objects::nonNull).toList();
		return this.catalog.suggest(languages, tools);
	}

}
