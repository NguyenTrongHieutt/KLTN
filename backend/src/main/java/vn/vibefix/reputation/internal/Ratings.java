package vn.vibefix.reputation.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.identity.CustomerNames;
import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.job.Crews;
import vn.vibefix.job.Crews.CrewMember;
import vn.vibefix.job.Crews.Selection;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * The Rating Window and its Ratings and private feedback (FR-49, FR-78, AD-7, AD-41). J9 opens the
 * window for the Job's copied days; the Customer and the Mentor rate each other blind (published when
 * both rated or at the end), the Mentor rates each Crew member who was ever active (published at the
 * end), and those Crew members may give private feedback once. Every read filters what is not
 * published, so nothing hidden leaves the server (NFR-1).
 */
@Component
class Ratings {

	static final String OPENED = "reputation.rating_window_opened";

	static final String INVITED = "reputation.private_feedback_invited";

	static final String PUBLISHED = "reputation.ratings_published";

	static final String RATE = "reputation.rate";

	static final String GIVE_FEEDBACK = "reputation.give_private_feedback";

	static final int DEFAULT_DAYS = 14;

	static final Duration FEEDBACK_KEPT = Duration.ofDays(730);

	private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	enum Kind {
		CUSTOMER_TO_MENTOR, MENTOR_TO_CUSTOMER, MENTOR_TO_STUDENT
	}

	record Window(UUID jobId, UUID productId, @Nullable UUID customerId, @Nullable UUID mentorId, String jobTitle,
			Instant openedAt, Instant endsAt, @Nullable Instant pairPublishedAt, @Nullable Instant crewPublishedAt) {

		boolean open(Instant now) {
			return now.isBefore(this.endsAt);
		}

	}

	record Rating(UUID id, UUID jobId, Kind kind, @Nullable UUID raterId, UUID rateeId, int stars, String comment,
			Instant createdAt) {
	}

	private final JdbcClient jdbc;

	private final Crews crews;

	private final CustomerNames customers;

	private final MentorDirectory mentors;

	private final Notifications notifications;

	private final Todos todos;

	private final Deadlines deadlines;

	private final IdGenerator ids;

	private final Clock clock;

	Ratings(JdbcClient jdbc, Crews crews, CustomerNames customers, MentorDirectory mentors, Notifications notifications,
			Todos todos, Deadlines deadlines, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.crews = crews;
		this.customers = customers;
		this.mentors = mentors;
		this.notifications = notifications;
		this.todos = todos;
		this.deadlines = deadlines;
		this.ids = ids;
		this.clock = clock;
	}

	/** J9: opens the window, tells everyone, and opens their to-dos; opening twice changes nothing. */
	@Transactional
	void open(UUID jobId, UUID productId, UUID customerId, UUID mentorId, String title, int days, UUID causeId) {
		if (window(jobId).isPresent()) {
			return;
		}
		Instant now = this.clock.instant();
		Instant ends = now.plus(Duration.ofDays(days));
		this.jdbc.sql("""
				INSERT INTO reputation.rating_window (job_id, product_id, customer_account_id, mentor_account_id, job_title,
				    opened_at, ends_at)
				VALUES (:job, :product, :customer, :mentor, :title, :now, :ends)
				""")
			.param("job", jobId)
			.param("product", productId)
			.param("customer", customerId)
			.param("mentor", mentorId)
			.param("title", title)
			.param("now", Timestamp.from(now))
			.param("ends", Timestamp.from(ends))
			.update();
		String date = dayMonth(ends);
		String mentorName = mentorName(mentorId);
		String customerName = this.customers.displayName(customerId);
		String link = "/cong-viec/" + jobId;
		this.notifications.send(new NotificationRequest(OPENED, causeId, List.of(new Recipient(customerId, Audience.CUSTOMER)),
				Map.of("party", mentorName, "date", date), link, jobId, null));
		this.notifications.send(new NotificationRequest(OPENED, causeId, List.of(new Recipient(mentorId, Audience.MENTOR)),
				Map.of("party", customerName, "date", date), link, jobId, null));
		this.todos.open(new TodoRequest(RATE, customerId, Audience.CUSTOMER, jobId, Map.of("party", mentorName), link, ends,
				jobId));
		this.todos.open(new TodoRequest(RATE, mentorId, Audience.MENTOR, jobId, Map.of("party", customerName), link, ends,
				jobId));
		for (UUID member : crew(jobId)) {
			this.notifications.send(new NotificationRequest(INVITED, causeId,
					List.of(new Recipient(member, Audience.CREW_EVER_ACTIVE)), Map.of("date", date), link, jobId, null));
			this.todos.open(new TodoRequest(GIVE_FEEDBACK, member, Audience.CREW_EVER_ACTIVE, jobId, Map.of(), link, ends,
					jobId));
		}
		this.deadlines.schedule(new DeadlineRequest(RatingWindowEnd.KEY, jobId, jobId.toString(), jobId, ends));
		this.deadlines.schedule(
				new DeadlineRequest(FeedbackPurge.KEY, jobId, jobId.toString(), jobId, ends.plus(FEEDBACK_KEPT)));
	}

