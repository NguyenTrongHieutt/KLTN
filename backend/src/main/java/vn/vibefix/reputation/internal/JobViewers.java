package vn.vibefix.reputation.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.job.JobRole;
import vn.vibefix.job.JobRoles;
import vn.vibefix.reputation.internal.Ratings.Viewer;
import vn.vibefix.reputation.internal.Ratings.ViewerRole;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.ErrorCode;

/**
 * Who may read or act on a Job's post-Job duties (AD-19, AD-41, AD-43), by their role in the Job: the Customer,
 * the Mentor (revoked or not, while unlocked), or a Crew member who was ever active, once the Job is Active or
 * later; everyone else finds nothing.
 */
@Component
class JobViewers {

	private static final List<String> STATES = List.of("ACTIVE", "CLOSING", "CLOSED", "CANCELLED");

	private final JobPosts posts;

	private final Ratings ratings;

	private final JobRoles roles;

	JobViewers(JobPosts posts, Ratings ratings, JobRoles roles) {
		this.posts = posts;
		this.ratings = ratings;
		this.roles = roles;
	}

	/** @throws ApiException {@code NOT_FOUND} for anyone else */
	@Transactional(readOnly = true)
	Viewer viewer(UUID jobId, Caller caller) {
		JobPostState post = this.posts.post(jobId)
			.filter(found -> found.mentorId() != null)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		UUID account = caller.accountId();
		if (account == null || !STATES.contains(post.state())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		JobRole role = this.roles.roleOf(jobId, caller);
		if (role.isCustomer()) {
			return new Viewer(jobId, account, ViewerRole.CUSTOMER);
		}
		if (role.isMentor()) {
			return new Viewer(jobId, account, ViewerRole.MENTOR);
		}
		if (role.isCrew() && this.ratings.crew(jobId).contains(account)) {
			return new Viewer(jobId, account, ViewerRole.CREW);
		}
		throw new ApiException(ErrorCode.NOT_FOUND);
	}

}
