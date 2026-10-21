package vn.vibefix.repo.internal;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.github.PlatformRepositories.Invited;
import vn.vibefix.github.PlatformRepositories.PlatformRepository;
import vn.vibefix.identity.Administrators;
import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorGate;
import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.identity.StudentDirectory.StudentCard;
import vn.vibefix.job.Crews;
import vn.vibefix.job.Crews.CrewMember;
import vn.vibefix.job.JobActivation;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.operations.ExternalOperations;
import vn.vibefix.operations.OperationCompleted;
import vn.vibefix.operations.OperationRequest;
import vn.vibefix.operations.OperationStatus;
import vn.vibefix.repo.internal.JobSetups.Setup;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * J7's Invitations and Verify stages (FR-18, FR-75, AD-11, AD-28, NFR-6), as {@code repo.job_grant}
 * rows: who is invited to a Job's Working Repository, who accepted, and who was raised to write. Setup's
 * resolving transaction grants the Mentor and every active Crew member whose account is unlocked; each
 * invitation is a consequence operation with read permission. Once every counted grant is accepted with
 * two-factor authentication on, and none has drifted, {@code repo} reserves {@code JOB_VERIFY}; Verify's
 * resolving transaction applies J7 through {@code job}. A member a Crew change adds to an Active Job is
 * granted the same way and raised on acceptance without a state change. Every step is audited per person
 * and repository.
 */
@Component
class JobGrants {

	static final String INVITE = "repo.grant_invite";

	static final String RAISE = "repo.grant_raise";

	static final String VERIFY = "repo.job_verify";

	static final String SENT = "repo.github_invitation_sent";

	static final String ACCEPT_TODO = "repo.accept_github_invitation";

	static final String TWO_FACTOR = "repo.two_factor_required";

	static final String DRIFT = "repo.member_drift";

	static final String EXHAUSTED = "repo.invitation_renewals_exhausted";

	/** FR-75: a Crew change member's invitation is renewed at most this often. */
	static final int CREW_CHANGE_RENEWALS = 3;

	/** One person's access to one Working Repository. */
	record Grant(UUID id, UUID jobId, UUID productId, UUID accountId, String role, String login, long repositoryId,
			String repositoryName, boolean crewChange, String status, boolean twoFactorMissing, @Nullable Long invitationId,
			@Nullable Instant expiresAt, int renewals, @Nullable Instant acceptedAt, @Nullable Instant writeAt) {

		PlatformRepository repository() {
			return new PlatformRepository(this.repositoryId, this.repositoryName.substring(this.repositoryName.indexOf('/') + 1),
					this.repositoryName);
		}

		Audience audience() {
			return "MENTOR".equals(this.role) ? Audience.MENTOR : Audience.CREW_ACTIVE;
		}

		/** Counted toward Verify: accepted, two-factor authentication on. */
		boolean ready() {
			return ("ACCEPTED".equals(this.status) || "WRITE".equals(this.status)) && !this.twoFactorMissing;
		}

	}

	/** A person the Job counts (AD-41): the Mentor, or an active Crew member, with an unlocked account. */
	record Person(UUID accountId, String role, String login) {
	}

	private static final String COLUMNS = """
			id, job_id, product_id, account_id, role, github_login, repository_github_id, repository_name, crew_change, status,
			two_factor_missing, invitation_id, expires_at, renewals, accepted_at, write_at
			""";

	private final JdbcClient jdbc;

	private final JobBusy busy;

	private final JobActivation activation;

	private final Crews crews;

	private final MentorDirectory mentors;

	private final MentorGate gate;

	private final StudentDirectory students;

	private final ExternalOperations operations;

	private final Notifications notifications;

	private final Todos todos;

	private final Deadlines deadlines;

