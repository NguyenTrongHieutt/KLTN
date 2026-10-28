package vn.vibefix.notification.internal;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationCatalog;
import vn.vibefix.notification.NotificationType;
import vn.vibefix.notification.RecipientLookup;
import vn.vibefix.notification.TodoType;

/**
 * Every notification type and to-do key, and the checks every send and every open goes through
 * (AD-20, AD-27): a recipient's audience class must be one the entry allows and must be confirmed,
 * the parameters must be exactly those the entry declares, and the link must be a platform path.
 */
@Component
class NotificationRegistry {

	static final Pattern KEY = Pattern.compile("[a-z]+(\\.[a-z0-9_]+)+");

	/**
	 * A path inside the SPA, such as {@code /viec-can-lam} or {@code /cong-viec/{uuid}}, with at most one query
	 * parameter, such as CUS-7's segment ({@code /cong-viec?vai-tro=tham-gia}, Story 20.6).
	 */
	static final Pattern LINK = Pattern.compile("/[a-z0-9/-]*(\\?[a-z0-9-]+=[a-z0-9-]+)?");

	private final Map<String, NotificationType> notificationTypes = new TreeMap<>();

	private final Map<String, TodoType> todoTypes = new TreeMap<>();

	private final List<RecipientLookup> lookups;

	NotificationRegistry(List<NotificationCatalog> catalogs, List<RecipientLookup> lookups) {
		this.lookups = List.copyOf(lookups);
		for (NotificationCatalog catalog : catalogs) {
			catalog.notificationTypes().forEach(type -> add(this.notificationTypes, type.key(), type));
			catalog.todoTypes().forEach(type -> add(this.todoTypes, type.key(), type));
		}
	}

	private static <T> void add(Map<String, T> entries, String key, T entry) {
		if (!KEY.matcher(key).matches()) {
			throw new IllegalStateException("A notification or to-do key looks like module.event, not '" + key + "'");
		}
		if (entries.putIfAbsent(key, entry) != null) {
			throw new IllegalStateException("The registry has " + key + " twice");
		}
	}

	Collection<NotificationType> notificationTypes() {
		return this.notificationTypes.values();
	}

	Collection<TodoType> todoTypes() {
		return this.todoTypes.values();
	}

	Optional<NotificationType> notificationType(String key) {
		return Optional.ofNullable(this.notificationTypes.get(key));
	}

	NotificationType requireNotificationType(String key) {
		return notificationType(key)
			.orElseThrow(() -> new IllegalArgumentException("There is no notification type " + key));
	}

	TodoType requireTodoType(String key) {
		TodoType type = this.todoTypes.get(key);
		if (type == null) {
			throw new IllegalArgumentException("There is no to-do key " + key);
		}
		return type;
	}

	/**
	 * Refuses a recipient the entry may not reach: an audience class it does not allow, a Job-scoped
	 * class without the Job, a split party without the Milestone, a subject without its record, or a class
	 * no lookup confirms.
	 */
	void checkRecipient(String key, Set<Audience> allowed, UUID accountId, Audience audience, @Nullable UUID jobId,
			@Nullable UUID subjectRecordId, @Nullable UUID milestoneId) {
		if (!allowed.contains(audience)) {
			throw new IllegalArgumentException(key + " does not reach the audience class " + audience);
		}
		if (audience.jobScoped() && jobId == null) {
			throw new IllegalArgumentException(audience + " needs the Job (" + key + ")");
		}
		if (audience.milestoneScoped() && milestoneId == null) {
			throw new IllegalArgumentException(audience + " needs the Milestone (" + key + ")");
		}
		if (audience == Audience.SUBJECT) {
			if (subjectRecordId == null) {
				throw new IllegalArgumentException("SUBJECT needs the record the recipient is the subject of (" + key + ")");
			}
			// The sending module resolves its own subject (AD-20).
			return;
		}
		boolean confirmed = this.lookups.stream()
			.anyMatch(lookup -> lookup.audiences().contains(audience)
					&& lookup.holds(accountId, audience, jobId, milestoneId));
		if (!confirmed) {
			throw new IllegalArgumentException("The account is not confirmed as " + audience + " (" + key + ")");
		}
	}

	/** Refuses parameters other than those declared, or a declared one without a value. */
	static void checkParams(String key, Set<String> declared, Map<String, String> params) {
		if (!declared.equals(params.keySet())) {
			throw new IllegalArgumentException(key + " takes the parameters " + declared + ", not " + params.keySet());
		}
	}

	static void checkLink(String key, String link) {
		if (!LINK.matcher(link).matches()) {
			throw new IllegalArgumentException(key + " links to '" + link + "', not a platform path");
		}
	}

}