	/**
	 * A Rating (FR-49), once per rater and ratee.
	 * @throws ApiException {@code FORBIDDEN NOT_A_RATER}; {@code GUARD_FAILED RATING_WINDOW_NOT_OPEN} or
	 * {@code RATING_WINDOW_CLOSED}; {@code VALIDATION RATEE_NOT_ALLOWED}; {@code CONFLICT ALREADY_SUBMITTED}
	 */
	@Transactional
	void rate(Viewer viewer, UUID rateeId, int stars, String comment) {
		if (viewer.role() == ViewerRole.CREW) {
			throw new ApiException(ErrorCode.FORBIDDEN, ReputationReason.NOT_A_RATER);
		}
		Window window = openWindow(viewer.jobId());
		Kind kind;
		if (viewer.role() == ViewerRole.CUSTOMER) {
			if (!rateeId.equals(window.mentorId())) {
				throw new ApiException(ErrorCode.VALIDATION, ReputationReason.RATEE_NOT_ALLOWED);
			}
			kind = Kind.CUSTOMER_TO_MENTOR;
		}
		else if (rateeId.equals(window.customerId())) {
			kind = Kind.MENTOR_TO_CUSTOMER;
		}
		else if (crew(viewer.jobId()).contains(rateeId)) {
			kind = Kind.MENTOR_TO_STUDENT;
		}
		else {
			throw new ApiException(ErrorCode.VALIDATION, ReputationReason.RATEE_NOT_ALLOWED);
		}
		String text = comment.strip();
		if (text.isEmpty() || text.length() > 2000 || stars < 1 || stars > 5) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		int inserted = this.jdbc.sql("""
				INSERT INTO reputation.rating (id, job_id, kind, rater_account_id, ratee_account_id, stars, comment, created_at)
				VALUES (:id, :job, :kind, :rater, :ratee, :stars, :comment, :now) ON CONFLICT DO NOTHING
				""")
			.param("id", this.ids.newId())
			.param("job", viewer.jobId())
			.param("kind", kind.name())
			.param("rater", viewer.accountId())
			.param("ratee", rateeId)
			.param("stars", stars)
			.param("comment", text)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		if (inserted == 0) {
			throw new ApiException(ErrorCode.CONFLICT, ReputationReason.ALREADY_SUBMITTED);
		}
		if (left(viewer, window).isEmpty()) {
			this.todos.close(RATE, viewer.accountId(), viewer.jobId());
		}
		if (kind != Kind.MENTOR_TO_STUDENT && window.pairPublishedAt() == null && pairComplete(viewer.jobId())) {
			this.jdbc.sql("UPDATE reputation.rating_window SET pair_published_at = :now WHERE job_id = :job")
				.param("job", viewer.jobId())
				.param("now", Timestamp.from(this.clock.instant()))
				.update();
			publishedTo(window, false);
		}
	}