	private final Administrators administrators;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	JobGrants(JdbcClient jdbc, JobBusy busy, JobActivation activation, Crews crews, MentorDirectory mentors, MentorGate gate,
			StudentDirectory students, ExternalOperations operations, Notifications notifications, Todos todos,
			Deadlines deadlines, Administrators administrators, AuditLog auditLog, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.busy = busy;
		this.activation = activation;
		this.crews = crews;
		this.mentors = mentors;
		this.gate = gate;
		this.students = students;
		this.operations = operations;
		this.notifications = notifications;
		this.todos = todos;
		this.deadlines = deadlines;
		this.administrators = administrators;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	/** Setup's resolving transaction: everyone the Job counts is granted on the Working Repository. */
	void grantAll(Setup setup, PlatformRepository working) {
		BusyJob job = this.busy.job(setup.jobId()).orElseThrow();
		for (Person person : counted(job)) {
			grant(job, person, working, false);
		}
	}

	/** One person's grant and its first invitation, unless they have one on this repository. */
	@Transactional
	void grant(BusyJob job, Person person, PlatformRepository working, boolean crewChange) {
		UUID id = this.ids.newId();
		int inserted = this.jdbc.sql("""
				INSERT INTO repo.job_grant (id, job_id, product_id, account_id, role, github_login, repository_github_id,
				    repository_name, crew_change, status, created_at)
				VALUES (:id, :job, :product, :account, :role, :login, :repository, :name, :change, 'INVITING', :now)
				ON CONFLICT (job_id, account_id, repository_github_id) DO NOTHING
				""")
			.param("id", id).param("job", job.jobId()).param("product", job.productId()).param("account", person.accountId())
			.param("role", person.role()).param("login", person.login()).param("repository", working.id())
			.param("name", working.fullName()).param("change", crewChange).param("now", Timestamp.from(this.clock.instant()))
			.update();
		if (inserted == 1) {
			requestInvite(id, 0);
		}
	}

	/** The Mentor and the active Crew, each with an unlocked account and a GitHub username. */
	List<Person> counted(BusyJob job) {
		List<Person> people = new ArrayList<>();
		if (job.mentorId() != null && unlocked(job.mentorId())) {
			this.mentors.cards(List.of(job.mentorId()))
				.stream()
				.filter(card -> card.githubLogin() != null)
				.findFirst()
				.ifPresent(card -> people.add(new Person(card.accountId(), "MENTOR", card.githubLogin())));
		}
		List<UUID> crew = this.crews.crewOf(job.jobId(), Crews.Selection.ACTIVE).stream().map(CrewMember::studentId).toList();
		if (!crew.isEmpty()) {
			for (StudentCard card : this.students.cards(crew)) {
				if (card.available() && card.githubLogin() != null) {
					people.add(new Person(card.accountId(), "CREW", card.githubLogin()));
				}
			}
		}
		return people;
	}

	private boolean unlocked(UUID accountId) {
		try {
			this.gate.requireActiveAccount(accountId);
			return true;
		}
		catch (ApiException locked) {
			return false;
		}
	}

	private void requestInvite(UUID grantId, int round) {
		this.operations.request(new OperationRequest(INVITE, key(grantId + ":invite:" + round),
				Map.of("grantId", grantId.toString(), "round", round), null, null, 0));
	}

	/**
	 * The invitation was sent: the person hears of it, gets the to-do, and the renewal is scheduled. A grant
	 * revoked meanwhile stays revoking; the platform sweep reads a late invitation back as drift.
	 */
	@Transactional
	void invited(Grant grant, Invited invited) {
		Instant now = this.clock.instant();
		int updated = this.jdbc.sql("""
				UPDATE repo.job_grant SET status = 'INVITED', invitation_id = :invitation, invited_at = :now, expires_at = :expires
				WHERE id = :id AND status = 'INVITING'
				""").param("invitation", invited.invitationId()).param("now", Timestamp.from(now))
			.param("expires", Timestamp.from(invited.expiresAt())).param("id", grant.id()).update();
		if (updated == 0) {
			return;
		}
		audit("repo.grant_invited", grant);
		String title = title(grant.jobId());
		this.notifications.send(new NotificationRequest(SENT, key(grant.id() + ":sent:" + grant.renewals()),
				List.of(new Recipient(grant.accountId(), grant.audience())), Map.of("job", title), "/cong-viec/" + grant.jobId(),
				grant.jobId(), null));
		this.todos.open(new TodoRequest(ACCEPT_TODO, grant.accountId(), grant.audience(), grant.id(), Map.of("job", title),
				"/cong-viec/" + grant.jobId(), invited.expiresAt(), grant.jobId()));
		this.deadlines.schedule(new DeadlineRequest(InvitationRenewal.KEY, grant.id(), String.valueOf(grant.renewals()),
				grant.jobId(), invited.expiresAt()));
	}

	/** GitHub added an organization member at once (AD-11): not an acceptance; Administrators remove them. */
	@Transactional
	void drifted(Grant grant) {
		int updated = this.jdbc.sql("UPDATE repo.job_grant SET status = 'MEMBER_DRIFT' WHERE id = :id AND status = 'INVITING'")
			.param("id", grant.id())
			.update();
		if (updated == 0) {
			return;
		}
		audit("repo.grant_member_drift", grant);
		List<Recipient> admins = this.administrators.activeIds().stream().map(id -> new Recipient(id, Audience.ADMIN)).toList();
		if (!admins.isEmpty()) {
			this.notifications.send(new NotificationRequest(DRIFT, key(grant.id() + ":drift"), admins,
					Map.of("login", grant.login(), "job", title(grant.jobId())), "/quan-tri", null, null));
		}
	}

	/** GitHub shows the person as a collaborator: their invitation was accepted. */
	@Transactional
	void accepted(Grant grant) {
		int updated = this.jdbc.sql("""
				UPDATE repo.job_grant SET status = 'ACCEPTED', accepted_at = :now WHERE id = :id AND status = 'INVITED'
				""").param("now", Timestamp.from(this.clock.instant())).param("id", grant.id()).update();
		if (updated == 1) {
			audit("repo.grant_accepted", grant);
			this.todos.close(ACCEPT_TODO, grant.accountId(), grant.id());
		}
	}

	/** Two-factor authentication read back; the person hears once when it is missing. */
	@Transactional
	void twoFactor(Grant grant, boolean missing) {
		if (grant.twoFactorMissing() == missing) {
			return;
		}
		this.jdbc.sql("UPDATE repo.job_grant SET two_factor_missing = :missing WHERE id = :id")
			.param("missing", missing)
			.param("id", grant.id())
			.update();
		if (missing) {
			audit("repo.grant_two_factor_missing", grant);
			this.notifications.send(new NotificationRequest(TWO_FACTOR, key(grant.id() + ":two-factor"),
					List.of(new Recipient(grant.accountId(), grant.audience())), Map.of("job", title(grant.jobId())),
					"/cong-viec/" + grant.jobId(), grant.jobId(), null));
		}
	}

	/** A new invitation for a pending or drifted grant, again with read. */
	@Transactional
	void reinvite(Grant grant) {
		int round = grant.renewals() + 1;
		int updated = this.jdbc.sql("""
				UPDATE repo.job_grant SET status = 'INVITING', invitation_id = NULL, expires_at = NULL, renewals = :round
				WHERE id = :id AND status IN ('INVITED', 'MEMBER_DRIFT') AND renewals = :previous
				""").param("round", round).param("id", grant.id()).param("previous", grant.renewals()).update();
		if (updated == 1) {
			audit("repo.grant_renewed", grant);
			requestInvite(grant.id(), round);
		}
	}

	/** A Crew change member's renewals ran out (FR-75): the Mentor hears, and Administrators are alerted. */
	@Transactional
	void exhausted(Grant grant) {
		BusyJob job = this.busy.job(grant.jobId()).orElseThrow();
		List<Recipient> recipients = new ArrayList<>();
		if (job.mentorId() != null) {
			recipients.add(new Recipient(job.mentorId(), Audience.MENTOR));
		}
		this.administrators.activeIds().forEach(id -> recipients.add(new Recipient(id, Audience.ADMIN)));
		audit("repo.grant_renewals_exhausted", grant);
		this.notifications.send(new NotificationRequest(EXHAUSTED, key(grant.id() + ":exhausted"), recipients,
				Map.of("login", grant.login(), "job", job.title()), "/cong-viec/" + grant.jobId(), grant.jobId(), null));
	}

	/** A Crew change member accepted on an Active Job (FR-75): the raise is a consequence, not busy. */
	@Transactional
	void requestRaise(Grant grant) {
		this.operations.request(new OperationRequest(RAISE, key(grant.id() + ":raise"), Map.of("grantId", grant.id().toString()),
				null, null, 0));
	}

	/** Raised in place and read back as write. */
	@Transactional
	void wrote(Grant grant) {
		int updated = this.jdbc.sql("""
				UPDATE repo.job_grant SET status = 'WRITE', write_at = :now WHERE id = :id AND status = 'ACCEPTED'
				""").param("now", Timestamp.from(this.clock.instant())).param("id", grant.id()).update();
		if (updated == 1) {
			audit("repo.grant_raised", grant);
		}
	}

	/**
	 * Verify starts once every counted grant is ready and none drifted, on a Starting Job whose Setup is done
	 * (AD-11): {@code JOB_VERIFY} is reserved and the operation requested. A busy Job waits for the next look.
	 * @return whether Verify was requested now
	 */
	@Transactional
	boolean maybeVerify(UUID jobId, Setup setup) {
		BusyJob job = this.busy.job(jobId).orElse(null);
		if (job == null || !"STARTING".equals(job.state()) || job.busy().isPresent() || job.frozen()
				|| !"DONE".equals(setup.status())) {
			return false;
		}
		List<Grant> grants = ofJob(jobId);
		if (grants.stream().anyMatch(grant -> "MEMBER_DRIFT".equals(grant.status()))) {
			return false;
		}
		List<Person> people = counted(job);
		boolean ready = !people.isEmpty() && people.stream()
			.allMatch(person -> grants.stream().anyMatch(grant -> grant.accountId().equals(person.accountId()) && grant.ready()));
		if (!ready) {
			return false;
		}
		UUID verify = this.ids.newId();
		String operationKey = VERIFY + ":" + verify;
		this.busy.reserve(jobId, JobBusy.Purpose.JOB_VERIFY, null, job.version(), operationKey);
		this.operations.request(new OperationRequest(VERIFY, verify, Map.of("jobId", jobId.toString()), null, null, 0));
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "repo.verify_started", AuditObject.of("job.job", jobId))
			.forJob(jobId));
		return true;
	}

	/** Verify's resolving transaction: every counted grant reads back as write, so J7 applies (AD-5). */
	@Transactional
	boolean verified(UUID jobId, String operationKey) {
		boolean started = this.activation.activate(jobId, operationKey);
		this.auditLog.record(AuditEntry.of(AuditActor.system(), started ? "repo.verify_done" : "repo.verify_moot",
				AuditObject.of("job.job", jobId)).forJob(jobId));
		return started;
	}

	/** Verify ended without success: its resolving transaction releases the marker (AD-28). */
	@ApplicationModuleListener
	void on(OperationCompleted completed) {
		if (VERIFY.equals(completed.type()) && completed.status() != OperationStatus.SUCCEEDED) {
			this.jdbc.sql("SELECT id FROM job.job WHERE busy_operation_key = :key")
				.param("key", completed.key())
				.query(UUID.class)
				.optional()
				.ifPresent(jobId -> this.busy.release(jobId, completed.key()));
		}
	}

	Optional<Grant> find(UUID grantId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM repo.job_grant WHERE id = :id")
			.param("id", grantId)
			.query(JobGrants::grant)
			.optional();
	}

	List<Grant> ofJob(UUID jobId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM repo.job_grant WHERE job_id = :job ORDER BY role DESC, created_at, id")
			.param("job", jobId)
			.query(JobGrants::grant)
			.list();
	}

	/** Jobs with a grant still to settle, for the sweep. */
	List<UUID> unsettledJobs() {
		return this.jdbc.sql("SELECT DISTINCT job_id FROM repo.job_grant WHERE status IN ('INVITED', 'MEMBER_DRIFT', 'ACCEPTED')")
			.query(UUID.class)
			.list();
	}

	/** The Jobs granted on {@code repositoryId}, for a webhook hint. */
	List<UUID> jobsOf(long repositoryId) {
		return this.jdbc.sql("SELECT DISTINCT job_id FROM repo.job_grant WHERE repository_github_id = :repository")
			.param("repository", repositoryId)
			.query(UUID.class)
			.list();
	}

	private String title(UUID jobId) {
		return this.busy.job(jobId).map(BusyJob::title).orElse("");
	}

	private void audit(String action, Grant grant) {
		this.auditLog.record(AuditEntry.of(AuditActor.system(), action, AuditObject.of("repo.job_grant", grant.id()),
				AuditObject.of("identity.account", grant.accountId())).forJob(grant.jobId()));
	}

	private static UUID key(String text) {
		return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));
	}

	private static Grant grant(ResultSet rs, int n) throws SQLException {
		Timestamp expires = rs.getTimestamp("expires_at");
		Timestamp accepted = rs.getTimestamp("accepted_at");
		Timestamp write = rs.getTimestamp("write_at");
		return new Grant(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
				rs.getObject("product_id", UUID.class), rs.getObject("account_id", UUID.class), rs.getString("role"),
				rs.getString("github_login"), rs.getLong("repository_github_id"), rs.getString("repository_name"),
				rs.getBoolean("crew_change"), rs.getString("status"), rs.getBoolean("two_factor_missing"),
				rs.getObject("invitation_id", Long.class), expires == null ? null : expires.toInstant(), rs.getInt("renewals"),
				accepted == null ? null : accepted.toInstant(), write == null ? null : write.toInstant());
	}

}
