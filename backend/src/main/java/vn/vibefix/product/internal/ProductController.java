package vn.vibefix.product.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import vn.vibefix.product.internal.Products.Tool;
import vn.vibefix.scanning.Finding;
import vn.vibefix.scanning.FindingCategory;
import vn.vibefix.scanning.FindingStatus;
import vn.vibefix.scanning.ScanStatus;
import vn.vibefix.scanning.ScanSummary;
import vn.vibefix.scanning.ScanSummary.UnscannedFile;
import vn.vibefix.scanning.Scans;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;

/**
 * CUS-2, CUS-3, and CUS-4 (FR-7, FR-8, AD-13): a Customer's Products with their scan state, the
 * registration wizard's live repository check and its submission, and one Product's detail and open
 * Findings, which only its Customer and Administrators read (AD-20). Any other caller finds nothing.
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

	/**
	 * The Product's state on CUS-2 and CUS-4: no scan yet, a scan running, a scan that did not
	 * finish, open Findings, or clean.
	 */
	enum ScanState {

		AWAITING_SCAN, SCANNING, NOT_FINISHED, FINDINGS, CLEAN;

		static ScanState of(@Nullable ScanSummary latest, int openFindings) {
			if (latest == null) {
				return AWAITING_SCAN;
			}
			if (latest.status().inProgress()) {
				return SCANNING;
			}
			if (openFindings > 0) {
				return FINDINGS;
			}
			return latest.status() == ScanStatus.SUCCEEDED ? CLEAN : NOT_FINISHED;
		}

	}

	/** The latest scan run (UX-DR21: CUS-4 polls every 5 s while it is pending or running). */
	record ScanView(UUID id, ScanStatus status, Instant requestedAt, @Nullable Instant finishedAt,
			@Nullable Integer found, int unscannedCount, List<UnscannedFile> unscanned) {

		static @Nullable ScanView of(@Nullable ScanSummary scan) {
			return scan == null ? null : new ScanView(scan.id(), scan.status(), scan.requestedAt(), scan.finishedAt(),
					scan.found(), scan.unscannedCount(), scan.unscanned());
		}

	}

	/** One Product as CUS-2 and CUS-4 show it. */
	record ProductView(UUID id, String name, String description, @Nullable String demoUrl, List<Tool> tools,
			String repositoryFullName, String repositoryUrl, String repositoryOwnerType, @Nullable String forkParent,
			Instant createdAt, ScanState scanState, @Nullable ScanView scan, int openFindings, int jobs) {

		static ProductView of(Product product, @Nullable ScanSummary scan, int openFindings) {
			return new ProductView(product.id(), product.name(), product.description(), product.demoUrl(), product.tools(),
					product.repositoryFullName(), "https://github.com/" + product.repositoryFullName(),
					product.repositoryOwnerType(), product.forkParent(), product.createdAt(),
					ScanState.of(scan, openFindings), ScanView.of(scan), openFindings, 0);
		}

	}

	/**
	 * A Finding as CUS-4 lists it (FR-8, FR-65): the masked preview only, never the value; for
	 * personal data, up to two masked samples and the lines holding personal values.
	 */
	record FindingView(UUID id, FindingCategory category, String type, String path, String commit,
			Instant committedAt, String maskedPreview, @Nullable Integer matchCount, FindingStatus status,
			Instant createdAt) {

		static FindingView of(Finding finding) {
			return new FindingView(finding.id(), finding.category(), finding.type(), finding.path(), finding.commit(),
					finding.committedAt(), finding.maskedPreview(), finding.matchCount(), finding.status(),
					finding.createdAt());
		}

	}

	private final Products products;

	private final RepositoryChecks checks;

	private final Scans scans;

	private final CurrentCaller callers;

	ProductController(Products products, RepositoryChecks checks, Scans scans, CurrentCaller callers) {
		this.products = products;
		this.checks = checks;
		this.scans = scans;
		this.callers = callers;
	}

	@GetMapping
	List<ProductView> products() {
		List<Product> products = this.products.list(customer());
		List<UUID> ids = products.stream().map(Product::id).toList();
		Map<UUID, ScanSummary> latest = this.scans.latest(ids);
		Map<UUID, Integer> open = this.scans.openFindingCounts(ids);
		return products.stream()
			.map(product -> ProductView.of(product, latest.get(product.id()), open.getOrDefault(product.id(), 0)))
			.toList();
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
		Product product = readable(productId);
		return ProductView.of(product, this.scans.latest(productId).orElse(null),
				this.scans.openFindingCounts(List.of(productId)).getOrDefault(productId, 0));
	}

	/** CUS-4 "Vấn đề phát hiện": the open Findings, for the Product's Customer or an Administrator. */
	@GetMapping("/{productId}/findings")
	List<FindingView> findings(@PathVariable UUID productId) {
		readable(productId);
		return this.scans.openFindings(productId).stream().map(FindingView::of).toList();
	}

	private Product readable(UUID productId) {
		Caller caller = this.callers.get();
		return this.products.find(productId)
			.filter(found -> caller.hasRole(Role.ADMINISTRATOR)
					|| caller.hasRole(Role.CUSTOMER) && found.customerId().equals(caller.requireAccountId()))
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
