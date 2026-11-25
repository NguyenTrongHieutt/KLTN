package vn.vibefix.delivery.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.delivery.DeliveryEvidence;

/**
 * {@link DeliveryEvidence} over {@code delivery.handover}: the Mentor's criteria as reported
 * ({@code handover_criterion}) next to the Customer's checks ({@code handover_check}), and what the handover
 * carried beside them, read as the Delivery Report reads it: the preview or the reason there is none, the images
 * ({@link HandoverScreenshots#of}) and the links ({@link Handovers#links}), each link's domain from its text alone
 * (AD-32, AD-35). Nothing a link names is ever requested.
 */
@Component
class HandoverEvidence implements DeliveryEvidence {

	private final JdbcClient jdbc;

	private final Handovers handovers;

	private final HandoverScreenshots screenshots;

	HandoverEvidence(JdbcClient jdbc, Handovers handovers, HandoverScreenshots screenshots) {
		this.jdbc = jdbc;
		this.handovers = handovers;
		this.screenshots = screenshots;
	}

	@Override
	@Transactional(readOnly = true)
	public List<Report> reports(UUID jobId) {
		return this.jdbc.sql("""
				SELECT id, milestone_id, milestone_number, milestone_title, round, status, created_at, decided_at, auto,
				       voided_at, handover_commit, preview_url, no_preview_reason, days_late
				FROM delivery.handover WHERE job_id = :job ORDER BY created_at, seq
				""").param("job", jobId).query((rs, n) -> {
			UUID handover = rs.getObject("id", UUID.class);
			String preview = rs.getString("preview_url");
			return new Report(handover, rs.getObject("milestone_id", UUID.class), rs.getInt("milestone_number"),
					rs.getString("milestone_title"), rs.getInt("round"), rs.getString("status"),
					rs.getTimestamp("created_at").toInstant(), instant(rs.getTimestamp("decided_at")), rs.getBoolean("auto"),
					instant(rs.getTimestamp("voided_at")), rs.getString("handover_commit"), criteria(handover), preview,
					preview == null ? null : PreviewChecks.domain(preview), rs.getString("no_preview_reason"), images(handover),
					links(handover), (Integer) rs.getObject("days_late"));
		}).list();
	}

	@Override
	@Transactional(readOnly = true)
	public List<CommitLine> commitsOf(UUID milestoneId) {
		return this.jdbc.sql("""
				SELECT c.handover_id, h.round, c.sha, c.message, c.author_github_id
				FROM delivery.handover_commit c JOIN delivery.handover h ON h.id = c.handover_id
				WHERE h.milestone_id = :milestone ORDER BY h.created_at, h.seq, c.position
				""")
			.param("milestone", milestoneId)
			.query((rs, n) -> new CommitLine(rs.getObject("handover_id", UUID.class), rs.getInt("round"), rs.getString("sha"),
					rs.getString("message"), rs.getObject("author_github_id", Long.class)))
			.list();
	}

	@Override
	@Transactional(readOnly = true)
	public boolean commitsTruncatedOf(UUID milestoneId) {
		return Boolean.TRUE.equals(this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM delivery.handover
				               WHERE milestone_id = :milestone AND commits_truncated)
				""").param("milestone", milestoneId).query(Boolean.class).single());
	}

	private List<CriterionStatus> criteria(UUID handoverId) {
		return this.jdbc.sql("""
				SELECT c.criterion_id, c.number, c.statement, c.met, c.note, k.met AS customer_met, k.comment
				FROM delivery.handover_criterion c
				LEFT JOIN delivery.handover_check k ON k.handover_id = c.handover_id AND k.criterion_id = c.criterion_id
				WHERE c.handover_id = :handover ORDER BY c.number
				""").param("handover", handoverId).query((rs, n) -> {
			boolean checked = rs.getObject("customer_met") != null;
			return new CriterionStatus(rs.getObject("criterion_id", UUID.class), rs.getInt("number"), rs.getString("statement"),
					rs.getBoolean("met"), rs.getString("note"), checked ? rs.getBoolean("customer_met") : null,
					rs.getString("comment"));
		}).list();
	}

	private List<Image> images(UUID handoverId) {
		return this.screenshots.of(handoverId)
			.stream()
			.map(screenshot -> new Image(screenshot.fileId(), screenshot.fileName(), screenshot.size()))
			.toList();
	}

	/** Each link with its host as the Delivery Report shows it: lowercased ASCII, as typed (AD-35). */
	private List<Link> links(UUID handoverId) {
		return this.handovers.links(handoverId)
			.stream()
			.map(link -> new Link(kind(link.kind()), link.label(), link.url(),
					Handovers.linkHost(link.url()).orElse(link.url())))
			.toList();
	}

	private static LinkKind kind(Handovers.Link.Kind kind) {
		return switch (kind) {
			case VIDEO -> LinkKind.VIDEO;
			case DOCUMENT -> LinkKind.DOCUMENT;
		};
	}

	private static @Nullable Instant instant(@Nullable Timestamp at) {
		return at == null ? null : at.toInstant();
	}

}
