package vn.vibefix.chat.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import vn.vibefix.chat.internal.Chats.Message;
import vn.vibefix.chat.internal.Chats.Window;
import vn.vibefix.job.Crews;
import vn.vibefix.job.Crews.CrewMember;
import vn.vibefix.job.Crews.Selection;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.job.JobRole;
import vn.vibefix.job.JobRoles;
import vn.vibefix.shared.AdminReadScope;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.ErrorCode;

/**
 * Who reads and writes a Chat (FR-46, FR-77, AD-20, AD-41), by the role in the Job that {@code job.roleOf}
 * reads (AD-43):
 * <ul>
 * <li>the Job Chat: its Customer and its Mentor;</li>
 * <li>the Crew Chat: its Mentor and every {@code ACTIVE} Crew member; an {@code INACTIVE} or
 * {@code FORMER} member only reads, and only the messages inside their windows;</li>
 * <li>an Administrator only under a grant naming the Chat, or for one message the grant of an Abuse Report
 * naming it (FR-69), only reading, each read audited (AD-8).</li>
 * </ul>
 * Everyone else, an {@code ASSIGNED} Student and the Customer of the Crew Chat included, finds nothing
 * (SG-31). A closed Chat (J9, J10) is read, never written.
 */
@Component
class ChatAccess {

	/**
	 * The caller and the Chat they reached.
	 * @param participant a person of the Chat; {@code false} for a granted Administrator
	 * @param readOnly the Chat is closed, or the caller only reads it
	 * @param windows the spans whose messages the caller sees, or {@code null} for all of them
	 * @param customerId the Job Chat's Customer; {@code null} for the Crew Chat
	 * @param crew the Students ever {@code ACTIVE} on the Job, for the Crew Chat's names and grants
	 */
	record Viewer(Chat chat, UUID accountId, boolean participant, boolean readOnly, @Nullable List<Window> windows,
			String jobTitle, UUID mentorId, @Nullable UUID customerId, List<UUID> crew) {

		Viewer {
			windows = windows == null ? null : List.copyOf(windows);
			crew = List.copyOf(crew);
		}

		/** Whether the caller sees a message sent at {@code at}. */
		boolean sees(Instant at) {
			return this.windows == null
					|| this.windows.stream().anyMatch(window -> !at.isBefore(window.from()) && !at.isAfter(window.to()));
		}

		/** Everyone whose content the Chat holds, for a grant naming an account. */
		List<UUID> people() {
			List<UUID> people = new ArrayList<>();
			people.add(this.mentorId);
			if (this.customerId != null) {
				people.add(this.customerId);
			}
			people.addAll(this.crew);
			return people;
		}

	}

	private final Chats chats;

	private final JobPosts posts;

	private final Crews crews;

	private final ChatGrants grants;

	private final AuditLog auditLog;

	private final Clock clock;

	private final JobRoles roles;

