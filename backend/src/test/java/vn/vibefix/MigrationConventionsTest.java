package vn.vibefix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Conventions, Migrations: Flyway files live in {@code db/migration/{module}/}, are named
 * {@code V{yyyyMMddHHmm}__{module}_{description}.sql}, touch only their own module's schema, and
 * once on {@code main} are never edited; a new migration's version must be newer than every
 * version on {@code main} ({@code outOfOrder} is off).
 * <p>
 * The base branch is {@code origin/main}, else {@code main}; the system property
 * {@code vibefix.migrations.base-ref} overrides it. The test fails if neither exists.
 */
class MigrationConventionsTest {

	static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

	static final String GIT_MIGRATIONS = "backend/src/main/resources/db/migration";

	static final Pattern FILE_NAME = Pattern.compile("V(\\d{12})__([a-z]+)_([a-z0-9]+(?:_[a-z0-9]+)*)\\.sql");

	static final DateTimeFormatter VERSION_FORMAT = DateTimeFormatter.ofPattern("uuuuMMddHHmm")
		.withResolverStyle(ResolverStyle.STRICT);

	/** Every schema a migration could name: one per module, plus the framework schema. */
	static final Set<String> SCHEMAS = schemas();

	@Test
	void migrationsFollowTheConventions() {
		Map<String, String> current = currentMigrations();

		assertThat(current).as("migrations under %s", MIGRATIONS).isNotEmpty();
		assertThat(check(current)).isEmpty();
	}

	@Test
	void migrationsOnMainAreUnchangedAndNewOnesAreNewer() {
		String baseRef = baseRef();

		assertThat(checkAgainstBase(migrationsAt(baseRef), currentMigrations())).as("compared with %s", baseRef)
			.isEmpty();
	}

	@Test
	void rejectsAMigrationThatNamesAnotherSchema() {
		assertThat(check(Map.of("identity/V202611010900__identity_accounts.sql", """
				CREATE TABLE identity.account (id UUID PRIMARY KEY);
				INSERT INTO platform.environment (name) VALUES ('dev');
				"""))).singleElement().asString().contains("platform");
		assertThat(check(Map.of("identity/V202611010900__identity_grants.sql", """
				GRANT USAGE ON SCHEMA audit TO vibefix_app;
				"""))).singleElement().asString().contains("audit");
		assertThat(check(Map.of("identity/V202611010900__identity_path.sql", """
				SET search_path TO audit;
				"""))).singleElement().asString().contains("search_path");
		assertThat(check(Map.of("identity/V202611010900__identity_unqualified.sql", """
				CREATE TABLE account (id UUID PRIMARY KEY);
				"""))).singleElement().asString().contains("unqualified");
	}

	@Test
	void ignoresSchemaNamesInCommentsAndLiterals() {
		assertThat(check(Map.of("identity/V202611010900__identity_accounts.sql", """
				-- Mirrors audit.entry, see platform.environment
				/* job.job is read through the job API */
				CREATE TABLE identity.account (id UUID PRIMARY KEY, note TEXT DEFAULT 'audit.entry');
				CREATE INDEX account_note_idx ON identity.account (note);
				GRANT USAGE ON SCHEMA identity TO ${runtime_role};
				"""))).isEmpty();
	}

	@Test
	void rejectsMisplacedOrMisnamedMigrations() {
		assertThat(check(Map.of("V202611010900__identity_accounts.sql", "SELECT 1;"))).hasSize(1);
		assertThat(check(Map.of("unknown/V202611010900__unknown_accounts.sql", "SELECT 1;"))).isNotEmpty();
		assertThat(check(Map.of("identity/V202611010900__audit_accounts.sql", "SELECT 1;"))).hasSize(1);
		assertThat(check(Map.of("identity/V1__identity_accounts.sql", "SELECT 1;"))).hasSize(1);
		assertThat(check(Map.of("identity/V202613010900__identity_accounts.sql", "SELECT 1;"))).hasSize(1);
		assertThat(check(Map.of("identity/R__identity_view.sql", "SELECT 1;"))).hasSize(1);
		assertThat(check(Map.of("identity/V202611010900__identity_a.sql", "SELECT 1;",
				"audit/V202611010900__audit_b.sql", "SELECT 1;")))
			.singleElement()
			.asString()
			.contains("duplicate");
	}

