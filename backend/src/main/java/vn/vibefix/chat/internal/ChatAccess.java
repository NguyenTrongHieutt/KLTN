package vn.vibefix.chat.internal;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.chat.internal.Chats.Chat;
import vn.vibefix.chat.internal.Chats.Kind;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.shared.AdminReadScope;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Role;

/**
 * Who reads and writes a Job Chat (FR-46, AD-20): its Customer and its Mentor, read from {@code job}
 * ({@code job.mentorOf}); a Crew member, any Student, any other account, and an Administrator without
 * a grant find nothing (SG-31). A granted Administrator only reads, and each read is audited (AD-8).
 */
@Component
class ChatAccess {

	/**
	 * The caller and the Chat they reached.
	 * @param participant the Customer or the Mentor; {@code false} for a granted Administrator
	 */
	record Viewer(Chat chat, UUID accountId, boolean participant, UUID customerId, UUID mentorId) {
	}

	private final Chats chats;

	private final JobPosts posts;

	private final ChatGrants grants;

	private final AuditLog auditLog;

	private final Clock clock;

	ChatAccess(Chats chats, JobPosts posts, ChatGrants grants, AuditLog auditLog, Clock clock) {
		this.chats = chats;
		this.posts = posts;
		this.grants = grants;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/** A participant of the Job's Chat, who may write. */
	@Transactional(readOnly = true)
	Viewer participant(UUID jobId, Caller caller) {
		Viewer viewer = viewer(jobId, caller);
		if (viewer == null) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return viewer;
	}

	/** A participant, or an Administrator a grant permits, whose read this audits. */
	@Transactional
	Viewer reader(UUID jobId, Caller caller) {
		Viewer viewer = viewer(jobId, caller);
		if (viewer != null) {
			return viewer;
		}
		if (!caller.hasRole(Role.ADMINISTRATOR)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID admin = caller.requireAccountId();
		JobPostState post = this.posts.post(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Chat chat = this.chats.find(jobId, Kind.JOB).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (post.mentorId() == null || !this.grants.permits(admin, jobId, AdminReadScope.JOB_CHAT, chat.id(),
				List.of(post.customerId(), post.mentorId()), this.clock.instant())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		this.auditLog.record(AuditEntry.of(AuditActor.user(admin), "chat.admin_read", AuditObject.of("chat.chat", chat.id()))
			.forJob(jobId));
		return new Viewer(chat, admin, false, post.customerId(), post.mentorId());
	}

	private @Nullable Viewer viewer(UUID jobId, Caller caller) {
		if (!caller.hasRole(Role.CUSTOMER) && !caller.hasRole(Role.MENTOR)) {
			return null;
		}
		UUID account = caller.requireAccountId();
		JobPostState post = this.posts.post(jobId).orElse(null);
		if (post == null || post.mentorId() == null) {
			return null;
		}
		boolean customer = caller.hasRole(Role.CUSTOMER) && post.customerId().equals(account);
		boolean mentor = caller.hasRole(Role.MENTOR) && post.mentorId().equals(account);
		if (!customer && !mentor) {
			return null;
		}
		return this.chats.find(jobId, Kind.JOB)
			.map(chat -> new Viewer(chat, account, true, post.customerId(), post.mentorId()))
			.orElse(null);
	}

}
