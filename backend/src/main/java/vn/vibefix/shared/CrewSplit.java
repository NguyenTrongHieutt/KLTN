package vn.vibefix.shared;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The one split of an amount by a Milestone Split's shares (AD-33, FR-42): each party gets ⌊amount × pct / 100⌋,
 * the remainder goes to the remainder receiver, and a zero part is left out. The receiver is the split's party at
 * position 0: the Mentor in a normal Job, the member who signed first in a Self-mentored Job (AD-29, AD-47). The split
 * previews and every release use it, so they always agree.
 */
public final class CrewSplit {

	private CrewSplit() {
	}

	/**
	 * @param amountVnd the whole VND to split, zero or more
	 * @param shares each party's whole percentage, summing to 100
	 * @param remainderReceiverId the party who receives the remainder, also at 0%; must be among {@code shares}
	 * @return the parts in the order of {@code shares}, parts of zero left out; they sum to the amount
	 */
	public static Map<UUID, Long> split(long amountVnd, Map<UUID, Integer> shares, UUID remainderReceiverId) {
		Objects.requireNonNull(shares, "shares");
		if (amountVnd < 0) {
			throw new IllegalArgumentException("A negative amount does not split: " + amountVnd);
		}
		if (!shares.containsKey(remainderReceiverId)) {
			throw new IllegalArgumentException("The remainder receiver is a party of the split");
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
		parts.merge(remainderReceiverId, amountVnd - given, Long::sum);
		parts.values().removeIf(part -> part == 0);
		return parts;
	}

}