	@Test
	void rejectsAVersionNotNewerThanMain() {
		Map<String, String> base = Map.of("platform/V202610181120__platform_init.sql", "SELECT 1;");
		Map<String, String> current = new HashMap<>(base);
		current.put("identity/V202610181120__identity_init.sql", "SELECT 1;");
		current.put("audit/V202609010000__audit_init.sql", "SELECT 1;");
		current.put("files/V202610181121__files_init.sql", "SELECT 1;");

		assertThat(checkAgainstBase(base, current)).hasSize(2)
			.anySatisfy(violation -> assertThat(violation).contains("identity/V202610181120"))
			.anySatisfy(violation -> assertThat(violation).contains("audit/V202609010000"));
	}

	@Test
	void rejectsEditingOrDeletingAMigrationOnMain() {
		Map<String, String> base = Map.of("platform/V202610181120__platform_init.sql", "SELECT 1;\n",
				"platform/V202610181130__platform_more.sql", "SELECT 2;\n");

		assertThat(checkAgainstBase(base, Map.of("platform/V202610181120__platform_init.sql", "SELECT 1;\r\n",
				"platform/V202610181130__platform_more.sql", "SELECT 2;\n")))
			.isEmpty();
		assertThat(checkAgainstBase(base, Map.of("platform/V202610181120__platform_init.sql", "SELECT 3;\n",
				"platform/V202610181130__platform_more.sql", "SELECT 2;\n")))
			.singleElement()
			.asString()
			.contains("edited");
		assertThat(checkAgainstBase(base, Map.of("platform/V202610181120__platform_init.sql", "SELECT 1;\n")))
			.singleElement()
			.asString()
			.contains("deleted");
	}

	/** Checks location, name, version and schema use of every migration (path relative to db/migration). */
	static List<String> check(Map<String, String> migrations) {
		List<String> violations = new ArrayList<>();
		Map<String, String> versions = new HashMap<>();
		new TreeMap<>(migrations).forEach((path, sql) -> {
			String[] parts = path.split("/");
			if (parts.length != 2) {
				violations.add(path + ": must be in db/migration/{module}/");
				return;
			}
			String folder = parts[0];
			if (!SCHEMAS.contains(folder)) {
				violations.add(path + ": folder '" + folder + "' is not a module of AD-3 or platform");
			}
			Matcher name = FILE_NAME.matcher(parts[1]);
			if (!name.matches()) {
				violations.add(path + ": must be named V{yyyyMMddHHmm}__{module}_{description}.sql");
				return;
			}
			if (!name.group(2).equals(folder)) {
				violations.add(path + ": file names module '" + name.group(2) + "' but is in folder '" + folder + "'");
			}
			String version = name.group(1);
			if (!isValidTimestamp(version)) {
				violations.add(path + ": version " + version + " is not a valid yyyyMMddHHmm timestamp");
			}
			String previous = versions.putIfAbsent(version, path);
			if (previous != null) {
				violations.add(path + ": duplicate version " + version + " (also " + previous + ")");
			}
			violations.addAll(schemaViolations(path, folder, sql));
		});
		return violations;
	}

	/** Compares with the base branch: migrations there are immutable, new ones must be newer. */
	static List<String> checkAgainstBase(Map<String, String> base, Map<String, String> current) {
		List<String> violations = new ArrayList<>();
		String newestOnBase = base.keySet().stream().map(MigrationConventionsTest::versionOf).flatMap(Optional::stream)
			.max(String::compareTo)
			.orElse("");
		new TreeMap<>(base).forEach((path, sql) -> {
			String now = current.get(path);
			if (now == null) {
				violations.add(path + ": deleted, but it is on main");
			}
			else if (!normalize(now).equals(normalize(sql))) {
				violations.add(path + ": edited, but it is on main; add a new migration instead");
			}
		});
		new TreeMap<>(current).keySet().stream().filter(path -> !base.containsKey(path)).forEach(path -> {
			String version = versionOf(path).orElse("");
			if (version.compareTo(newestOnBase) <= 0) {
				violations.add(path + ": version " + version + " is not newer than " + newestOnBase + " on main");
			}
		});
		return violations;
	}

