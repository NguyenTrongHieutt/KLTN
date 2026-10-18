package vn.vibefix.support;

import java.util.List;
import java.util.Set;

import vn.vibefix.notification.Audience;
import vn.vibefix.notification.Channel;
import vn.vibefix.notification.EmailClass;
import vn.vibefix.notification.NotificationCatalog;
import vn.vibefix.notification.NotificationType;
import vn.vibefix.notification.TodoType;

/**
 * Notification types and to-do keys for tests ({@code test.*}), next to the application's catalog;
 * their email templates are in {@code src/test/resources/templates/email}.
 */
public class TestNotificationCatalog implements NotificationCatalog {

	/** In-app and email to a subject, with one parameter. */
	public static final NotificationType SUBJECT_UPDATE = new NotificationType("test.subject_update",
			Set.of(Audience.SUBJECT), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "bell", Set.of("name"));

	/** In-app only, to a subject. */
	public static final NotificationType SUBJECT_INFO = new NotificationType("test.subject_info", Set.of(Audience.SUBJECT),
			Channel.IN_APP, EmailClass.TRANSACTIONAL, "info", Set.of());

	/** Email only, to a subject. */
	public static final NotificationType SUBJECT_EMAIL = new NotificationType("test.subject_email",
			Set.of(Audience.SUBJECT), Channel.EMAIL_ONLY, EmailClass.TRANSACTIONAL, "mail", Set.of());

	/** In-app and email to every Administrator. */
	public static final NotificationType ADMIN_ALERT = new NotificationType("test.admin_alert", Set.of(Audience.ADMIN),
			Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "alert", Set.of());

	/** In-app to the Customer of a Job: a Job-scoped class no lookup confirms yet. */
	public static final NotificationType JOB_UPDATE = new NotificationType("test.job_update", Set.of(Audience.CUSTOMER),
			Channel.IN_APP, EmailClass.TRANSACTIONAL, "briefcase", Set.of());

	public static final TodoType DECIDES = new TodoType("test.decides", Set.of(Audience.SUBJECT), 1, Set.of());

	public static final TodoType BLOCKS = new TodoType("test.blocks", Set.of(Audience.SUBJECT), 2, Set.of("name"));

	public static final TodoType OTHER = new TodoType("test.other", Set.of(Audience.SUBJECT), 3, Set.of());

	@Override
	public List<NotificationType> notificationTypes() {
		return List.of(SUBJECT_UPDATE, SUBJECT_INFO, SUBJECT_EMAIL, ADMIN_ALERT, JOB_UPDATE);
	}

	@Override
	public List<TodoType> todoTypes() {
		return List.of(DECIDES, BLOCKS, OTHER);
	}

}
