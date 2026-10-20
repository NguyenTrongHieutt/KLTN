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

	/** J4 started the Agreement draft (FR-16): the Mentor, due at the Agreement deadline. */
	static final TodoType JOB_DRAFT_AGREEMENT = new TodoType("job.draft_agreement", Set.of(Audience.MENTOR), 2,
			Set.of("job"));

	/** The Mentor shared a draft (FR-16): the Customer reviews it, due at the Agreement deadline. */
	static final TodoType JOB_REVIEW_AGREEMENT = new TodoType("job.review_agreement", Set.of(Audience.CUSTOMER), 1,
			Set.of("job"));

	/** The Customer asked for changes (FR-16): until the Mentor shares a new draft. */
	static final TodoType JOB_REVISE_AGREEMENT = new TodoType("job.revise_agreement", Set.of(Audience.MENTOR), 2, Set.of("job"));

	/** The Customer approved the draft (FR-17): until the Mentor confirms it. */
	static final TodoType JOB_CONFIRM_AGREEMENT_MENTOR = new TodoType("job.confirm_agreement_mentor", Set.of(Audience.MENTOR), 2,
			Set.of("job"));

	/** J4 (FR-75): the Mentor picks the Crew, until FR-17's Crew condition holds. */
	static final TodoType JOB_PICK_CREW = new TodoType("job.pick_crew", Set.of(Audience.MENTOR), 2, Set.of("job"));

	/** An assigned Crew member signs the NDA and confirms the latest Crew Terms version (FR-75). */
	static final TodoType JOB_CONFIRM_CREW_TERMS = new TodoType("job.confirm_crew_terms", Set.of(Audience.CREW_ASSIGNED), 1,
			Set.of("job"));

	/** A Customer invited this Mentor to apply (FR-14): until they apply or the Job leaves Open. */
	static final TodoType MATCHING_ANSWER_INVITATION_TO_APPLY = new TodoType("matching.answer_invitation_to_apply",
			Set.of(Audience.SUBJECT), 3, Set.of("job"));

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

	/** J5 by the selected Mentor's decline (FR-15): the Customer. */
	static final NotificationType JOB_SELECTION_DECLINED = new NotificationType("job.selection_declined",
			Set.of(Audience.CUSTOMER), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "alert", Set.of("mentor"));

	/** J4: the Applications on hold close (FR-15). */
	static final NotificationType MATCHING_APPLICATIONS_CLOSED = new NotificationType("matching.applications_closed",
			Set.of(Audience.SUBJECT), Channel.IN_APP, EmailClass.TRANSACTIONAL, "info", Set.of("job"));

	/** J5: the Applications on hold are considered again (FR-15). */
	static final NotificationType MATCHING_APPLICATIONS_REOPENED = new NotificationType("matching.applications_reopened",
			Set.of(Audience.SUBJECT), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "info", Set.of("job"));

	/** The FR-15 signature period passed without a signature: the Customer may choose again. */
	static final NotificationType MATCHING_NDA_OVERDUE = new NotificationType("matching.nda_overdue",
			Set.of(Audience.CUSTOMER), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "alert", Set.of("job"));

	/** A Customer invited this Mentor to apply to their Job Post (FR-14, UX-DR16). */
	static final NotificationType MATCHING_INVITED_TO_APPLY = new NotificationType("matching.invited_to_apply",
			Set.of(Audience.SUBJECT), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "mail",
			Set.of("customer", "job"));

	/** The Mentor shared an Agreement draft (FR-16): the Customer. */
	static final NotificationType JOB_AGREEMENT_DRAFT_READY = new NotificationType("job.agreement_draft_ready",
			Set.of(Audience.CUSTOMER), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "info", Set.of("job"));

	/** The Customer asked for changes on the shared draft (FR-16). */
	static final NotificationType JOB_AGREEMENT_CHANGES_REQUESTED = new NotificationType("job.agreement_changes_requested",
			Set.of(Audience.MENTOR), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "info", Set.of("job"));

	/** The Customer approved; the Mentor confirms once the Crew is confirmed (FR-17). */
	static final NotificationType JOB_AGREEMENT_AWAITING_CONFIRMATION = new NotificationType(
			"job.agreement_awaiting_confirmation", Set.of(Audience.MENTOR), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL,
			"check", Set.of("job"));

	/** The Agreement version 1 took effect (FR-17): it lists the Crew's GitHub usernames. */
	static final NotificationType JOB_AGREEMENT_CONFIRMED = new NotificationType("job.agreement_confirmed",
			Set.of(Audience.CUSTOMER, Audience.MENTOR, Audience.CREW_ACTIVE), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL,
			"check", Set.of("job", "crew"));

	/** A Milestone waits for the Customer's payment (FR-41): after the confirmation, Milestone 1. */
	static final NotificationType JOB_MILESTONE_AWAITING_FUNDING = new NotificationType("job.milestone_awaiting_funding",
			Set.of(Audience.CUSTOMER), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "info", Set.of("job", "n", "amount"));

	/** A Crew member was locked between the confirmation and Starting (FR-17, R-1 H1). */
	static final NotificationType JOB_CREW_RECONFIRM_NEEDED = new NotificationType("job.crew_reconfirm_needed",
			Set.of(Audience.MENTOR), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "alert", Set.of("job"));

	/** The Mentor added this Student to the Crew (FR-75). */
	static final NotificationType JOB_CREW_ASSIGNED = new NotificationType("job.crew_assigned", Set.of(Audience.CREW_ASSIGNED),
			Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "info", Set.of("job"));

	/** A Crew member signed the NDA and confirmed their share (FR-75): the Mentor. */
	static final NotificationType JOB_CREW_MEMBER_CONFIRMED = new NotificationType("job.crew_member_confirmed",
			Set.of(Audience.MENTOR), Channel.IN_APP, EmailClass.TRANSACTIONAL, "check", Set.of("job", "student"));

	/** A Crew member declined (FR-75): the Mentor picks someone else. */
	static final NotificationType JOB_CREW_MEMBER_DECLINED = new NotificationType("job.crew_member_declined",
			Set.of(Audience.MENTOR), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "alert", Set.of("job", "student"));

	/** The Mentor removed this Student from the Crew before the Job started (FR-75). */
	static final NotificationType JOB_CREW_MEMBER_REMOVED = new NotificationType("job.crew_member_removed",
			Set.of(Audience.SUBJECT), Channel.IN_APP, EmailClass.TRANSACTIONAL, "info", Set.of("job"));

	/** The Mentor saved new Crew Terms: every Crew member confirms again (FR-75, N-20). */
	static final NotificationType JOB_CREW_TERMS_CHANGED = new NotificationType("job.crew_terms_changed",
			Set.of(Audience.CREW_ASSIGNED), Channel.IN_APP, EmailClass.TRANSACTIONAL, "info", Set.of("job"));

	/** FR-17's Crew condition holds: the Mentor may confirm the Agreement. */
	static final NotificationType JOB_CREW_CONFIRMED = new NotificationType("job.crew_confirmed", Set.of(Audience.MENTOR),
			Channel.IN_APP, EmailClass.TRANSACTIONAL, "check", Set.of("job"));

	/** A Crew addition refused for a shared person-link signal (AD-19): every Administrator. */
	static final NotificationType JOB_CONFLICT_OF_INTEREST_REFUSED = new NotificationType("job.conflict_of_interest_refused",
			Set.of(Audience.ADMIN), Channel.IN_APP, EmailClass.TRANSACTIONAL, "alert", Set.of());

	/** Two participants of a running Job newly share a person-link signal (AD-19): every Administrator. */
	static final NotificationType JOB_PERSON_LINK_ALERT = new NotificationType("job.person_link_alert", Set.of(Audience.ADMIN),
			Channel.IN_APP, EmailClass.TRANSACTIONAL, "alert", Set.of("job", "first", "second"));

	/** J4 opened the Job Chat (FR-46): the Customer and the Mentor, in-app only. */
	static final NotificationType CHAT_CHAT_OPENED = new NotificationType("chat.chat_opened",
			Set.of(Audience.CUSTOMER, Audience.MENTOR), Channel.IN_APP, EmailClass.TRANSACTIONAL, "info", Set.of("job"));

	@Override
	public List<NotificationType> notificationTypes() {
		return List.of(SCHOOL_EMAIL_CODE, VERIFICATION_EXPIRING, VERIFICATION_EXPIRED, ADMIN_SIGN_IN_LOCKED,
				ADMINISTRATOR_CREATED, ADMINISTRATOR_ENROLMENT, STUDENT_CARD_APPROVED, STUDENT_CARD_REJECTED,
				MENTOR_APPLICATION_RECEIVED, MENTOR_APPROVED, MENTOR_REJECTED, TEAM_INVITATION_RECEIVED,
				TEAM_INVITATION_ANSWERED, TEAM_JOIN_REQUEST_RECEIVED, TEAM_JOIN_REQUEST_ANSWERED, TEAM_RELEASED,
				TEAM_MEMBER_LEFT, TEAM_MEMBER_REMOVED, SCANNING_INTAKE_SCAN_CLEAN, SCANNING_FINDING_OPENED,
				JOB_POST_LAPSED, MATCHING_APPLICATION_RECEIVED, MATCHING_APPLICATION_AUTO_WITHDRAWN,
				MATCHING_CONFLICT_OF_INTEREST_REFUSED, JOB_ASSIGNEE_SELECTED, MATCHING_APPLICATIONS_ON_HOLD,
				JOB_SELECTION_DECLINED, MATCHING_APPLICATIONS_CLOSED, MATCHING_APPLICATIONS_REOPENED, MATCHING_NDA_OVERDUE,
				MATCHING_INVITED_TO_APPLY, CHAT_CHAT_OPENED, JOB_AGREEMENT_DRAFT_READY, JOB_CREW_ASSIGNED,
				JOB_CREW_MEMBER_CONFIRMED, JOB_CREW_MEMBER_DECLINED, JOB_CREW_MEMBER_REMOVED, JOB_CREW_TERMS_CHANGED,
				JOB_CREW_CONFIRMED, JOB_CONFLICT_OF_INTEREST_REFUSED, JOB_PERSON_LINK_ALERT, JOB_AGREEMENT_CHANGES_REQUESTED,
				JOB_AGREEMENT_AWAITING_CONFIRMATION, JOB_AGREEMENT_CONFIRMED, JOB_MILESTONE_AWAITING_FUNDING, JOB_CREW_RECONFIRM_NEEDED);
	}

	@Override
	public List<TodoType> todoTypes() {
		return List.of(COMPLETE_MENTOR_APPLICATION, VERIFY_STUDENT, SCANNING_RESOLVE_INTAKE_FINDING,
				MATCHING_CHOOSE_ASSIGNEE, MATCHING_SIGN_NDA, MATCHING_ANSWER_INVITATION_TO_APPLY, JOB_DRAFT_AGREEMENT,
				JOB_REVIEW_AGREEMENT, JOB_PICK_CREW, JOB_CONFIRM_CREW_TERMS, JOB_REVISE_AGREEMENT, JOB_CONFIRM_AGREEMENT_MENTOR);
	}

}