	private static List<String> schemaViolations(String path, String own, String sql) {
		String code = stripCommentsAndLiterals(sql);
		List<String> violations = new ArrayList<>();
		Set<String> named = new TreeSet<>();
		Matcher qualified = Pattern.compile("(?<![\\w$.\"])\"?([a-z_][a-z0-9_]*)\"?\\s*\\.\\s*[\"a-z_*]",
				Pattern.CASE_INSENSITIVE)
			.matcher(code);
		while (qualified.find()) {
			String schema = qualified.group(1).toLowerCase();
			if (isForeign(schema, own)) {
				named.add(schema);
			}
		}
		Matcher schemaClause = Pattern
			.compile("\\bSCHEMA\\s+(?:IF\\s+(?:NOT\\s+)?EXISTS\\s+)?\"?([a-z_][a-z0-9_]*)\"?", Pattern.CASE_INSENSITIVE)
			.matcher(code);
		while (schemaClause.find()) {
			String schema = schemaClause.group(1).toLowerCase();
			if (!schema.equals(own)) {
				named.add(schema);
			}
		}
		named.forEach(schema -> violations.add(path + ": names schema '" + schema + "', which " + own + " does not own"));
		if (Pattern.compile("\\bsearch_path\\b", Pattern.CASE_INSENSITIVE).matcher(code).find()) {
			violations.add(path + ": changes search_path; qualify names with schema " + own + " instead");
		}
		Matcher created = Pattern.compile(
				"\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:UNLOGGED\\s+)?(TABLE|VIEW|MATERIALIZED\\s+VIEW|SEQUENCE|TYPE|DOMAIN|FUNCTION|PROCEDURE)\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\"?[a-z_][a-z0-9_]*\"?)(\\s*\\.)?",
				Pattern.CASE_INSENSITIVE)
			.matcher(code);
		while (created.find()) {
			if (created.group(3) == null) {
				violations.add(path + ": unqualified " + created.group(1).toUpperCase() + " " + created.group(2)
						+ "; create it as " + own + "." + created.group(2));
			}
		}
		return violations;
	}

	private static boolean isForeign(String schema, String own) {
		return !schema.equals(own) && (SCHEMAS.contains(schema) || schema.equals("public"));
	}

	private static String stripCommentsAndLiterals(String sql) {
		return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ").replaceAll("'(?:[^']|'')*'", "''");
	}

	private static boolean isValidTimestamp(String version) {
		try {
			LocalDateTime.parse(version, VERSION_FORMAT);
			return true;
		}
		catch (DateTimeParseException ex) {
			return false;
		}
	}

	private static Optional<String> versionOf(String path) {
		Matcher name = FILE_NAME.matcher(path.substring(path.lastIndexOf('/') + 1));
		return name.matches() ? Optional.of(name.group(1)) : Optional.empty();
	}

	private static String normalize(String sql) {
		return sql.replace("\r\n", "\n");
	}

	private static Set<String> schemas() {
		Set<String> schemas = new HashSet<>(ModuleLayersTest.LAYERS.keySet());
		schemas.add("platform");
		return Set.copyOf(schemas);
	}

	private static Map<String, String> currentMigrations() {
		Map<String, String> migrations = new TreeMap<>();
		try (Stream<Path> files = Files.walk(MIGRATIONS)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				String relative = MIGRATIONS.relativize(file).toString().replace('\\', '/');
				migrations.put(relative, Files.readString(file, StandardCharsets.UTF_8));
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return migrations;
	}

	private static String baseRef() {
		String configured = System.getProperty("vibefix.migrations.base-ref");
		List<String> candidates = configured == null ? List.of("origin/main", "main") : List.of(configured);
		for (String candidate : candidates) {
			if (git(true, "rev-parse", "--verify", "--quiet", candidate + "^{commit}") != null) {
				return candidate;
			}
		}
		throw new AssertionError("None of " + candidates + " exists; fetch main (or set -Dvibefix.migrations.base-ref)"
				+ " so new migrations can be compared with it");
	}

	private static Map<String, String> migrationsAt(String ref) {
		Map<String, String> migrations = new TreeMap<>();
		String listing = git(false, "ls-tree", "-r", "--name-only", ref, "--", GIT_MIGRATIONS);
		for (String path : listing.lines().filter(line -> !line.isBlank()).toList()) {
			String relative = path.substring(GIT_MIGRATIONS.length() + 1);
			migrations.put(relative, git(false, "show", ref + ":" + path));
		}
		return migrations;
	}

	/** Runs git at the repository root; returns null on failure when {@code mayFail}. */
	private static String git(boolean mayFail, String... args) {
		List<String> command = new ArrayList<>(List.of("git", "-C", repositoryRoot().toString()));
		command.addAll(List.of(args));
		try {
			Process process = new ProcessBuilder(command).redirectErrorStream(!mayFail).start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			int exit = process.waitFor();
			if (exit != 0) {
				if (mayFail) {
					return null;
				}
				throw new AssertionError("git " + String.join(" ", args) + " failed (" + exit + "): " + output);
			}
			return output;
		}
		catch (IOException ex) {
			throw new AssertionError("git is required to compare migrations with main", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AssertionError(ex);
		}
	}

	/** The repository root: the parent of the backend module this test runs in. */
	private static Path repositoryRoot() {
		return Path.of("").toAbsolutePath().getParent();
	}

}
