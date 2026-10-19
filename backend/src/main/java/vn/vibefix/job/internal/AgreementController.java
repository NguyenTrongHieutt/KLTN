package vn.vibefix.job.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.vibefix.identity.MentorGate;
import vn.vibefix.job.internal.Agreements.AccessLevel;
import vn.vibefix.job.internal.Agreements.Content;
import vn.vibefix.job.internal.Agreements.CriterionInput;
import vn.vibefix.job.internal.Agreements.Draft;
import vn.vibefix.job.internal.Agreements.Environment;
import vn.vibefix.job.internal.Agreements.ExternalAccessInput;
import vn.vibefix.job.internal.Agreements.MilestoneInput;
import vn.vibefix.job.internal.Agreements.Problem;
import vn.vibefix.job.internal.Agreements.SaveCommand;
import vn.vibefix.job.internal.Agreements.Version;
import vn.vibefix.job.internal.Agreements.VersionSummary;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;

/**
 * JOB-6 "Thỏa thuận" (FR-16, AD-29): the Job's Mentor reads, saves, and shares the draft; the Customer
 * reads the shared versions only, and finds nothing before the first share (SG-31). Payment shares
 * never appear (AD-41).
 */
@RestController
@Access(Gate.SIGNED_IN)
class AgreementController {

	record CriterionRequest(@NotNull UUID id, @NotNull @Size(max = 500) String statement,
			@NotNull @Size(max = 2000) String checkSteps) {
	}

	record MilestoneRequest(@NotNull UUID id, @NotNull @Size(max = 120) String title,
			@Nullable @Min(1000) @Max(1_000_000_000) Long priceVnd, @Nullable LocalDate dueOn,
			@Min(0) @Max(10) int revisionRounds, @NotNull @Size(max = Agreements.MAX_CRITERIA) List<@Valid CriterionRequest> criteria) {
	}

	record ExternalAccessRequest(@NotNull UUID id, @NotNull @Size(max = 120) String system,
			@NotNull @Size(max = 300) String purpose, @NotNull Environment environment, @NotNull AccessLevel accessLevel) {
	}

	/** "Lưu bản nháp": the whole draft at the revision the editor loaded (AD-23). */
	record SaveRequest(@NotNull Integer revision, @NotNull @Size(max = 4000) String scope,
			@Nullable @Min(1000) @Max(1_000_000_000) Long priceVnd,
			@NotNull @Size(max = Agreements.MAX_SKILLS) List<UUID> skillIds,
			@NotNull @Size(max = Agreements.MAX_MILESTONES) List<@Valid MilestoneRequest> milestones,
			@NotNull @Size(max = Agreements.MAX_EXTERNAL_ACCESS) List<@Valid ExternalAccessRequest> externalAccess) {
	}

	record ShareRequest(@NotNull Integer revision) {
	}

	/** The selected Application's quote, which the draft is compared with (FR-16). */
	record QuoteView(long priceVnd, int timelineDays) {
	}

	/** The Mentor's editor: the live draft and what keeps it from being shared. */
	record AgreementDraftView(int revision, Content content, List<Problem> problems, @Nullable QuoteView quote,
			List<VersionSummary> versions, JobState state) {
	}

	/**
	 * A shared version as the Customer reads it.
	 * @param crew the GitHub usernames of the Crew rows that are not released: never a share (FR-75)
	 * @param crewConfirmed FR-17's Crew condition holds; until then the Crew line reads as waiting
	 */
	record AgreementVersionView(int versionNo, Instant sharedAt, Content content,
			List<VersionSummary> versions, @Nullable QuoteView quote, List<String> crew, boolean crewConfirmed) {
	}

	private final Agreements agreements;

	private final Jobs jobs;

	private final MentorGate gate;

	private final CurrentCaller callers;

	private final JdbcClient jdbc;

	private final CrewService crews;

	AgreementController(Agreements agreements, Jobs jobs, MentorGate gate, CurrentCaller callers, JdbcClient jdbc,
			CrewService crews) {
		this.crews = crews;
		this.agreements = agreements;
		this.jobs = jobs;
		this.gate = gate;
		this.callers = callers;
		this.jdbc = jdbc;
	}

