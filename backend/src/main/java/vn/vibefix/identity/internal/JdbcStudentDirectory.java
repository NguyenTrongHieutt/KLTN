package vn.vibefix.identity.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.identity.StudentDirectory;

/**
 * Any person's card from their account and profile (AD-36, Stories 19.3, 19.4 and 20.3). The name is the projection of
 * the live student verification, shown only while it is current (today in Vietnam on or before its last valid day) and
 * the details are not hidden (X9).
 */
@Component
class JdbcStudentDirectory implements StudentDirectory {

	private static final String COLUMNS = """
			a.id, a.github_login, a.github_user_id, a.locked_at IS NULL AND a.erased_at IS NULL AS available,
			p.student_name, coalesce(NOT p.hide_student_details, FALSE) AS shown, v.valid_until
			""";

	private static final String FROM = """
			FROM identity.account a
			LEFT JOIN identity.profile p ON p.account_id = a.id
			LEFT JOIN identity.verification v ON v.account_id = a.id AND v.kind = 'STUDENT' AND v.ended_at IS NULL
			""";

	/** One row before the name's rule applies. */
	private record Row(UUID id, @Nullable String login, @Nullable Long githubUserId, boolean available, @Nullable String name,
			boolean shown, @Nullable LocalDate verifiedUntil) {
	}

	private final JdbcClient jdbc;

	private final Clock clock;

	JdbcStudentDirectory(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public List<StudentCard> cards(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		LocalDate today = LocalDate.ofInstant(this.clock.instant(), StudentVerifications.VIETNAM);
		Map<UUID, StudentCard> byId = this.jdbc.sql("SELECT " + COLUMNS + FROM + "WHERE a.kind = 'USER' AND a.id IN (:ids)")
			.param("ids", List.copyOf(ids))
			.query(JdbcStudentDirectory::row)
			.list()
			.stream()
			.map(row -> card(row, today))
			.collect(Collectors.toMap(StudentCard::accountId, Function.identity()));
		List<StudentCard> ordered = new ArrayList<>();
		for (UUID id : ids) {
			StudentCard card = byId.get(id);
			if (card != null) {
				ordered.add(card);
			}
		}
		return ordered;
	}

	private static StudentCard card(Row row, LocalDate today) {
		// A lapsed verification's name is not shown, though its expiry Deadline has yet to clear it.
		boolean current = row.verifiedUntil() != null && !today.isAfter(row.verifiedUntil());
		return new StudentCard(row.id(), row.login(), row.githubUserId(), row.shown() && current ? row.name() : null,
				row.available());
	}

	private static Row row(ResultSet rs, int n) throws SQLException {
		return new Row(rs.getObject("id", UUID.class), rs.getString("github_login"), rs.getObject("github_user_id", Long.class),
				rs.getBoolean("available"), rs.getString("student_name"), rs.getBoolean("shown"),
				rs.getObject("valid_until", LocalDate.class));
	}

}
