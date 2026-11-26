package vn.vibefix.job.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import vn.vibefix.identity.ActiveAccount;
import vn.vibefix.job.Crews.Relationship;
import vn.vibefix.job.JobRole;
import vn.vibefix.job.JobRules;
import vn.vibefix.job.internal.AgreementReviews.Confirmed;
import vn.vibefix.job.internal.Agreements.AccessLevel;
import vn.vibefix.job.internal.Agreements.Content;
import vn.vibefix.job.internal.Agreements.Draft;
import vn.vibefix.job.internal.Agreements.Environment;
import vn.vibefix.job.internal.Agreements.Version;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * JOB-6 read-only for the Crew (FR-75, FR-81, AD-41, UX-DR125, Story 25.1b): a picked applicant and an active member
 * read the Agreement they sign for and work under. Until the Agreement is confirmed that is the latest version the
 * Mentor shared with the Customer; from then on, every confirmed version. Never the Mentor's live draft, an earlier
 * shared draft, a comment, the quote, or a Change Request in progress, and nothing of the Customer's approval but the
 * systems it marked as live.
 * <p>
 * Every record here is this endpoint's own, copied field by field from what an Agreement says: a field added later to
 * the Customer's or the Mentor's view, or to a stored version, reaches the Crew only when it is added here too.
 * <p>
 * The reader's screen says which version it shows ({@code agreement/read}); a reader who opened one is told when a
 * newer one becomes readable ({@link AgreementReads}).
 */
@RestController
@Access(Gate.SIGNED_IN)
class CrewAgreementController {

	record CrewAgreementCriterion(UUID id, String statement, String checkSteps) {
	}

	record CrewAgreementMilestone(UUID id, String title, @Nullable Long priceVnd, @Nullable LocalDate dueOn,
			int revisionRounds, List<CrewAgreementCriterion> criteria) {
	}

	record CrewAgreementAccess(UUID id, String system, String purpose, Environment environment, AccessLevel accessLevel) {
	}

	record CrewAgreementSkill(UUID id, String name) {
	}

	/** What the Agreement says, as the Crew reads it: the Milestones with their prices, never who is paid what. */
	record CrewAgreementContent(String scope, @Nullable Long priceVnd, boolean codeJob, boolean assessment,
			List<CrewAgreementSkill> skills, List<CrewAgreementMilestone> milestones, List<CrewAgreementAccess> externalAccess) {
	}

	/**
	 * One version the reader may open.
	 * @param confirmed a confirmed Agreement version; otherwise the draft the Mentor shared with the Customer
	 * @param versionNo the confirmed version's number, or the shared draft's
	 * @param at when it was confirmed, or shared
	 */
	record CrewAgreementOption(boolean confirmed, int versionNo, Instant at) {
	}

	/**
	 * @param versions the versions the reader may open, oldest first; empty before the first share
	 * @param shown the version {@code content} is, {@code null} before the first share
	 * @param liveAccessIds the External Access entries the Customer marked as live systems, for a confirmed version
	 * @param reworkDays the Job's copied {@code REWORK_DAYS}, for the note on due dates (FR-87)
	 * @param changedSinceRead the reader opened a version before, and a newer one than that is readable now: "Bản nháp
	 * thỏa thuận đã thay đổi từ lần bạn đọc trước." (UX-DR125)
	 * @param selfMentored a Self-mentored Job (FR-85): an unconfirmed version is the Customer's live draft, named by its
	 * revision, and nobody shared it
	 */
	record CrewAgreementView(String jobTitle, List<CrewAgreementOption> versions, @Nullable CrewAgreementOption shown,
			@Nullable CrewAgreementContent content, List<UUID> liveAccessIds, int reworkDays, boolean changedSinceRead,
			boolean selfMentored) {
	}

	/** The version the reader has in front of them, named as {@link CrewAgreementOption} names it. */
	record CrewAgreementReadRequest(@NotNull Boolean confirmed, @NotNull @Min(1) Integer versionNo) {
	}

	private final Agreements agreements;

	private final AgreementReviews reviews;

	private final JdbcClient jdbc;

	private final JobRoleReader roles;

	private final JobRules rules;

	private final ActiveAccount gate;

	private final CurrentCaller callers;

	private final AgreementReads reads;

	CrewAgreementController(Agreements agreements, AgreementReviews reviews, JdbcClient jdbc, JobRoleReader roles, JobRules rules,
			ActiveAccount gate, CurrentCaller callers, AgreementReads reads) {
		this.reads = reads;
		this.agreements = agreements;
		this.reviews = reviews;
		this.jdbc = jdbc;
		this.roles = roles;
		this.rules = rules;
		this.gate = gate;
		this.callers = callers;
	}