	/**
	 * Private feedback (FR-78), once, by a Crew member who was ever active.
	 * @throws ApiException {@code GUARD_FAILED RATING_WINDOW_NOT_OPEN} or {@code RATING_WINDOW_CLOSED};
	 * {@code CONFLICT ALREADY_SUBMITTED}
	 */
	@Transactional
	void giveFeedback(Viewer viewer, int stars, @Nullable String comment) {
		Window window = openWindow(viewer.jobId());
		String text = comment == null || comment.isBlank() ? null : comment.strip();
		if (stars < 1 || stars > 5 || (text != null && text.length() > 2000) || window.mentorId() == null) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		int inserted = this.jdbc.sql("""
				INSERT INTO reputation.private_feedback (id, job_id, mentor_account_id, student_account_id, stars, comment,
				    created_at, delete_after)
				VALUES (:id, :job, :mentor, :student, :stars, :comment, :now, :deleteAfter) ON CONFLICT DO NOTHING
				""")
			.param("id", this.ids.newId())
			.param("job", viewer.jobId())
			.param("mentor", window.mentorId())
			.param("student", viewer.accountId())
			.param("stars", stars)
			.param("comment", text)
			.param("now", Timestamp.from(this.clock.instant()))
			.param("deleteAfter", Timestamp.from(window.endsAt().plus(FEEDBACK_KEPT)))
			.update();
		if (inserted == 0) {
			throw new ApiException(ErrorCode.CONFLICT, ReputationReason.ALREADY_SUBMITTED);
		}
		this.todos.close(GIVE_FEEDBACK, viewer.accountId(), viewer.jobId());
	}