	ChatAccess(Chats chats, JobPosts posts, Crews crews, ChatGrants grants, AuditLog auditLog, Clock clock,
			JobRoles roles) {
		this.roles = roles;
		this.chats = chats;
		this.posts = posts;
		this.crews = crews;
		this.grants = grants;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/** A person of the Job's Chat of {@code kind}, writing or not. */
	@Transactional(readOnly = true)
	Viewer participant(UUID jobId, Kind kind, Caller caller) {
		Viewer viewer = viewer(jobId, kind, caller);
		if (viewer == null) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return viewer;
	}

	/** A person of the Crew Chat {@code chatId}. */
	@Transactional(readOnly = true)
	Viewer crewParticipant(UUID chatId, Caller caller) {
		return participant(crewJob(chatId), Kind.CREW, caller);
	}

	/** A person, or an Administrator a grant permits, whose read this audits. */
	@Transactional
	Viewer reader(UUID jobId, Kind kind, Caller caller) {
		Viewer viewer = viewer(jobId, kind, caller);
		if (viewer != null) {
			return viewer;
		}
		if (!caller.isAdministrator()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID admin = caller.requireAccountId();
		Viewer granted = granted(jobId, kind, admin);
		Chat chat = granted.chat();
		AdminReadScope scope = kind == Kind.JOB ? AdminReadScope.JOB_CHAT : AdminReadScope.CREW_CHAT;
		if (!this.grants.permits(admin, jobId, scope, chat.id(), granted.people(), this.clock.instant())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		this.auditLog.record(AuditEntry.of(AuditActor.user(admin), "chat.admin_read", AuditObject.of("chat.chat", chat.id()))
			.forJob(jobId));
		return granted;
	}

	/** A reader of the Crew Chat {@code chatId}. */
	@Transactional
	Viewer crewReader(UUID chatId, Caller caller) {
		return reader(crewJob(chatId), Kind.CREW, caller);
	}

	/** One message and who reads it. */
	record MessageRead(Viewer viewer, Message message) {
	}

	/**
	 * One message: for a person of its Chat who sees it, or an Administrator whose grant covers it (an Abuse
	 * Report's grant names the message itself), whose read this audits.
	 */
	@Transactional
	MessageRead messageReader(UUID messageId, Caller caller) {
		Message message = this.chats.message(messageId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Chat chat = this.chats.byId(message.chatId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Viewer viewer = viewer(chat.jobId(), chat.kind(), caller);
		if (viewer != null) {
			if (!viewer.sees(message.sentAt())) {
				throw new ApiException(ErrorCode.NOT_FOUND);
			}
			return new MessageRead(viewer, message);
		}
		if (!caller.isAdministrator()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID admin = caller.requireAccountId();
		Viewer granted = granted(chat.jobId(), chat.kind(), admin);
		AdminReadScope scope = chat.kind() == Kind.JOB ? AdminReadScope.JOB_CHAT : AdminReadScope.CREW_CHAT;
		if (!this.grants.permits(admin, chat.jobId(), scope, messageId, granted.people(), this.clock.instant())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		this.auditLog.record(AuditEntry.of(AuditActor.user(admin), "chat.admin_read", AuditObject.of("chat.message", messageId))
			.forJob(chat.jobId()));
		return new MessageRead(granted, message);
	}

	/** A person of the Chat who sees the message, for an Abuse Report (FR-69); never an Administrator. */
	@Transactional(readOnly = true)
	Optional<MessageRead> seenBy(UUID messageId, Caller caller) {
		Message message = this.chats.message(messageId).orElse(null);
		Chat chat = message == null ? null : this.chats.byId(message.chatId()).orElse(null);
		if (chat == null) {
			return Optional.empty();
		}
		Viewer viewer = viewer(chat.jobId(), chat.kind(), caller);
		return viewer != null && viewer.sees(message.sentAt()) ? Optional.of(new MessageRead(viewer, message))
				: Optional.empty();
	}

	/** The viewer, when they may write. */
	static Viewer writing(Viewer viewer) {
		if (viewer.readOnly()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, ChatReason.CHAT_READ_ONLY);
		}
		return viewer;
	}

	/** The read-only viewer an Administrator reads through, before any grant is checked. */
	private Viewer granted(UUID jobId, Kind kind, UUID admin) {
		JobPostState post = this.posts.post(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Chat chat = this.chats.find(jobId, kind).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (post.mentorId() == null) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return new Viewer(chat, admin, false, true, null, post.title(), post.mentorId(),
				kind == Kind.JOB ? post.customerId() : null, kind == Kind.CREW ? crewOf(jobId) : List.of());
	}

	private UUID crewJob(UUID chatId) {
		return this.chats.byId(chatId)
			.filter(chat -> chat.kind() == Kind.CREW)
			.map(Chat::jobId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private @Nullable Viewer viewer(UUID jobId, Kind kind, Caller caller) {
		JobRole role = this.roles.roleOf(jobId, caller);
		if (JobRole.NONE.equals(role)) {
			return null;
		}
		UUID account = caller.requireAccountId();
		JobPostState post = this.posts.post(jobId).orElse(null);
		if (post == null || post.mentorId() == null) {
			return null;
		}
		Chat chat = this.chats.find(jobId, kind).orElse(null);
		if (chat == null) {
			return null;
		}
		if (kind == Kind.JOB) {
			return role.isCustomer() || role.isMentor() ? new Viewer(chat, account, true, chat.closed(), null,
					post.title(), post.mentorId(), post.customerId(), List.of()) : null;
		}
		if (role.isMentor()) {
			return new Viewer(chat, account, true, chat.closed(), null, post.title(), post.mentorId(), null, crewOf(jobId));
		}
		return switch (role.crewKind()) {
			case ACTIVE -> new Viewer(chat, account, true, chat.closed(), null, post.title(), post.mentorId(), null,
					crewOf(jobId));
			case INACTIVE, FORMER -> {
				List<Window> windows = windows(jobId, account);
				yield windows.isEmpty() ? null
						: new Viewer(chat, account, true, true, windows, post.title(), post.mentorId(), null, crewOf(jobId));
			}
			default -> null;
		};
	}

	/** Each of the Student's rows on the Job that was ever {@code ACTIVE}, when both bounds are known. */
	private List<Window> windows(UUID jobId, UUID student) {
		return this.crews.crewOf(jobId, Selection.EVER_ACTIVE)
			.stream()
			.filter(member -> member.studentId().equals(student) && member.signedAt() != null && member.windowEnd() != null)
			.map(member -> new Window(member.signedAt(), member.windowEnd()))
			.toList();
	}

	private List<UUID> crewOf(UUID jobId) {
		return this.crews.crewOf(jobId, Selection.EVER_ACTIVE).stream().map(CrewMember::studentId).distinct().toList();
	}

}
