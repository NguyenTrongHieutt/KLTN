package vn.vibefix.product.internal;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.github.GitHubRepositories.RepositoryMetadata;
import vn.vibefix.identity.ActiveAccount;
import vn.vibefix.identity.GitHubGate;
import vn.vibefix.product.internal.Products.Product;
import vn.vibefix.product.internal.Products.Registration;
import vn.vibefix.product.internal.Products.Tool;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * CUS-2 to CUS-4 (FR-7, AD-13): a user's own Products, the registration wizard's live repository check and
 * its submission, and one Product's detail, which only its Customer and Administrators read (AD-20); vibefix
 * scans no code (Story 23.1), so none of them shows a scan. No account role gates them (Story 20.3):
 * registering a Product and its repository check need GitHub linked ({@code FORBIDDEN GITHUB_NOT_LINKED},
 * FR-80), every other endpoint an unlocked user, and every query is scoped to the caller's own Products, so
 * any other caller finds nothing.
 */
@RestController
@RequestMapping("/api/v1/products")
@Access(Gate.SIGNED_IN)
class ProductController {

	record RepositoryCheckRequest(@NotBlank @Size(max = 300) String url) {
	}

	/** CUS-3 step 2's result: the repository that passed every check. */
	record RepositoryCheckView(long githubId, String fullName, String htmlUrl, String ownerType, long sizeKb,
			@Nullable String forkParent, String defaultBranch) {

		static RepositoryCheckView of(RepositoryMetadata repository) {
			return new RepositoryCheckView(repository.id(), repository.fullName(), repository.htmlUrl(),
					repository.ownerType(), repository.sizeKb(), repository.forkParent(), repository.defaultBranch());
		}

	}

	record ProductRegistrationRequest(@NotBlank @Size(max = 300) String repositoryUrl,
			@NotNull @NotEmpty Set<@NotNull Tool> tools, @NotBlank @Size(max = 80) String name,
			@NotBlank @Size(max = 1000) String description,
			@Nullable @Size(max = 300) @Pattern(regexp = "^https://\\S+$") String demoUrl,
			@Nullable String publicNoticeVersion, @Nullable String copyPermissionVersion,
			@Nullable String detectionNoticeVersion) {
	}

	record ProductCreatedView(UUID id) {
	}

	/** One Product as CUS-2 and CUS-4 show it. */
	record ProductView(UUID id, String name, String description, @Nullable String demoUrl, List<Tool> tools,
			String repositoryFullName, String repositoryUrl, String repositoryOwnerType, @Nullable String forkParent,
			Instant createdAt, int jobs) {

		static ProductView of(Product product) {
			return new ProductView(product.id(), product.name(), product.description(), product.demoUrl(), product.tools(),
					product.repositoryFullName(), "https://github.com/" + product.repositoryFullName(),
					product.repositoryOwnerType(), product.forkParent(), product.createdAt(), 0);
		}

	}

	/** A Skill CUS-6 pre-selects, with its plain hint (UX-DR75). */
	record SuggestedSkillView(UUID id, String name, String hint) {
	}

	private final Products products;

	private final RepositoryChecks checks;

	private final SkillSuggestions skillSuggestions;

	private final CurrentCaller callers;

	private final GitHubGate gitHub;

	private final ActiveAccount active;

	ProductController(Products products, RepositoryChecks checks, SkillSuggestions skillSuggestions, CurrentCaller callers,
			GitHubGate gitHub, ActiveAccount active) {
		this.gitHub = gitHub;
		this.active = active;
		this.products = products;
		this.checks = checks;
		this.skillSuggestions = skillSuggestions;
		this.callers = callers;
	}

	@GetMapping
	List<ProductView> products() {
		return this.products.list(customer()).stream().map(ProductView::of).toList();
	}

	/**
	 * CUS-3 step 2: every repository check, read with the user's own GitHub token; GitHub must be linked
	 * (FR-80, the UX-DR95 gate).
	 */
	@PostMapping("/repository-check")
	RepositoryCheckView check(@Valid @RequestBody RepositoryCheckRequest body) {
		UUID customer = linkedUser();
		return RepositoryCheckView.of(this.checks.check(customer, body.url()));
	}

	/** CUS-3 step 4: "Đăng ký sản phẩm"; GitHub must be linked (FR-80). */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	ProductCreatedView register(@Valid @RequestBody ProductRegistrationRequest body) {
		UUID customer = linkedUser();
		return new ProductCreatedView(this.products.register(customer,
				new Registration(body.repositoryUrl(), body.tools(), body.name(), body.description(), body.demoUrl(),
						body.publicNoticeVersion(), body.copyPermissionVersion(), body.detectionNoticeVersion())));
	}

	/** CUS-4: the Product's Customer, or an Administrator. */
	@GetMapping("/{productId}")
	ProductView product(@PathVariable UUID productId) {
		return ProductView.of(readable(productId));
	}

	/** CUS-4 "Xóa sản phẩm", after the destructive Confirmation dialog: the Product's Customer only. */
	@DeleteMapping("/{productId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@PathVariable UUID productId) {
		this.products.delete(owner(productId), productId);
	}

	/** CUS-6 "Kỹ năng cần có": the Skills suggested from the repository's languages and the AI tools. */
	@GetMapping("/{productId}/skill-suggestions")
	List<SuggestedSkillView> skillSuggestions(@PathVariable UUID productId) {
		return this.skillSuggestions.suggest(owner(productId), productId)
			.stream()
			.map(skill -> new SuggestedSkillView(skill.id(), skill.name(), skill.hint()))
			.toList();
	}

	/** The Product, for its own Customer (an unlocked user) or an Administrator; anyone else finds nothing. */
	private Product readable(UUID productId) {
		Caller caller = this.callers.get();
		if (caller.isAdministrator()) {
			return this.products.find(productId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		}
		UUID customer = customer();
		return this.products.find(productId)
			.filter(found -> found.customerId().equals(customer))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	/**
	 * The calling user, unlocked (AD-19, read on every request), whose own Products every query is scoped to; an
	 * Administrator, who holds no Product, finds nothing.
	 */
	private UUID customer() {
		Caller caller = this.callers.get();
		if (!caller.isUser()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID customer = caller.requireAccountId();
		this.active.requireActiveAccount(customer);
		return customer;
	}

	/** The Product's own Customer calling, unlocked (AD-20). Anyone else finds nothing. */
	private UUID owner(UUID productId) {
		UUID customer = customer();
		this.products.find(productId)
			.filter(found -> found.customerId().equals(customer))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		return customer;
	}

	/** The calling user with GitHub linked (FR-80, X1), for the commands that register a Product. */
	private UUID linkedUser() {
		Caller caller = this.callers.get();
		if (!caller.isUser()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID customer = caller.requireAccountId();
		this.gitHub.requireGitHubLinked(customer);
		return customer;
	}

}
