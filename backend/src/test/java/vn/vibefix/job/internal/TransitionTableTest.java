package vn.vibefix.job.internal;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import vn.vibefix.job.internal.Transition.Machine;

/**
 * AD-4: the transition table is PRD section 4.0, written out here from the PRD as "from -> to", and
 * {@link JobLifecycle} is the only code that writes a state.
 */
class TransitionTableTest {

	private static final Map<String, String> SECTION_4_0 = Map.ofEntries(entry("J1", "DRAFT -> OPEN"),
			entry("J2", "OPEN -> DRAFT"), entry("J3", "OPEN -> SELECTING"), entry("J4", "SELECTING -> AGREEMENT"),
			entry("J5", "SELECTING -> OPEN"), entry("J6", "AGREEMENT -> STARTING"), entry("J7", "STARTING -> ACTIVE"),
			entry("J8", "ACTIVE -> CLOSING"), entry("J9", "CLOSING -> CLOSED|CANCELLED"),
			entry("J10", "AGREEMENT|STARTING|ACTIVE -> CANCELLED"), entry("M1", "PLANNED -> FUNDED"),
			entry("M2", "FUNDED|REVISION_REQUESTED -> SUBMITTED"), entry("M3", "SUBMITTED -> FUNDED|REVISION_REQUESTED"),
			entry("M4", "SUBMITTED -> REVISION_REQUESTED"), entry("M5", "SUBMITTED -> ACCEPTED"),
			entry("M6", "PLANNED -> CANCELLED"), entry("M7", "FUNDED|REVISION_REQUESTED|SUBMITTED -> CANCELLED"),
			entry("M8", "FUNDED|REVISION_REQUESTED|SUBMITTED -> SETTLED"));

	/** Who triggers each entry, as section 4.0 names the actors. */
	private static final Map<String, Set<Trigger>> TRIGGERS = Map.ofEntries(entry("J1", Set.of(Trigger.USER)),
			entry("J2", Set.of(Trigger.USER, Trigger.AUTO)), entry("J3", Set.of(Trigger.USER)),
			entry("J4", Set.of(Trigger.USER)), entry("J5", Set.of(Trigger.USER)), entry("J6", Set.of(Trigger.SYSTEM)),
			entry("J7", Set.of(Trigger.SYSTEM)), entry("J8", Set.of(Trigger.USER, Trigger.AUTO, Trigger.DECISION)),
			entry("J9", Set.of(Trigger.SYSTEM)), entry("J10", Set.of(Trigger.USER, Trigger.DECISION)),
			entry("M1", Set.of(Trigger.SYSTEM)), entry("M2", Set.of(Trigger.USER)), entry("M3", Set.of(Trigger.SYSTEM)),
			entry("M4", Set.of(Trigger.USER)), entry("M5", Set.of(Trigger.USER, Trigger.AUTO)),
			entry("M6", Set.of(Trigger.USER, Trigger.SYSTEM)), entry("M7", Set.of(Trigger.USER)),
			entry("M8", Set.of(Trigger.DECISION)));

	/** The modules of ARCHITECTURE-SPINE.md, L0 to L11. */
	private static final Set<String> MODULES = Set.of("shared", "audit", "files", "settings", "catalog", "github",
			"operations", "timers", "notification", "identity", "team", "scanning", "product", "job", "matching", "repo",
			"payment", "work", "chat", "delivery", "reputation", "dispute", "admin");

	@Test
	void theTableIsSection40AndNothingElse() {
		Map<String, String> table = new TreeMap<>();
		for (Transition transition : Transition.values()) {
			table.put(transition.name(),
					String.join("|", transition.from()) + " -> " + String.join("|", transition.to()));
		}

		assertThat(table).isEqualTo(new TreeMap<>(SECTION_4_0));
		assertThat(Arrays.stream(Transition.values()).collect(Collectors.toMap(Enum::name, Transition::triggers)))
			.isEqualTo(TRIGGERS);
		assertThat(Arrays.stream(Transition.values()).filter(transition -> transition.name().startsWith("J")))
			.allMatch(transition -> transition.machine() == Machine.JOB);
		assertThat(Arrays.stream(Transition.values()).filter(transition -> transition.name().startsWith("M")))
			.allMatch(transition -> transition.machine() == Machine.MILESTONE);
	}

