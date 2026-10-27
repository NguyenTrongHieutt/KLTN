package vn.vibefix.job.internal;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.identity.GitHubGate;
import vn.vibefix.product.ProductDirectory;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.ReportableContent;

/**
 * FR-69: a Job Post and its Product are reportable by whoever MEN-3 shows them to: an unlocked user with GitHub
 * linked ("Tìm việc", X1, Story 20.3) reading an Open Job Post that is not hidden, but never by its own Customer,
 * as nobody reports their own content.
 */
@Component
class JobPostReportable implements ReportableContent {

	private final JobPostQueries posts;

	private final ProductDirectory products;

	private final GitHubGate gitHub;

	private final JdbcClient jdbc;

	JobPostReportable(JobPostQueries posts, ProductDirectory products, GitHubGate gitHub, JdbcClient jdbc) {
		this.posts = posts;
		this.products = products;
		this.gitHub = gitHub;
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> kinds() {
		return Set.of("JOB_POST", "PRODUCT");
	}

	/** Runs outside any transaction: a refused read must not mark the caller's transaction rollback-only. */
	@Override
	public Optional<Reported> reportable(String kind, UUID id, Caller caller) {
		if (!caller.isUser() || !linked(caller.requireAccountId())) {
			return Optional.empty();
		}
		UUID reader = caller.requireAccountId();
		if ("JOB_POST".equals(kind)) {
			return this.posts.find(id)
				.filter(job -> !job.customerId().equals(reader))
				.map(job -> new Reported(job.id(), job.productId(), job.customerId(), job.title()));
		}
		if (!"PRODUCT".equals(kind)) {
			return Optional.empty();
		}
		boolean posted = this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM job.job WHERE product_id = :product AND state = 'OPEN' AND hidden_at IS NULL)
				""").param("product", id).query(Boolean.class).single();
		if (!posted || !this.products.hidden(Set.of(id)).isEmpty()) {
			return Optional.empty();
		}
		return this.products.find(id)
			.filter(product -> !product.customerId().equals(reader))
			.map(product -> new Reported(null, product.id(), product.customerId(), product.name()));
	}

	/** The gate reads the account again (AD-19); its refusal is only an answer here. */
	private boolean linked(UUID readerId) {
		try {
			this.gitHub.requireGitHubLinked(readerId);
			return true;
		}
		catch (ApiException refused) {
			return false;
		}
	}

}
