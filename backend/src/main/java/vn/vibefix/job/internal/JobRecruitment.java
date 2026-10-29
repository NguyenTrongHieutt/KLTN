package vn.vibefix.job.internal;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.catalog.Catalog;
import vn.vibefix.catalog.Skill;
import vn.vibefix.job.CrewRecruitment;
import vn.vibefix.job.CrewRecruitmentClosed;
import vn.vibefix.job.CrewRecruitmentOpened;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;

/**
 * The Mentor's Crew recruitment (FR-81, AD-3, AD-41, R2, Story 21.4): only the Job's Mentor, from J4 until J8
 * (Agreement, Starting or Active, a Held Milestone included), turns "Nhận đơn xin vào nhóm" on with the open slots (1
 * up to the Crew cap minus the unreleased rows; the cap is 5, or 1 with {@code features.largeCrews} off), the Skills
 * sought (at most 10, an added one active in the catalog) and a note, and edits or turns it off through the same
 * command. Every write runs under the Job row lock and increments {@code crew_version}, which no Customer DTO carries;
 * turning it on publishes {@link CrewRecruitmentOpened}, and turning it off, J8 and J10 publish
 * {@link CrewRecruitmentClosed}, on which {@code matching} lapses the open Crew Applications. The setting lives on the
 * Job, so {@code job.phaseOf} and "Tìm việc" never read {@code matching} (AD-3).
 */
@Component
class JobRecruitment implements CrewRecruitment {

	static final String OPENED = "job.recruitment_opened";

	static final String EDITED = "job.recruitment_edited";

	static final String CLOSED = "job.recruitment_closed";

	/** Skills a recruitment seeks at most (the Skill picker's limit on a recruitment). */
	static final int MAX_SKILLS = 10;

	/** The longest note to applicants, as the column allows. */
	static final int MAX_NOTE = 1000;

	private static final Set<JobState> RECRUITS = Set.of(JobState.AGREEMENT, JobState.STARTING, JobState.ACTIVE);

	/**
	 * What the Mentor saves on JOB-9.
	 * @param openSlots the places sought while {@code on}; ignored while off
	 * @param skillIds the Skills sought, in the Mentor's order; ignored while off
	 * @param note the note to applicants; ignored while off
	 */
	record Setting(boolean on, @Nullable Integer openSlots, List<UUID> skillIds, @Nullable String note) {

		Setting {
			skillIds = List.copyOf(skillIds);
		}

	}

	private final JdbcClient jdbc;

	private final CrewService crews;

	private final CrewAlerts alerts;

	private final Catalog catalog;

	private final AuditLog auditLog;

	private final ApplicationEventPublisher events;

	private final IdGenerator ids;

	private final Clock clock;

	private final boolean largeCrews;

	JobRecruitment(JdbcClient jdbc, CrewService crews, CrewAlerts alerts, Catalog catalog, AuditLog auditLog,
			ApplicationEventPublisher events, IdGenerator ids, Clock clock,
			@Value("${vibefix.features.large-crews:false}") boolean largeCrews) {
		this.jdbc = jdbc;
		this.crews = crews;
		this.alerts = alerts;
		this.catalog = catalog;
		this.auditLog = auditLog;
		this.events = events;
		this.ids = ids;
		this.clock = clock;
		this.largeCrews = largeCrews;
	}

