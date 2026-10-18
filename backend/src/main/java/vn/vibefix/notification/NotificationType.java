package vn.vibefix.notification;

import java.util.Objects;
import java.util.Set;

/**
 * One notification type of the registry (AD-27, AD-20): its key ({@code {sending module}.{event}}),
 * the audience classes it may reach, its channel, the AD-34 class of its email, the icon the SPA
 * shows, and the parameters its texts use. Its in-app text is {@code notification.{key}.title} (and
 * an optional {@code .body}) in {@code vi.json}; its email is {@code templates/email/{key}.txt}.
 * Every parameter is shown to every audience the type allows: a type whose audiences must read
 * different things is two types.
 */
public record NotificationType(String key, Set<Audience> audiences, Channel channel, EmailClass emailClass, String icon,
		Set<String> params) {

	public NotificationType {
		Objects.requireNonNull(key, "key");
		audiences = Set.copyOf(audiences);
		Objects.requireNonNull(channel, "channel");
		Objects.requireNonNull(emailClass, "emailClass");
		Objects.requireNonNull(icon, "icon");
		params = Set.copyOf(params);
		if (audiences.isEmpty()) {
			throw new IllegalArgumentException("Notification type " + key + " reaches at least one audience class");
		}
	}

}
