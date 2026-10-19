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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.github.GitHubRepositories.RepositoryMetadata;
import vn.vibefix.product.internal.Products.Product;
import vn.vibefix.product.internal.Products.Registration;
import vn.vibefix.product.internal.Products.ScanState;
import vn.vibefix.product.internal.Products.Tool;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;

/**
 * CUS-2, CUS-3, and CUS-4 (FR-7, AD-13): a Customer's Products, the registration wizard's live
 * repository check and its submission, and one Product's detail, which only its Customer and
 * Administrators read (AD-20). Any other caller finds nothing.
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
			Instant createdAt, ScanState scanState, int openFindings, int jobs) {

		static ProductView of(Product product) {
			return new ProductView(product.id(), product.name(), product.description(), product.demoUrl(), product.tools(),
					product.repositoryFullName(), "https://github.com/" + product.repositoryFullName(),
					product.repositoryOwnerType(), product.forkParent(), product.createdAt(), product.scanState(), 0, 0);
		}

	}

	private final Products products;

	private final RepositoryChecks checks;

	private final CurrentCaller callers;

	ProductController(Products products, RepositoryChecks checks, CurrentCaller callers) {
		this.products = products;
		this.checks = checks;
		this.callers = callers;
	}

	@GetMapping
	List<ProductView> products() {
		return this.products.list(customer()).stream().map(ProductView::of).toList();
	}

	/** CUS-3 step 2: every repository check, read with the Customer's own GitHub sign-in. */
	@PostMapping("/repository-check")
	RepositoryCheckView check(@Valid @RequestBody RepositoryCheckRequest body) {
		return RepositoryCheckView.of(this.checks.check(customer(), body.url()));
	}

	/** CUS-3 step 4: "Bắt đầu kiểm tra". */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	ProductCreatedView register(@Valid @RequestBody ProductRegistrationRequest body) {
		return new ProductCreatedView(this.products.register(customer(),
				new Registration(body.repositoryUrl(), body.tools(), body.name(), body.description(), body.demoUrl(),
						body.publicNoticeVersion(), body.copyPermissionVersion(), body.detectionNoticeVersion())));
	}

	/** CUS-4: the Product's Customer, or an Administrator. */
	@GetMapping("/{productId}")
	ProductView product(@PathVariable UUID productId) {
		Caller caller = this.callers.get();
		return this.products.find(productId)
			.filter(found -> caller.hasRole(Role.ADMINISTRATOR)
					|| caller.hasRole(Role.CUSTOMER) && found.customerId().equals(caller.requireAccountId()))
			.map(ProductView::of)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private UUID customer() {
		Caller caller = this.callers.get();
		if (!caller.hasRole(Role.CUSTOMER)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return caller.requireAccountId();
	}

}