	@GetMapping("/api/v1/mentor/jobs/{jobId}/agreement")
	AgreementDraftView draft(@PathVariable UUID jobId) {
		Job job = mentorJob(mentor(), jobId);
		Draft draft = this.agreements.draft(job).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		return view(job, draft);
	}

	@PutMapping("/api/v1/mentor/jobs/{jobId}/agreement")
	AgreementDraftView save(@PathVariable UUID jobId, @Valid @RequestBody SaveRequest body) {
		UUID mentor = mentor();
		Draft draft = this.agreements.save(mentor, jobId, new SaveCommand(body.revision(), body.scope(), body.priceVnd(),
				body.skillIds(),
				body.milestones().stream().map(milestone -> new MilestoneInput(milestone.id(), milestone.title(),
						milestone.priceVnd(), milestone.dueOn(), milestone.revisionRounds(),
						milestone.criteria().stream()
							.map(criterion -> new CriterionInput(criterion.id(), criterion.statement(), criterion.checkSteps()))
							.toList())).toList(),
				body.externalAccess().stream().map(access -> new ExternalAccessInput(access.id(), access.system(),
						access.purpose(), access.environment(), access.accessLevel())).toList()));
		return view(mentorJob(mentor, jobId), draft);
	}

	/** "Gửi cho khách hàng" (FR-16). */
	@PostMapping("/api/v1/mentor/jobs/{jobId}/agreement/share")
	AgreementDraftView share(@PathVariable UUID jobId, @Valid @RequestBody ShareRequest body) {
		UUID mentor = mentor();
		this.agreements.share(mentor, jobId, body.revision());
		Job job = mentorJob(mentor, jobId);
		return view(job, this.agreements.draft(job).orElseThrow());
	}

	/** The Customer's Thỏa thuận tab: a shared version, the latest by default. */
	@GetMapping("/api/v1/jobs/{jobId}/agreement")
	AgreementVersionView customer(@PathVariable UUID jobId, @RequestParam(required = false) @Nullable Integer version) {
		Caller caller = this.callers.get();
		if (!caller.hasRole(Role.CUSTOMER)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		Job job = this.jobs.find(caller.requireAccountId(), jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Version shared = this.agreements.version(job.id(), version).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		List<UUID> running = this.crews.rows(job.id()).stream().filter(row -> !row.released()).map(CrewService.Row::studentId).toList();
		List<String> usernames = running.stream()
			.map(this.crews.cards(running)::get)
			.filter(card -> card != null && card.githubLogin() != null)
			.map(card -> card.githubLogin())
			.toList();
		return new AgreementVersionView(shared.versionNo(), shared.sharedAt(), shared.content(),
				this.agreements.versions(job.id()), quote(job.id()), usernames, this.crews.crewConfirmed(job.id()));
	}

	private AgreementDraftView view(Job job, Draft draft) {
		return new AgreementDraftView(draft.revision(), draft.content(), this.agreements.problems(draft.content()),
				quote(job.id()), this.agreements.versions(job.id()), job.state());
	}

	private @Nullable QuoteView quote(UUID jobId) {
		return this.jdbc.sql("""
				SELECT quote_price_vnd, quote_timeline_days FROM job.job
				WHERE id = :id AND quote_price_vnd IS NOT NULL AND quote_timeline_days IS NOT NULL
				""").param("id", jobId)
			.query((rs, n) -> new QuoteView(rs.getLong("quote_price_vnd"), rs.getInt("quote_timeline_days")))
			.optional()
			.orElse(null);
	}

	private Job mentorJob(UUID mentor, UUID jobId) {
		return this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE id = :id AND mentor_account_id = :mentor")
			.param("id", jobId)
			.param("mentor", mentor)
			.query(Jobs::job)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	/** The Mentor calling, unlocked; anyone else finds nothing. */
	private UUID mentor() {
		Caller caller = this.callers.get();
		if (!caller.hasRole(Role.MENTOR)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID mentor = caller.requireAccountId();
		this.gate.requireActiveAccount(mentor);
		return mentor;
	}

}