	/** The Crew cap (FR-75, AD-30): the most unreleased Crew rows a Job holds. */
	int cap() {
		return this.largeCrews ? CrewService.LARGE_CREW_LIMIT : 1;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Recruitment> recruitment(UUID jobId) {
		return read(jobId, false);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public Recruitment requireRecruiting(UUID jobId) {
		Recruitment recruitment = read(jobId, true).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!recruitment.recruiting() || recruitment.hidden()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.RECRUITMENT_CLOSED);
		}
		if (!recruitment.freeSlot()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.CREW_FULL, Map.of("n", recruitment.cap()));
		}
		return recruitment;
	}

	@Override
	public void conflictRefused() {
		this.alerts.conflictRefused();
	}

	/**
	 * JOB-9's "Nhận đơn xin vào nhóm" (AD-41, R2): on with its slots, Skills and note, an edit while on, or off. Off
	 * while off changes nothing.
	 * @throws ApiException {@code NOT_FOUND} for anyone but the Job's Mentor; {@code STALE} for an older Crew version;
	 * {@code GUARD_FAILED RECRUITMENT_UNAVAILABLE} outside Agreement, Starting and Active; {@code VALIDATION} with
	 * {@code SLOTS_INVALID} (and {@code max}), {@code SKILL_LIMIT}, {@code SKILL_INACTIVE}, or a note too long
	 */
	@Transactional
	Recruitment set(UUID mentorId, UUID jobId, long crewVersion, Setting setting) {
		Job job = this.crews.lockMentorJob(mentorId, jobId, crewVersion);
		if (!RECRUITS.contains(job.state())) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.RECRUITMENT_UNAVAILABLE);
		}
		Recruitment current = read(jobId, false).orElseThrow();
		Instant now = this.clock.instant();
		if (!setting.on()) {
			if (current.on()) {
				this.jdbc.sql("UPDATE job.job SET recruiting = false WHERE id = :id").param("id", jobId).update();
				this.crews.bump(jobId, false);
				audit(mentorId, jobId, CLOSED);
				this.events.publishEvent(new CrewRecruitmentClosed(this.ids.newId(), jobId, ClosedBy.MENTOR));
			}
			return read(jobId, false).orElseThrow();
		}
		int max = Math.max(0, current.cap() - current.unreleased());
		Integer slots = setting.openSlots();
		if (slots == null || slots < 1 || slots > max) {
			throw new ApiException(ErrorCode.VALIDATION, JobReason.SLOTS_INVALID, Map.of("max", max));
		}
		List<UUID> chosen = List.copyOf(new LinkedHashSet<>(setting.skillIds()));
		this.catalog.requireAtMost(chosen.size(), MAX_SKILLS);
		Set<UUID> added = new LinkedHashSet<>(chosen);
		current.skillIds().forEach(added::remove);
		this.catalog.requireActive(added);
		Map<UUID, String> names = new HashMap<>();
		for (int i = 0; i < current.skillIds().size(); i++) {
			names.put(current.skillIds().get(i), current.skillNames().get(i));
		}
		this.catalog.byIds(chosen).forEach(skill -> names.put(skill.id(), skill.name()));
		List<UUID> kept = chosen.stream().filter(names::containsKey).toList();
		String note = setting.note() == null || setting.note().isBlank() ? null : setting.note().strip();
		if (note != null && note.length() > MAX_NOTE) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		this.jdbc.sql("""
				UPDATE job.job SET recruiting = true, recruiting_since = CASE WHEN recruiting THEN recruiting_since ELSE :now END,
				    open_slots = :slots, recruit_skill_ids = :skills, recruit_skill_names = :names, recruit_note = :note
				WHERE id = :id
				""")
			.param("id", jobId)
			.param("now", Timestamp.from(now))
			.param("slots", slots)
			.param("skills", kept.toArray(UUID[]::new))
			.param("names", kept.stream().map(names::get).toArray(String[]::new))
			.param("note", note)
			.update();
		this.crews.bump(jobId, false);
		audit(mentorId, jobId, current.on() ? EDITED : OPENED);
		if (!current.on()) {
			this.events.publishEvent(new CrewRecruitmentOpened(this.ids.newId(), jobId));
		}
		return read(jobId, false).orElseThrow();
	}

	/**
	 * J8 and J10 (AD-41, A-04): recruitment turns off, in the transition's transaction under its Job row lock, and
	 * {@link CrewRecruitmentClosed} lapses whatever Crew Application is still open, so none outlives the Job's run.
	 */
	void closeForJob(UUID jobId, ClosedBy closedBy) {
		int closed = this.jdbc.sql("UPDATE job.job SET recruiting = false WHERE id = :id AND recruiting")
			.param("id", jobId)
			.update();
		if (closed > 0) {
			this.crews.bump(jobId, false);
		}
		this.events.publishEvent(new CrewRecruitmentClosed(this.ids.newId(), jobId, closedBy));
	}

	/** The Skills sought, by ID, as the catalog names them now (for the Skill picker's tokens). */
	Map<UUID, Skill> skills(Recruitment recruitment) {
		Map<UUID, Skill> skills = new HashMap<>();
		this.catalog.byIds(recruitment.skillIds()).forEach(skill -> skills.put(skill.id(), skill));
		return skills;
	}

	private void audit(UUID mentorId, UUID jobId, String action) {
		this.auditLog.record(AuditEntry.of(AuditActor.user(mentorId), action, AuditObject.of("job.job", jobId)).forJob(jobId));
	}

	private Optional<Recruitment> read(UUID jobId, boolean lock) {
		return this.jdbc.sql("""
				SELECT id, product_id, customer_account_id, mentor_account_id, title, state, hidden_at IS NOT NULL AS hidden,
				    recruiting, open_slots, recruit_skill_ids, recruit_skill_names, recruit_note,
				    (SELECT count(*) FROM job.crew_row r WHERE r.job_id = j.id AND r.status <> 'RELEASED') AS unreleased
				FROM job.job j WHERE id = :id
				""" + (lock ? " FOR UPDATE" : ""))
			.param("id", jobId)
			.query((rs, n) -> new Recruitment(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
					rs.getObject("customer_account_id", UUID.class), rs.getObject("mentor_account_id", UUID.class),
					rs.getString("title"), rs.getString("state"), rs.getBoolean("hidden"), rs.getBoolean("recruiting"),
					rs.getInt("open_slots"), rs.getInt("unreleased"), cap(), uuids(rs.getArray("recruit_skill_ids")),
					Arrays.asList((String[]) rs.getArray("recruit_skill_names").getArray()), rs.getString("recruit_note"),
					false))
			.optional()
			.map(found -> new Recruitment(found.jobId(), found.productId(), found.customerId(), found.mentorId(),
					found.title(), found.state(), found.hidden(), found.on(), found.openSlots(), found.unreleased(),
					found.cap(), found.skillIds(), found.skillNames(), found.note(),
					!this.crews.countedActive(jobId).isEmpty()));
	}

	private static List<UUID> uuids(Array array) throws SQLException {
		return Arrays.stream((Object[]) array.getArray()).map(UUID.class::cast).toList();
	}

}
