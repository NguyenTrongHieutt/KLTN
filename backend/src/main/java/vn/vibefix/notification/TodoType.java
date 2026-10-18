package vn.vibefix.notification;

import java.util.Objects;
import java.util.Set;

/**
 * One to-do key of the registry (AD-27, EXPERIENCE.md To-do Items): the audience classes it may
 * reach, its ordering tier (1 decides automatically if ignored, 2 blocks the Job, 3 everything
 * else), and the parameters of its strip text {@code todo.{key}} in {@code vi.json}.
 */
public record TodoType(String key, Set<Audience> audiences, int tier, Set<String> params) {

	public TodoType {
		Objects.requireNonNull(key, "key");
		audiences = Set.copyOf(audiences);
		params = Set.copyOf(params);
		if (audiences.isEmpty()) {
			throw new IllegalArgumentException("To-do key " + key + " reaches at least one audience class");
		}
		if (tier < 1 || tier > 3) {
			throw new IllegalArgumentException("A to-do tier is 1, 2 or 3");
		}
	}

}
