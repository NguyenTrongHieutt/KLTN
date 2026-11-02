package vn.vibefix.job.internal;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.identity.GitHubGate;
import vn.vibefix.job.Crews.Relationship;
import vn.vibefix.job.JobRole;
import vn.vibefix.job.JobRoles;
import vn.vibefix.product.ProductDirectory;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.ReportableContent;

/**
 * FR-69: a Job Post and its Product are reportable by whoever MEN-3 shows them to: an unlocked user with GitHub
 * linked ("Tìm việc", X1, Story 20.3) reading a Job listed there (AD-36, Story 21.1), but never by its own Customer,
 * as nobody reports their own content. So is the Mentor's recruitment note, while MEN-3 shows it (Story 21.7), by
 * those it shows it to: a reader with no role in the Job and a former member, never the Customer, the Mentor, or a
 * member whose Crew row is not released. The report is against the Mentor, named by the Job, and keeps the note as it
 * read at filing; FR-60 does not hide it, so the Administrator acts on the person.
 */
@Component
class JobPostReportable implements ReportableContent {

	private final JobPostQueries posts;

	private final ProductDirectory products;

	private final GitHubGate gitHub;

	private final JdbcClient jdbc;

	private final JobRoles roles;

	JobPostReportable(JobPostQueries posts, ProductDirectory products, GitHubGate gitHub, JdbcClient jdbc,
			JobRoles roles) {
		this.posts = posts;
		this.products = products;
		this.gitHub = gitHub;
		this.jdbc = jdbc;
		this.roles = roles;
	}

	@Override
	public Set<String> kinds() {
		return Set.of("JOB_POST", "PRODUCT", "RECRUITMENT_NOTE");
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
				.map(JobPostQueries.Post::job)
				.filter(job -> !job.customerId().equals(reader))
				.map(job -> new Reported(job.id(), job.productId(), job.customerId(), job.title()));
		}
		if ("RECRUITMENT_NOTE".equals(kind)) {
			return this.posts.find(id)
				.filter(post -> post.recruitment().on() && post.recruitment().note() != null)
				.filter(post -> readsTheNote(post.job().id(), caller))
				.map(post -> new Reported(post.job().id(), post.job().productId(), post.job().mentorId(),
						post.job().title(), post.recruitment().note()));
		}
		if (!"PRODUCT".equals(kind)) {
			return Optional.empty();
		}
		boolean posted = this.jdbc
			.sql("SELECT EXISTS (SELECT 1 FROM job.job j WHERE j.product_id = :product AND " + JobPostQueries.LISTED + ")")
			.param("product", id)
			.query(Boolean.class)
			.single();
		if (!posted || !this.products.hidden(Set.of(id)).isEmpty()) {
			return Optional.empty();
		}
		return this.products.find(id)
			.filter(product -> !product.customerId().equals(reader))
			.map(product -> new Reported(null, product.id(), product.customerId(), product.name()));
	}

	/** Whom MEN-3 shows the note to: a reader with no role in the Job, and a former member. */
	private boolean readsTheNote(UUID jobId, Caller caller) {
		JobRole role = this.roles.roleOf(jobId, caller);
		return JobRole.NONE.equals(role) || role.crewKind() == Relationship.FORMER;
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