	@Test
	void everyStateIsUsedAndTheFinalOnesAreNeverLeft() {
		assertThat(states(Machine.JOB)).isEqualTo(names(JobState.values()));
		assertThat(states(Machine.MILESTONE)).isEqualTo(names(MilestoneState.values()));
		assertThat(left(Machine.JOB)).doesNotContain("CLOSED", "CANCELLED");
		assertThat(left(Machine.MILESTONE)).doesNotContain("ACCEPTED", "CANCELLED", "SETTLED");
	}

	@Test
	void theFreezeRefusesM1ToM7AndOnlyJ1ToJ7M1M2M4AndM5HaveCommands() {
		assertThat(EnumSet.allOf(Transition.class).stream().filter(Transition::frozenBlocks))
			.containsExactly(Transition.M1, Transition.M2, Transition.M3, Transition.M4, Transition.M5, Transition.M6,
					Transition.M7);
		assertThat(EnumSet.allOf(Transition.class).stream().filter(Transition::reachable))
			.containsExactly(Transition.J1, Transition.J2, Transition.J3, Transition.J4, Transition.J5, Transition.J6,
					Transition.J7, Transition.M1, Transition.M2, Transition.M4, Transition.M5);
	}

	@Test
	void guardsAreTaggedWithTheirFrAndEffectsNameTheirModule() {
		Map<String, List<String>> untagged = new LinkedHashMap<>();
		for (Transition transition : Transition.values()) {
			transition.guards()
				.stream()
				.filter(guard -> !guard.fr().matches("FR-[0-9]+") || guard.text().isBlank())
				.forEach(guard -> untagged.computeIfAbsent(transition.name(), key -> new java.util.ArrayList<>())
					.add(guard.text()));
			transition.effects()
				.stream()
				.filter(effect -> !MODULES.contains(effect.module()) || effect.text().isBlank())
				.forEach(effect -> untagged.computeIfAbsent(transition.name(), key -> new java.util.ArrayList<>())
					.add(effect.module()));
			assertThat(transition.trigger()).as("the trigger of %s", transition).isNotBlank();
		}
		assertThat(untagged).isEmpty();
		assertThat(Transition.J1.guards()).extracting(Transition.Guard::fr).contains("FR-8");
		assertThat(Transition.J3.guards()).extracting(Transition.Guard::fr).contains("FR-13");
		assertThat(Transition.J4.guards()).extracting(Transition.Guard::fr).contains("FR-15");
		assertThat(Transition.J6.guards()).extracting(Transition.Guard::fr).contains("FR-75");
		assertThat(Transition.J1.auditAction()).isEqualTo("job.transition_j1");
	}

	/** No code but {@link JobLifecycle} sets the state column of a Job (or, later, of a Milestone). */
	@Test
	void onlyTheLifecycleWritesAState() throws IOException {
		Pattern update = Pattern.compile("(?is)UPDATE\\s+job\\.(job|milestone)\\b(.*?)(WHERE|\"\"\"|;)");
		Pattern state = Pattern.compile("(?i)[\\s,]state\\s*=");
		List<String> writers;
		try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
			writers = files.filter(file -> file.toString().endsWith(".java")).filter(file -> {
				Matcher matcher = update.matcher(read(file));
				while (matcher.find()) {
					if (state.matcher(matcher.group(2)).find()) {
						return true;
					}
				}
				return false;
			}).map(file -> file.getFileName().toString()).toList();
		}

		assertThat(writers).containsExactly("JobLifecycle.java");
	}

	private static Set<String> states(Machine machine) {
		return Arrays.stream(Transition.values())
			.filter(transition -> transition.machine() == machine)
			.flatMap(transition -> Stream.concat(transition.from().stream(), transition.to().stream()))
			.collect(Collectors.toSet());
	}

	private static Set<String> left(Machine machine) {
		return Arrays.stream(Transition.values())
			.filter(transition -> transition.machine() == machine)
			.flatMap(transition -> transition.from().stream())
			.collect(Collectors.toSet());
	}

	private static Set<String> names(Enum<?>[] values) {
		return Arrays.stream(values).map(Enum::name).collect(Collectors.toSet());
	}

	private static String read(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
	}

}