	/** The window's end: everything is published and every to-do closes. */
	@Transactional
	boolean end(UUID jobId) {
		Window window = window(jobId).orElse(null);
		if (window == null || window.crewPublishedAt() != null) {
			return false;
		}
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				UPDATE reputation.rating_window SET pair_published_at = coalesce(pair_published_at, :now), crew_published_at = :now
				WHERE job_id = :job
				""").param("job", jobId).param("now", now).update();
		this.todos.closeAll(RATE, jobId);
		this.todos.closeAll(GIVE_FEEDBACK, jobId);
		publishedTo(window, true);
		return true;
	}

	Optional<Window> window(UUID jobId) {
		return this.jdbc.sql("""
				SELECT job_id, product_id, customer_account_id, mentor_account_id, job_title, opened_at, ends_at,
				       pair_published_at, crew_published_at
				FROM reputation.rating_window WHERE job_id = :job
				""").param("job", jobId).query(Ratings::window).optional();
	}

	/** The Job's Ratings that are published, or given by {@code viewer}. */
	List<Rating> visibleTo(UUID jobId, UUID viewer) {
		return this.jdbc.sql("""
				SELECT r.id, r.job_id, r.kind, r.rater_account_id, r.ratee_account_id, r.stars, r.comment, r.created_at
				FROM reputation.rating r JOIN reputation.rating_window w ON w.job_id = r.job_id
				WHERE r.job_id = :job AND (r.rater_account_id = :viewer
				    OR (r.kind <> 'MENTOR_TO_STUDENT' AND w.pair_published_at IS NOT NULL)
				    OR (r.kind = 'MENTOR_TO_STUDENT' AND w.crew_published_at IS NOT NULL))
				ORDER BY r.created_at, r.id
				""").param("job", jobId).param("viewer", viewer).query(Ratings::rating).list();
	}

	/** Who the viewer still rates while the window is open: the Mentor, or the Customer then the Crew. */
	List<UUID> left(Viewer viewer, Window window) {
		if (viewer.role() == ViewerRole.CREW || !window.open(this.clock.instant())) {
			return List.of();
		}
		Set<UUID> given = new LinkedHashSet<>(this.jdbc.sql("""
				SELECT ratee_account_id FROM reputation.rating WHERE job_id = :job AND rater_account_id = :rater
				""").param("job", viewer.jobId()).param("rater", viewer.accountId()).query(UUID.class).list());
		List<UUID> targets = new ArrayList<>();
		if (viewer.role() == ViewerRole.CUSTOMER) {
			if (window.mentorId() != null) {
				targets.add(window.mentorId());
			}
		}
		else {
			if (window.customerId() != null) {
				targets.add(window.customerId());
			}
			targets.addAll(crew(viewer.jobId()));
		}
		targets.removeAll(given);
		return targets;
	}

	boolean feedbackGiven(UUID jobId, UUID studentId) {
		return this.jdbc.sql("SELECT EXISTS (SELECT 1 FROM reputation.private_feedback WHERE job_id = :job AND student_account_id = :id)")
			.param("job", jobId).param("id", studentId).query(Boolean.class).single();
	}

	/** Every Crew member who was ever active, the Mentor's Ratees and the feedback's authors. */
	List<UUID> crew(UUID jobId) {
		return this.crews.crewOf(jobId, Selection.EVER_ACTIVE).stream().map(CrewMember::studentId).distinct().toList();
	}

	static String dayMonth(Instant at) {
		return DAY_MONTH.format(at.atZone(VIETNAM));
	}

	private String mentorName(UUID mentorId) {
		return this.mentors.cards(List.of(mentorId)).stream().findFirst().map(MentorCard::displayName).orElse("");
	}

	private Window openWindow(UUID jobId) {
		Window window = window(jobId)
			.orElseThrow(() -> new ApiException(ErrorCode.GUARD_FAILED, ReputationReason.RATING_WINDOW_NOT_OPEN));
		if (!window.open(this.clock.instant())) {
			throw new ApiException(ErrorCode.GUARD_FAILED, ReputationReason.RATING_WINDOW_CLOSED);
		}
		return window;
	}

	private boolean pairComplete(UUID jobId) {
		return this.jdbc.sql("""
				SELECT count(DISTINCT kind) = 2 FROM reputation.rating
				WHERE job_id = :job AND kind IN ('CUSTOMER_TO_MENTOR', 'MENTOR_TO_CUSTOMER')
				""").param("job", jobId).query(Boolean.class).single();
	}

	/** {@code reputation.ratings_published}: the Customer and the Mentor, and at the end each rated member. */
	private void publishedTo(Window window, boolean end) {
		List<Recipient> recipients = new ArrayList<>();
		if (window.customerId() != null) {
			recipients.add(new Recipient(window.customerId(), Audience.CUSTOMER));
		}
		if (window.mentorId() != null) {
			recipients.add(new Recipient(window.mentorId(), Audience.MENTOR));
		}
		if (end) {
			this.jdbc.sql("""
					SELECT DISTINCT ratee_account_id FROM reputation.rating WHERE job_id = :job AND kind = 'MENTOR_TO_STUDENT'
					""").param("job", window.jobId()).query(UUID.class).list()
				.forEach(member -> recipients.add(new Recipient(member, Audience.CREW_EVER_ACTIVE)));
		}
		if (!recipients.isEmpty()) {
			this.notifications.send(new NotificationRequest(PUBLISHED, window.jobId(), recipients,
					Map.of("job", window.jobTitle()), "/cong-viec/" + window.jobId(), window.jobId(), null));
		}
	}

	private static Window window(ResultSet rs, int n) throws SQLException {
		Timestamp pair = rs.getTimestamp("pair_published_at");
		Timestamp crew = rs.getTimestamp("crew_published_at");
		return new Window(rs.getObject("job_id", UUID.class), rs.getObject("product_id", UUID.class),
				rs.getObject("customer_account_id", UUID.class), rs.getObject("mentor_account_id", UUID.class),
				rs.getString("job_title"), rs.getTimestamp("opened_at").toInstant(), rs.getTimestamp("ends_at").toInstant(),
				pair == null ? null : pair.toInstant(), crew == null ? null : crew.toInstant());
	}

	private static Rating rating(ResultSet rs, int n) throws SQLException {
		return new Rating(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class), Kind.valueOf(rs.getString("kind")),
				rs.getObject("rater_account_id", UUID.class), rs.getObject("ratee_account_id", UUID.class), rs.getInt("stars"),
				rs.getString("comment"), rs.getTimestamp("created_at").toInstant());
	}

	enum ViewerRole {
		CUSTOMER, MENTOR, CREW
	}

	/** Who reads or acts on the Job's Ratings. */
	record Viewer(UUID jobId, UUID accountId, ViewerRole role) {
	}

}
