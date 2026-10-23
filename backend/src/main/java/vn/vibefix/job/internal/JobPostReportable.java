package vn.vibefix.job.internal;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.identity.MentorGate;
import vn.vibefix.product.ProductDirectory;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.ReportableContent;
import vn.vibefix.shared.Role;

/**
 * FR-69: a Job Post and its Product are reportable by whoever MEN-3 shows them to, an approved Mentor
 * reading an Open Job Post that is not hidden.
 */
@Component
class JobPostReportable implements ReportableContent {

	private final JobPostQueries posts;

	private final ProductDirectory products;

	private final MentorGate mentors;

	private final JdbcClient jdbc;

	JobPostReportable(JobPostQueries posts, ProductDirectory products, MentorGate mentors, JdbcClient jdbc) {
		this.posts = posts;
		this.products = products;
		this.mentors = mentors;
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> kinds() {
		return Set.of("JOB_POST", "PRODUCT");
	}

	/** Runs outside any transaction: a refused read must not mark the caller's transaction rollback-only. */
	@Override
	public Optional<Reported> reportable(String kind, UUID id, Caller caller) {
		if (!caller.hasRole(Role.MENTOR) || !approved(caller.requireAccountId())) {
			return Optional.empty();
		}
		if ("JOB_POST".equals(kind)) {
			return this.posts.find(id).map(job -> new Reported(job.id(), job.productId(), job.customerId(), job.title()));
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
		return this.products.find(id).map(product -> new Reported(null, product.id(), product.customerId(), product.name()));
	}

	private boolean approved(UUID mentorId) {
		try {
			this.mentors.requireApprovedMentor(mentorId);
			return true;
		}
		catch (ApiException refused) {
			return false;
		}
	}

}
