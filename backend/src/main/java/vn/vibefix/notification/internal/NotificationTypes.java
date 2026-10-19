package vn.vibefix.notification.internal;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import vn.vibefix.notification.Audience;
import vn.vibefix.notification.Channel;
import vn.vibefix.notification.EmailClass;
import vn.vibefix.notification.NotificationCatalog;
import vn.vibefix.notification.NotificationType;
import vn.vibefix.notification.TodoType;

/**
 * The application's type registry (AD-27, AD-20), mirroring EXPERIENCE.md's Notification Inventory
 * and To-do Items: each story adds the types and to-do keys its module sends, with their texts in
 * {@code vi.json} and their email templates ({@code NotificationCatalogTest} checks both).
 */
@Component
class NotificationTypes implements NotificationCatalog {

	/** A Mentor account without a submitted application; opened at sign-up as a Mentor (FR-74). */
	static final TodoType COMPLETE_MENTOR_APPLICATION = new TodoType("identity.complete_mentor_application",
			Set.of(Audience.SUBJECT), 1, Set.of());

	/** An unverified Student; opened at sign-up as a Student or when verification expires (FR-5). */
	static final TodoType VERIFY_STUDENT = new TodoType("identity.verify_student", Set.of(Audience.SUBJECT), 3, Set.of());

	/** The school email code (FR-5): email only, to the address being verified, ahead of reminders (AD-34). */
	static final NotificationType SCHOOL_EMAIL_CODE = new NotificationType("identity.school_email_code",
			Set.of(Audience.SUBJECT), Channel.EMAIL_ONLY, EmailClass.ONE_TIME_CODE, "mail", Set.of("code"));

	/** 14 days before a Student's verification expires (FR-5, UX-DR16): a reminder, with the last valid day. */
	static final NotificationType VERIFICATION_EXPIRING = new NotificationType("identity.verification_expiring",
			Set.of(Audience.SUBJECT), Channel.IN_APP_AND_EMAIL, EmailClass.REMINDER, "info", Set.of("date"));

	/** A Student's verification expired on 30 September (FR-5): they renew to be picked for Crews again. */
	static final NotificationType VERIFICATION_EXPIRED = new NotificationType("identity.verification_expired",
			Set.of(Audience.SUBJECT), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "alert", Set.of());

	@Override
	public List<NotificationType> notificationTypes() {
		return List.of(SCHOOL_EMAIL_CODE, VERIFICATION_EXPIRING, VERIFICATION_EXPIRED);
	}

	@Override
	public List<TodoType> todoTypes() {
		return List.of(COMPLETE_MENTOR_APPLICATION, VERIFY_STUDENT);
	}

}
