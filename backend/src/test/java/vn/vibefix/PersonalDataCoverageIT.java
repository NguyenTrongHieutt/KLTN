package vn.vibefix;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.PostgresTestDatabase;

/**
 * AD-24, SG-37: every table that can hold personal data has a {@link PersonalDataParticipant}. A
 * table qualifies when a column names a person ({@link #PERSONAL_COLUMN}: an account ID under any of
 * the role names, a GitHub user ID, an email address, a session principal), or when it is one of the
 * side stores whose payloads name accounts: the event publication registry, the session tables, and
 * every table of {@code operations}. {@code audit} is exempt, because it holds internal IDs only.
 */
class PersonalDataCoverageIT extends IntegrationTest {

	static final Set<String> EXEMPT_SCHEMAS = Set.of("audit");

	static final Set<String> SIDE_STORES = Set.of("platform.event_publication", "platform.event_publication_archive",
			"platform.spring_session", "platform.spring_session_attributes");

	static final String SIDE_STORE_SCHEMA = "operations";

	/** Column names that identify a person. */
	static final Pattern PERSONAL_COLUMN = Pattern.compile(
			"(?:.*_)?(?:account|customer|mentor|student|admin|administrator|author|raiser|subject|user|github_user)_id"
					+ "|(?:.*_)?email|principal_name");

	@Autowired
	private ApplicationContext context;

	@Test
	void everyTableWithPersonalDataHasAParticipant() throws Exception {
		Set<String> tables = tablesHoldingPersonalData();
		Set<String> covered = new HashSet<>();
		this.context.getBeansOfType(PersonalDataParticipant.class).values().forEach(p -> covered.addAll(p.tables()));

		assertThat(tables).as("the event publication registry is checked").contains("platform.event_publication");
		assertThat(violations(tables, covered, allTables())).isEmpty();
	}

	@Test
	void theCheckFindsATableWithoutAParticipantAndAParticipantOfNoTable() {
		assertThat(violations(Set.of("identity.account", "platform.event_publication"),
				Set.of("platform.event_publication", "team.gone"),
				Set.of("identity.account", "platform.event_publication")))
			.containsExactly("identity.account holds account IDs but no PersonalDataParticipant covers it",
					"a PersonalDataParticipant covers team.gone, which does not exist");
	}

	@Test
	void personalColumnsAreRecognizedByName() {
		for (String column : List.of("account_id", "student_account_id", "member_account_id", "mentor_id", "customer_id",
				"raiser_id", "author_id", "github_user_id", "assignee_github_user_id", "email", "school_email",
				"principal_name")) {
			assertThat(PERSONAL_COLUMN.matcher(column).matches()).as(column).isTrue();
		}
		for (String column : List.of("id", "job_id", "product_id", "listener_id", "event_id", "publication_id",
				"emails_sent", "correlation_id")) {
			assertThat(PERSONAL_COLUMN.matcher(column).matches()).as(column).isFalse();
		}
	}

	static List<String> violations(Set<String> tablesWithAccountIds, Set<String> covered, Set<String> existing) {
		List<String> violations = new ArrayList<>();
		new TreeSet<>(tablesWithAccountIds).stream()
			.filter(table -> !covered.contains(table))
			.forEach(table -> violations.add(table + " holds account IDs but no PersonalDataParticipant covers it"));
		new TreeSet<>(covered).stream()
			.filter(table -> !existing.contains(table))
			.forEach(table -> violations.add("a PersonalDataParticipant covers " + table + ", which does not exist"));
		return violations;
	}

	private static Set<String> tablesHoldingPersonalData() throws Exception {
		Set<String> tables = new TreeSet<>();
		for (String row : query("""
				SELECT table_schema || '.' || table_name || ' ' || column_name FROM information_schema.columns
				WHERE table_schema NOT IN ('pg_catalog', 'information_schema')
				""")) {
			String[] tableAndColumn = row.split(" ", 2);
			if (PERSONAL_COLUMN.matcher(tableAndColumn[1]).matches()) {
				tables.add(tableAndColumn[0]);
			}
		}
		for (String table : allTables()) {
			if (SIDE_STORES.contains(table) || table.startsWith(SIDE_STORE_SCHEMA + ".")) {
				tables.add(table);
			}
		}
		tables.removeIf(table -> EXEMPT_SCHEMAS.contains(table.substring(0, table.indexOf('.'))));
		return tables;
	}

	private static Set<String> allTables() throws Exception {
		return new TreeSet<>(query("""
				SELECT table_schema || '.' || table_name FROM information_schema.tables
				WHERE table_schema NOT IN ('pg_catalog', 'information_schema') AND table_type = 'BASE TABLE'
				"""));
	}

	private static List<String> query(String sql) throws Exception {
		List<String> values = new ArrayList<>();
		try (Connection connection = database.superuserConnection(PostgresTestDatabase.DATABASE);
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery(sql)) {
			while (rows.next()) {
				values.add(rows.getString(1));
			}
		}
		return values;
	}

}
