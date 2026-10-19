package vn.vibefix.shared;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The one split of an amount by Crew Terms shares (AD-33, FR-42): each party gets ⌊amount × pct / 100⌋,
 * the remainder below one dong goes to the Mentor, and a zero part is left out. The Crew panel's
 * example amounts and every release use it, so they always agree.
 */
public final class CrewSplit {

	private CrewSplit() {
	}

	/**
	 * @param amountVnd the whole VND to split, zero or more
	 * @param shares each party's whole percentage, summing to 100
	 * @param mentorId the party who receives the remainder; must be among {@code shares}
	 * @return the parts in the order of {@code shares}, parts of zero left out; they sum to the amount
	 */
	public static Map<UUID, Long> split(long amountVnd, Map<UUID, Integer> shares, UUID mentorId) {
		Objects.requireNonNull(shares, "shares");
		if (amountVnd < 0) {
			throw new IllegalArgumentException("A negative amount does not split: " + amountVnd);
		}
		if (!shares.containsKey(mentorId)) {
			throw new IllegalArgumentException("The Mentor is a party of every Crew Terms version");
		}
		if (shares.values().stream().mapToInt(Integer::intValue).sum() != 100
				|| shares.values().stream().anyMatch(percent -> percent < 0)) {
			throw new IllegalArgumentException("Shares are whole percentages summing to 100");
		}
		Map<UUID, Long> parts = new LinkedHashMap<>();
		long given = 0;
		for (Map.Entry<UUID, Integer> share : shares.entrySet()) {
			long part = Math.multiplyExact(amountVnd, share.getValue()) / 100;
			parts.put(share.getKey(), part);
			given += part;
		}
		parts.merge(mentorId, amountVnd - given, Long::sum);
		parts.values().removeIf(part -> part == 0);
		return parts;
	}

}
