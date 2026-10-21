package vn.vibefix.job.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.StudentDirectory.StudentCard;
import vn.vibefix.job.internal.AgreementReviews.Confirmed;
import vn.vibefix.job.internal.Agreements.ExternalAccess;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.Todos;
import vn.vibefix.scanning.SensitiveText;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * External Access after confirmation (FR-66, AD-20, UX-DR83). The Mentor's confirmation opens one
 * {@value #TODO} per item for the Customer ({@link AgreementReviews#confirm}). The Mentor and each Crew
 * member whose row is not released enter the account to invite on each item's system. The Customer
 * reads those identifiers only next to the item, beside each person's GitHub username, and only while
 * the Job is neither Closed nor Cancelled; each person reads their own. "Đã cấp quyền" closes the to-do,
 * the only record of the tick. An identifier is no credential: one the {@code scanning} rules read as a
 * secret is refused.
 */
@Component
class ExternalAccessGrants {

	static final String TODO = "job.grant_external_access";

	/** FR-66: an identifier is one line of at most this many characters. */
	static final int MAX_IDENTIFIER = 200;

	/** Who reads the items. */
	enum Viewer {
		CUSTOMER, MENTOR, CREW
	}

	/** One person's identifier beside their GitHub username. */
	record Identifier(String githubLogin, String identifier) {
	}

	/**
	 * One item as its viewer reads it.
	 * @param live a {@code PRODUCTION} item, or one the Customer marked as their live system (FR-16)
	 * @param granted the Customer's tick; {@code null} for anyone else
	 * @param identifiers the people's identifiers, for the Customer only
	 * @param mine the viewer's own identifier, for the Mentor and the Crew
	 */
	record Item(ExternalAccess access, boolean live, @Nullable Boolean granted, List<Identifier> identifiers,
			@Nullable String mine) {
	}

	private final JdbcClient jdbc;

	private final AgreementReviews reviews;

	private final CrewService crews;

	private final MentorDirectory mentors;

	private final Todos todos;

	private final SensitiveText check;

	private final Clock clock;

	ExternalAccessGrants(JdbcClient jdbc, AgreementReviews reviews, CrewService crews, MentorDirectory mentors, Todos todos,
			SensitiveText check, Clock clock) {
		this.jdbc = jdbc;
		this.reviews = reviews;
		this.crews = crews;
		this.mentors = mentors;
		this.todos = todos;
		this.check = check;
		this.clock = clock;
	}

	/** The Job, whoever asks; the caller's checks follow. */
	Optional<Job> job(UUID jobId) {
		return this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE id = :id")
			.param("id", jobId)
			.query(Jobs::job)
			.optional();
	}

	/** Whether the Student holds a Crew row of the Job that is not released. */
	boolean onCrew(UUID jobId, UUID studentId) {
		return this.crews.rows(jobId).stream().anyMatch(row -> row.studentId().equals(studentId) && !row.released());
	}

	/**
	 * The confirmed Agreement's items as {@code viewer} reads them.
	 * @throws ApiException {@code NOT_FOUND} before the Mentor confirmed
	 */
	List<Item> items(Job job, Viewer viewer, UUID accountId) {
		Confirmed confirmed = this.reviews.confirmed(job.id()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		boolean ended = ended(job);
		Map<UUID, Map<UUID, String>> entered = new HashMap<>();
		this.jdbc.sql("SELECT access_id, account_id, identifier FROM job.external_access_identifier WHERE job_id = :job")
			.param("job", job.id())
			.query((rs, n) -> entered.computeIfAbsent(rs.getObject("access_id", UUID.class), id -> new HashMap<>())
				.put(rs.getObject("account_id", UUID.class), rs.getString("identifier")))
			.list();
		Map<UUID, String> people = viewer == Viewer.CUSTOMER && !ended ? people(job) : Map.of();
		List<Item> items = new ArrayList<>();
		for (ExternalAccess access : confirmed.content().externalAccess()) {
			Map<UUID, String> byPerson = entered.getOrDefault(access.id(), Map.of());
			boolean live = access.environment() == Agreements.Environment.PRODUCTION
					|| confirmed.liveAccessIds().contains(access.id());
			if (viewer == Viewer.CUSTOMER) {
				List<Identifier> identifiers = new ArrayList<>();
				people.forEach((person, login) -> {
					String identifier = byPerson.get(person);
					if (identifier != null) {
						identifiers.add(new Identifier(login, identifier));
					}
				});
				items.add(new Item(access, live, !this.todos.isOpen(TODO, job.customerId(), access.id()), identifiers, null));
			}
			else {
				items.add(new Item(access, live, null, List.of(), ended ? null : byPerson.get(accountId)));
			}
		}
		return items;
	}

	/**
	 * The Mentor or a Crew member enters, changes, or clears ({@code ""}) their identifier for one item.
	 * @throws ApiException {@code NOT_FOUND} for an item the confirmed Agreement does not list;
	 * {@code CONFLICT EXTERNAL_ACCESS_CLOSED} once the Job is Closed or Cancelled; {@code VALIDATION} for
	 * more than one line or {@value #MAX_IDENTIFIER} characters; {@code GUARD_FAILED IDENTIFIER_LOOKS_LIKE_SECRET}
	 */
	@Transactional
	void enter(Job job, UUID accountId, UUID accessId, String identifier) {
		access(job, accessId);
		if (ended(job)) {
			throw new ApiException(ErrorCode.CONFLICT, JobReason.EXTERNAL_ACCESS_CLOSED);
		}
		String value = identifier.strip();
		if (value.length() > MAX_IDENTIFIER || value.contains("\n") || value.contains("\r")) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		if (value.isEmpty()) {
			this.jdbc.sql("DELETE FROM job.external_access_identifier WHERE access_id = :access AND account_id = :account")
				.param("access", accessId)
				.param("account", accountId)
				.update();
			return;
		}
		if (this.check.check(value).count(SensitiveText.Kind.CREDENTIAL) > 0) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.IDENTIFIER_LOOKS_LIKE_SECRET);
		}
		this.jdbc.sql("""
				INSERT INTO job.external_access_identifier (access_id, account_id, job_id, identifier, updated_at)
				VALUES (:access, :account, :job, :identifier, :now)
				ON CONFLICT (access_id, account_id) DO UPDATE SET identifier = EXCLUDED.identifier, updated_at = EXCLUDED.updated_at
				""")
			.param("access", accessId)
			.param("account", accountId)
			.param("job", job.id())
			.param("identifier", value)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

	/** "Đã cấp quyền": the Customer's to-do for the item closes; ticking again changes nothing. */
	@Transactional
	void granted(Job job, UUID accessId) {
		access(job, accessId);
		this.todos.close(TODO, job.customerId(), accessId);
	}

	private ExternalAccess access(Job job, UUID accessId) {
		return this.reviews.confirmed(job.id())
			.flatMap(confirmed -> confirmed.content()
				.externalAccess()
				.stream()
				.filter(access -> access.id().equals(accessId))
				.findFirst())
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	/** The people on the Job now, the Mentor first: their GitHub usernames by account. */
	private Map<UUID, String> people(Job job) {
		Map<UUID, String> people = new LinkedHashMap<>();
		if (job.mentorId() != null) {
			this.mentors.cards(List.of(job.mentorId()))
				.stream()
				.filter(card -> card.githubLogin() != null)
				.forEach(card -> people.put(card.accountId(), card.githubLogin()));
		}
		List<UUID> crew = this.crews.rows(job.id())
			.stream()
			.filter(row -> !row.released())
			.map(CrewService.Row::studentId)
			.toList();
		Map<UUID, StudentCard> cards = this.crews.cards(crew);
		for (UUID student : crew) {
			StudentCard card = cards.get(student);
			if (card != null && card.githubLogin() != null) {
				people.put(student, card.githubLogin());
			}
		}
		return people;
	}

	private static boolean ended(Job job) {
		return job.state() == JobState.CLOSED || job.state() == JobState.CANCELLED;
	}

}
