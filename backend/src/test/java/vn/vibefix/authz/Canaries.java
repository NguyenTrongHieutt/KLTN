package vn.vibefix.authz;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * The canary values of {@code src/test/resources/authz/canaries.csv} (AD-20, SG-55): unique marker
 * strings that fixtures seed into hidden content. Each canary lists the callers who may see it; to
 * every other caller it is hidden, so a new caller kind sees nothing until a canary allows it.
 * <p>
 * The file has the header {@code canary,value,visible_to,seeded_as}: {@code visible_to} is a
 * space-separated list of {@link MatrixCaller} names (it may be empty), and {@code seeded_as} says
 * where a fixture puts the value.
 */
public final class Canaries {

	public static final String RESOURCE = "authz/canaries.csv";

	static final List<String> HEADER = List.of("canary", "value", "visible_to", "seeded_as");

	/** One canary: its name, its marker value, and who may see it. */
	public record Canary(String name, String value, Set<MatrixCaller> visibleTo) {

		public Canary {
			visibleTo = Set.copyOf(visibleTo);
			if (value.length() < 7) {
				throw new IllegalArgumentException("Canary " + name + " is too short to be unique");
			}
		}

		public boolean hiddenFrom(MatrixCaller caller) {
			return !this.visibleTo.contains(caller);
		}

	}

	private final Map<String, Canary> byName;

	Canaries(List<Canary> canaries) {
		Map<String, Canary> map = new LinkedHashMap<>();
		Set<String> values = new HashSet<>();
		for (Canary canary : canaries) {
			if (map.put(canary.name(), canary) != null) {
				throw new IllegalArgumentException("Duplicate canary " + canary.name());
			}
			if (!values.add(canary.value())) {
				throw new IllegalArgumentException("Duplicate canary value " + canary.value());
			}
		}
		this.byName = Map.copyOf(map);
	}

	public static Canaries load() {
		try (InputStream in = Canaries.class.getClassLoader().getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException(RESOURCE + " is missing");
			}
			return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	static Canaries parse(String csv) {
		List<Canary> canaries = new ArrayList<>();
		boolean headerSeen = false;
		for (String line : csv.lines().map(String::strip).toList()) {
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			List<String> fields = Arrays.stream(line.split(",", -1)).map(String::strip).toList();
			if (!headerSeen) {
				if (!fields.equals(HEADER)) {
					throw new IllegalArgumentException("The canary header must be " + String.join(",", HEADER));
				}
				headerSeen = true;
				continue;
			}
			if (fields.size() != HEADER.size()) {
				throw new IllegalArgumentException("Canary line '" + line + "' needs " + HEADER.size() + " fields");
			}
			Set<MatrixCaller> visibleTo = EnumSet.noneOf(MatrixCaller.class);
			for (String caller : fields.get(2).split("\\s+")) {
				if (!caller.isEmpty()) {
					visibleTo.add(MatrixCaller.valueOf(caller));
				}
			}
			canaries.add(new Canary(fields.get(0), fields.get(1), visibleTo));
		}
		return new Canaries(canaries);
	}

	public Canary get(String name) {
		Canary canary = this.byName.get(name);
		if (canary == null) {
			throw new NoSuchElementException("No canary " + name);
		}
		return canary;
	}

	public List<Canary> all() {
		return List.copyOf(this.byName.values());
	}

	/** The canaries {@code caller} must never receive. */
	public List<Canary> hiddenFrom(MatrixCaller caller) {
		return this.byName.values().stream().filter(canary -> canary.hiddenFrom(caller)).toList();
	}

}