	/**
	 * The version asked for, or the newest the reader may open. A version is named by both parameters together; with
	 * neither, the newest is shown.
	 * @param version the number of the version to show
	 * @param confirmed whether {@code version} names a confirmed version or the shared draft
	 * @throws ApiException {@code NOT_FOUND} for anyone but a picked applicant or an active member of the Job, for a
	 * version the reader may not open, and for one parameter given without the other; {@code FORBIDDEN ACCOUNT_LOCKED}
	 * for such a reader whose account is locked
	 */
	@GetMapping("/api/v1/crew/jobs/{jobId}/agreement")
	CrewAgreementView crewAgreement(@PathVariable UUID jobId, @RequestParam(required = false) @Nullable Integer version,
			@RequestParam(required = false) @Nullable Boolean confirmed) {
		Job job = readerJob(jobId);
		String title = job.title();
		boolean selfMentored = job.selfMentored();
		List<CrewAgreementOption> options = options(jobId);
		int reworkDays = this.rules.value(jobId, DueDates.REWORK_RULE, DueDates.DEFAULT_REWORK_DAYS);
		boolean changed = !options.isEmpty() && this.reads.of(jobId, this.callers.get().requireAccountId())
			.filter(read -> mark(options.getLast()).newerThan(read))
			.isPresent();
		CrewAgreementOption shown;
		if (version == null && confirmed == null) {
			if (options.isEmpty()) {
				return new CrewAgreementView(title, List.of(), null, null, List.of(), reworkDays, false, selfMentored);
			}
			shown = options.getLast();
		}
		else if (version == null || confirmed == null) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		else {
			int number = version;
			boolean ofConfirmed = confirmed;
			shown = options.stream()
				.filter(option -> option.versionNo() == number && option.confirmed() == ofConfirmed)
				.findFirst()
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		}
		if (shown.confirmed()) {
			Confirmed found = this.reviews.confirmed(jobId, shown.versionNo())
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
			return new CrewAgreementView(title, options, shown, content(found.content()), found.liveAccessIds(), reworkDays,
					changed, selfMentored);
		}
		if (selfMentored) {
			// AD-47, R54: nobody shares a draft here, so the reader opens the Customer's live draft at the revision named. A
			// save between the two reads leaves that revision behind: the reader finds nothing and loads the draft again.
			int revision = shown.versionNo();
			Draft draft = this.agreements.draft(job)
				.filter(live -> live.revision() == revision)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
			return new CrewAgreementView(title, options, shown, content(draft.content()), List.of(), reworkDays, changed, true);
		}
		Version shared = this.agreements.version(jobId, shown.versionNo())
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		return new CrewAgreementView(title, options, shown, content(shared.content()), List.of(), reworkDays, changed, false);
	}

	/**
	 * The reader has this version in front of them (Story 25.1b): what they are later told about is anything newer than
	 * the newest version they opened. Opening an older version changes nothing.
	 * @throws ApiException {@code NOT_FOUND} for anyone but a picked applicant or an active member of the Job, and for a
	 * version the reader may not open; {@code FORBIDDEN ACCOUNT_LOCKED} for such a reader whose account is locked;
	 * {@code VALIDATION} for a body that names no version
	 */
	@PostMapping("/api/v1/crew/jobs/{jobId}/agreement/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void crewAgreementRead(@PathVariable UUID jobId, @Valid @RequestBody CrewAgreementReadRequest body) {
		readerJob(jobId);
		AgreementReads.Mark opened = new AgreementReads.Mark(body.confirmed(), body.versionNo());
		// The reader and the version are checked again with the write, under the Job row (AgreementReads.opened).
		if (!this.reads.opened(jobId, this.callers.get().requireAccountId(), opened)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
	}

	private static AgreementReads.Mark mark(CrewAgreementOption option) {
		return new AgreementReads.Mark(option.confirmed(), option.versionNo());
	}

	/** The versions the reader may open, as {@link AgreementReads#readable} decides them. */
	private List<CrewAgreementOption> options(UUID jobId) {
		return this.reads.readable(jobId)
			.stream()
			.map(version -> new CrewAgreementOption(version.mark().confirmed(), version.mark().versionNo(), version.at()))
			.toList();
	}

	/**
	 * The Job of a picked applicant or an active member calling, by their role in it (AD-41, AD-43), unlocked. A former
	 * member, an applicant not picked, the Job's Customer, its Mentor and anyone else find nothing here.
	 */
	private Job readerJob(UUID jobId) {
		Caller caller = this.callers.get();
		JobRole role = this.roles.roleOf(jobId, caller);
		Relationship kind = role.crewKind();
		if (kind != Relationship.ASSIGNED && kind != Relationship.ACTIVE) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		this.gate.requireActiveAccount(caller.requireAccountId());
		return this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE id = :id AND " + Jobs.LIVE)
			.param("id", jobId)
			.query(Jobs::job)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private static CrewAgreementContent content(Content content) {
		List<CrewAgreementMilestone> milestones = new ArrayList<>();
		content.milestones()
			.forEach(milestone -> milestones.add(new CrewAgreementMilestone(milestone.id(), milestone.title(),
					milestone.priceVnd(), milestone.dueOn(), milestone.revisionRounds(),
					milestone.criteria()
						.stream()
						.map(criterion -> new CrewAgreementCriterion(criterion.id(), criterion.statement(), criterion.checkSteps()))
						.toList())));
		return new CrewAgreementContent(content.scope(), content.priceVnd(), content.codeJob(), content.assessment(),
				content.skills().stream().map(skill -> new CrewAgreementSkill(skill.id(), skill.name())).toList(), milestones,
				content.externalAccess()
					.stream()
					.map(access -> new CrewAgreementAccess(access.id(), access.system(), access.purpose(), access.environment(),
							access.accessLevel()))
					.toList());
	}

}
