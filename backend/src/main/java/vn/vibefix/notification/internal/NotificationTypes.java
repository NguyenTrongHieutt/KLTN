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

	/** An Intake Finding to resolve (FR-8, FR-65): its Customer, until it is resolved or marked a false positive. */
	static final TodoType SCANNING_RESOLVE_INTAKE_FINDING = new TodoType("scanning.resolve_intake_finding",
			Set.of(Audience.SUBJECT), 2, Set.of("product"));

	/** An unverified Student; opened at sign-up as a Student or when verification expires (FR-5). */
	/** The Customer's Job has open Applications to choose from (FR-15, UX-DR15). */
	static final TodoType MATCHING_CHOOSE_ASSIGNEE = new TodoType("matching.choose_assignee", Set.of(Audience.CUSTOMER),
			1, Set.of("n"));

	/** The selected Mentor signs the NDA within the FR-15 period (UX-DR15). */
	static final TodoType MATCHING_SIGN_NDA = new TodoType("matching.sign_nda", Set.of(Audience.MENTOR), 2, Set.of("job"));

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

	/** 5 failed Administrator sign-ins locked an Administrator's email (AD-34): every other Administrator. */
	static final NotificationType ADMIN_SIGN_IN_LOCKED = new NotificationType("identity.admin_sign_in_locked",
			Set.of(Audience.ADMIN), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "alert", Set.of("email"));

	/** An Administrator account was created (FR-2, SG-10): every Administrator, on ADM-1 and by email. */
	static final NotificationType ADMINISTRATOR_CREATED = new NotificationType("identity.administrator_created",
			Set.of(Audience.ADMIN), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "info", Set.of("email"));

	/** The new Administrator's enrolment link (FR-2): email only, to the new account's address. */
	static final NotificationType ADMINISTRATOR_ENROLMENT = new NotificationType("identity.administrator_enrolment",
			Set.of(Audience.SUBJECT), Channel.EMAIL_ONLY, EmailClass.TRANSACTIONAL, "mail", Set.of("by"));

	/** An Administrator approved a Student's card (FR-5): the Student is verified. */
	static final NotificationType STUDENT_CARD_APPROVED = new NotificationType("identity.student_card_approved",
			Set.of(Audience.SUBJECT), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "check", Set.of());

	/** An Administrator rejected a Student's card with a reason (FR-5); they may upload another. */
	static final NotificationType STUDENT_CARD_REJECTED = new NotificationType("identity.student_card_rejected",
			Set.of(Audience.SUBJECT), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "alert", Set.of("reason"));

	/** A Mentor submitted an application (FR-74): every Administrator, in-app. */
	static final NotificationType MENTOR_APPLICATION_RECEIVED = new NotificationType("identity.mentor_application_received",
			Set.of(Audience.ADMIN), Channel.IN_APP, EmailClass.TRANSACTIONAL, "info", Set.of());

	/** An Administrator approved a Mentor's application (FR-74): every Mentor action opens. */
	static final NotificationType MENTOR_APPROVED = new NotificationType("identity.mentor_approved",
			Set.of(Audience.SUBJECT), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "check", Set.of());

	/** An Administrator rejected a Mentor's application with a reason (FR-74); they may submit again after 30 days. */
	static final NotificationType MENTOR_REJECTED = new NotificationType("identity.mentor_rejected",
			Set.of(Audience.SUBJECT), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "alert", Set.of("reason"));

	/** A Mentor invited a Student into their Team (FR-6): the Student, by email too. */
	static final NotificationType TEAM_INVITATION_RECEIVED = new NotificationType("team.invitation_received",
			Set.of(Audience.SUBJECT), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "users", Set.of("mentor"));

	/** The invited Student accepted, declined, or let the invitation lapse: the Mentor, in-app. */
	static final NotificationType TEAM_INVITATION_ANSWERED = new NotificationType("team.invitation_answered",
			Set.of(Audience.SUBJECT), Channel.IN_APP, EmailClass.TRANSACTIONAL, "users", Set.of("name", "answer"));

	/** A Student asked to join the Mentor's Team (FR-6): the Mentor, by email too. */
	static final NotificationType TEAM_JOIN_REQUEST_RECEIVED = new NotificationType("team.join_request_received",
			Set.of(Audience.SUBJECT), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "users", Set.of("name"));

	/** The Mentor accepted, declined, or let the join request lapse: the Student, by email too. */
	static final NotificationType TEAM_JOIN_REQUEST_ANSWERED = new NotificationType("team.join_request_answered",
			Set.of(Audience.SUBJECT), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "users",
			Set.of("mentor", "answer"));

	/** The Mentor is gone, so the Team released the Student (FR-6, AD-37). */
	static final NotificationType TEAM_RELEASED = new NotificationType("team.released", Set.of(Audience.SUBJECT),
			Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "alert", Set.of("mentor"));

	/** A Student left the Team (FR-6): the Mentor, in-app. */
	static final NotificationType TEAM_MEMBER_LEFT = new NotificationType("team.member_left", Set.of(Audience.SUBJECT),
			Channel.IN_APP, EmailClass.TRANSACTIONAL, "users", Set.of("name"));

	/** The Mentor removed a Student (FR-6): the Student, by email too. */
	static final NotificationType TEAM_MEMBER_REMOVED = new NotificationType("team.member_removed",
			Set.of(Audience.SUBJECT), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "alert", Set.of("mentor"));

	/** An intake scan found no credential (FR-8): the Customer, in-app. */
	static final NotificationType SCANNING_INTAKE_SCAN_CLEAN = new NotificationType("scanning.intake_scan_clean",
			Set.of(Audience.SUBJECT), Channel.IN_APP, EmailClass.TRANSACTIONAL, "check", Set.of("product"));

	/** A scan opened a Finding (FR-8): whoever must act on it, by email too; intake Findings go to the Customer. */
	static final NotificationType SCANNING_FINDING_OPENED = new NotificationType("scanning.finding_opened",
			Set.of(Audience.SUBJECT), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "alert", Set.of("type", "product"));

	/** An Open Job Post's selection period lapsed and it returned to Draft (FR-9): the Customer. */
	static final NotificationType JOB_POST_LAPSED = new NotificationType("job.job_post_lapsed",
			Set.of(Audience.CUSTOMER), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "info", Set.of("job"));

	/** The first Application to the Customer's Job Post (FR-13). */
	static final NotificationType MATCHING_APPLICATION_RECEIVED = new NotificationType("matching.application_received",
			Set.of(Audience.CUSTOMER), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "users", Set.of("job"));

	/** A locked or revoked Mentor's Application was withdrawn by the System (FR-13, AD-19). */
	static final NotificationType MATCHING_APPLICATION_AUTO_WITHDRAWN = new NotificationType(
			"matching.application_auto_withdrawn", Set.of(Audience.CUSTOMER), Channel.IN_APP, EmailClass.TRANSACTIONAL,
			"alert", Set.of("mentor", "job"));

	/** An Application refused for a shared person-link signal (FR-13, AD-19): every Administrator. */
	static final NotificationType MATCHING_CONFLICT_OF_INTEREST_REFUSED = new NotificationType(
			"matching.conflict_of_interest_refused", Set.of(Audience.ADMIN), Channel.IN_APP_AND_EMAIL,
			EmailClass.TRANSACTIONAL, "alert", Set.of());

	/** J3 chose this Mentor (FR-15). */
	static final NotificationType JOB_ASSIGNEE_SELECTED = new NotificationType("job.assignee_selected",
			Set.of(Audience.MENTOR), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "check", Set.of("job"));

	/** J3 chose another Mentor: this Application waits (FR-15). */
	static final NotificationType MATCHING_APPLICATIONS_ON_HOLD = new NotificationType("matching.applications_on_hold",
			Set.of(Audience.SUBJECT), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "info", Set.of("job"));

	@Override
	public List<NotificationType> notificationTypes() {
		return List.of(SCHOOL_EMAIL_CODE, VERIFICATION_EXPIRING, VERIFICATION_EXPIRED, ADMIN_SIGN_IN_LOCKED,
				ADMINISTRATOR_CREATED, ADMINISTRATOR_ENROLMENT, STUDENT_CARD_APPROVED, STUDENT_CARD_REJECTED,
				MENTOR_APPLICATION_RECEIVED, MENTOR_APPROVED, MENTOR_REJECTED, TEAM_INVITATION_RECEIVED,
				TEAM_INVITATION_ANSWERED, TEAM_JOIN_REQUEST_RECEIVED, TEAM_JOIN_REQUEST_ANSWERED, TEAM_RELEASED,
				TEAM_MEMBER_LEFT, TEAM_MEMBER_REMOVED, SCANNING_INTAKE_SCAN_CLEAN, SCANNING_FINDING_OPENED,
				JOB_POST_LAPSED, MATCHING_APPLICATION_RECEIVED, MATCHING_APPLICATION_AUTO_WITHDRAWN,
				MATCHING_CONFLICT_OF_INTEREST_REFUSED, JOB_ASSIGNEE_SELECTED, MATCHING_APPLICATIONS_ON_HOLD);
	}

	@Override
	public List<TodoType> todoTypes() {
		return List.of(COMPLETE_MENTOR_APPLICATION, VERIFY_STUDENT, SCANNING_RESOLVE_INTAKE_FINDING,
				MATCHING_CHOOSE_ASSIGNEE, MATCHING_SIGN_NDA);
	}

}
