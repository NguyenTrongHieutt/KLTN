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

	/** The Agreement confirmed (Milestone 1) or the previous Milestone accepted: the Customer pays (FR-41). */
	static final TodoType PAYMENT_FUND_MILESTONE = new TodoType("payment.fund_milestone", Set.of(Audience.CUSTOMER), 2,
			Set.of("n", "amount"));

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

	/** The IPN recorded a payment (FR-41): the receipt, with N-13's line in its email. */
	static final NotificationType PAYMENT_PAYMENT_RECEIVED = new NotificationType("payment.payment_received",
			Set.of(Audience.CUSTOMER), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "check", Set.of("job", "amount", "n"));

	/** The IPN reported a failure, or the order expired (AD-14). */
	static final NotificationType PAYMENT_PAYMENT_FAILED = new NotificationType("payment.payment_failed",
			Set.of(Audience.CUSTOMER), Channel.IN_APP, EmailClass.TRANSACTIONAL, "alert", Set.of("job", "n"));

	/** Money that could not apply is being refunded (FR-43); ledger-recorded while S2 is open. */
	static final NotificationType PAYMENT_REFUND_REQUESTED = new NotificationType("payment.refund_requested",
			Set.of(Audience.CUSTOMER), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "info", Set.of("amount", "job"));

	/** The gateway confirmed the refund (FR-43). */
	static final NotificationType PAYMENT_REFUND_CONFIRMED = new NotificationType("payment.refund_confirmed",
			Set.of(Audience.CUSTOMER), Channel.IN_APP, EmailClass.TRANSACTIONAL, "check", Set.of("amount", "job"));

	/** An IPN named no known order (AD-14): every Administrator. */
	static final NotificationType PAYMENT_UNKNOWN_PAYMENT = new NotificationType("payment.unknown_payment",
			Set.of(Audience.ADMIN), Channel.IN_APP, EmailClass.TRANSACTIONAL, "alert", Set.of());

	/** M1 (FR-41): the Mentor and the active Crew may start, with N-13's line in the email. */
	static final NotificationType JOB_MILESTONE_FUNDED = new NotificationType("job.milestone_funded",
			Set.of(Audience.MENTOR, Audience.CREW_ACTIVE), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "check",
			Set.of("job", "n"));

	/** J6 for a Code Job: the repositories are being prepared (FR-18). */
	static final NotificationType JOB_JOB_STARTING = new NotificationType("job.job_starting",
			Set.of(Audience.CUSTOMER, Audience.MENTOR, Audience.CREW_ACTIVE), Channel.IN_APP, EmailClass.TRANSACTIONAL, "info",
			Set.of("job"));

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

	/** The Mentor assigned a Work Item (FR-23): the assignee, in-app only. */
	static final NotificationType WORK_WORK_ITEM_ASSIGNED = new NotificationType("work.work_item_assigned",
			Set.of(Audience.CREW_ACTIVE), Channel.IN_APP, EmailClass.TRANSACTIONAL, "info", Set.of("mentor", "key"));

	/** A Work Item was submitted (FR-25): the Mentor, in-app. */
	static final NotificationType WORK_REVIEW_REQUESTED = new NotificationType("work.review_requested",
			Set.of(Audience.MENTOR), Channel.IN_APP, EmailClass.TRANSACTIONAL, "info", Set.of("name", "key"));

	/** The Mentor approved a Work Item (FR-25): the assignee, in-app. */
	static final NotificationType WORK_WORK_ITEM_APPROVED = new NotificationType("work.work_item_approved",
			Set.of(Audience.CREW_ACTIVE), Channel.IN_APP, EmailClass.TRANSACTIONAL, "check", Set.of("key"));

	/** The Mentor returned a Work Item (FR-25): the assignee, in-app and by email. */
	static final NotificationType WORK_WORK_ITEM_RETURNED = new NotificationType("work.work_item_returned",
			Set.of(Audience.CREW_ACTIVE), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "alert",
			Set.of("key", "reason"));

	/** A Work Item waits for the Mentor's review (FR-25): until it is approved or returned. */
	static final TodoType WORK_REVIEW_WORK_ITEM = new TodoType("work.review_work_item", Set.of(Audience.MENTOR), 2,
			Set.of("key", "title"));

	/** A returned Work Item waits for its assignee (FR-25): until it is submitted again. */
	static final TodoType WORK_FIX_RETURNED_ITEM = new TodoType("work.fix_returned_item", Set.of(Audience.CREW_ACTIVE), 2,
			Set.of("key"));

	/** M2 (FR-32): the Customer may check the handed-over Milestone, by the Auto-acceptance date. */
	static final NotificationType JOB_MILESTONE_SUBMITTED = new NotificationType("job.milestone_submitted",
			Set.of(Audience.CUSTOMER), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "check",
			Set.of("mentor", "n", "date"));

	/** M5 (FR-33, FR-35): the Customer and the Mentor learn the Milestone is accepted, or accepted automatically. */
	static final NotificationType JOB_MILESTONE_ACCEPTED = new NotificationType("job.milestone_accepted",
			Set.of(Audience.CUSTOMER, Audience.MENTOR), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "check",
			Set.of("n", "job", "how"));

	/** FR-35: the Customer is reminded, twice, when the System accepts a handover they have not decided. */
	static final NotificationType DELIVERY_ACCEPTANCE_REMINDER = new NotificationType("delivery.acceptance_reminder",
			Set.of(Audience.CUSTOMER), Channel.IN_APP_AND_EMAIL, EmailClass.REMINDER, "info", Set.of("n", "m", "date"));

	/** M4 (FR-33, FR-34): the Mentor learns what to fix and how many Revision Rounds are left. */
	static final NotificationType JOB_REVISION_REQUESTED = new NotificationType("job.revision_requested",
			Set.of(Audience.MENTOR), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "alert",
			Set.of("n", "count", "rounds"));

	/** A release (FR-42): each payee learns their part, with N-13's line in the email. */
	static final NotificationType PAYMENT_FUNDS_RELEASED = new NotificationType("payment.funds_released",
			Set.of(Audience.MENTOR, Audience.CREW_EVER_ACTIVE), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "check",
			Set.of("amount", "job", "n"));

	/** FR-37, FR-67: closure is proposed, by a person to the other side or by the System to both. */
	static final NotificationType JOB_CLOSURE_PROPOSED = new NotificationType("job.closure_proposed",
			Set.of(Audience.CUSTOMER, Audience.MENTOR), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "briefcase",
			Set.of("job", "proposer"));

	/** FR-39: the proposal was declined; the Job stays Active. */
	static final NotificationType JOB_CLOSURE_DECLINED = new NotificationType("job.closure_declined",
			Set.of(Audience.CUSTOMER, Audience.MENTOR), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "alert",
			Set.of("job"));

	/** FR-39: a person's closure proposal went unanswered; its proposer may open a Dispute. */
	static final NotificationType JOB_CLOSURE_UNANSWERED = new NotificationType("job.closure_unanswered",
			Set.of(Audience.CUSTOMER, Audience.MENTOR), Channel.IN_APP_AND_EMAIL, EmailClass.REMINDER, "info",
			Set.of("job"));

	/** J8: the Job is closing. */
	static final NotificationType JOB_JOB_CLOSING = new NotificationType("job.job_closing",
			Set.of(Audience.CUSTOMER, Audience.MENTOR, Audience.CREW_ACTIVE), Channel.IN_APP_AND_EMAIL,
			EmailClass.TRANSACTIONAL, "briefcase", Set.of("job"));

	/** J9 to Closed: the Job is done. */
	static final NotificationType JOB_JOB_CLOSED = new NotificationType("job.job_closed",
			Set.of(Audience.CUSTOMER, Audience.MENTOR, Audience.CREW_ACTIVE), Channel.IN_APP_AND_EMAIL,
			EmailClass.TRANSACTIONAL, "check", Set.of("job"));

	/** J8 or J10 (FR-66): the Mentor and every Crew member ever active are asked to confirm data deletion. */
	static final NotificationType REPO_DATA_DELETION_CONFIRMATION_PENDING = new NotificationType(
			"repo.data_deletion_confirmation_pending", Set.of(Audience.MENTOR, Audience.CREW_EVER_ACTIVE),
			Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "alert", Set.of("job"));

	/** The data-deletion confirmation waits (FR-66): until both items are confirmed; it never blocks closure. */
	static final TodoType REPO_CONFIRM_DATA_DELETION = new TodoType("repo.confirm_data_deletion",
			Set.of(Audience.MENTOR, Audience.CREW_EVER_ACTIVE), 3, Set.of("job"));

	/** J9 (FR-49): the Customer and the Mentor are asked to rate each other before the window ends. */
	static final NotificationType REPUTATION_RATING_WINDOW_OPENED = new NotificationType(
			"reputation.rating_window_opened", Set.of(Audience.CUSTOMER, Audience.MENTOR), Channel.IN_APP_AND_EMAIL,
			EmailClass.TRANSACTIONAL, "info", Set.of("party", "date"));

	/** J9 (FR-78): each Crew member who was ever active may give private feedback about the Mentor. */
	static final NotificationType REPUTATION_PRIVATE_FEEDBACK_INVITED = new NotificationType(
			"reputation.private_feedback_invited", Set.of(Audience.CREW_EVER_ACTIVE), Channel.IN_APP_AND_EMAIL,
			EmailClass.TRANSACTIONAL, "info", Set.of("date"));

	/** FR-49: the Ratings are shown, when both rated or at the window's end. */
	static final NotificationType REPUTATION_RATINGS_PUBLISHED = new NotificationType("reputation.ratings_published",
			Set.of(Audience.CUSTOMER, Audience.MENTOR, Audience.CREW_EVER_ACTIVE), Channel.IN_APP, EmailClass.TRANSACTIONAL,
			"check", Set.of("job"));

	/** FR-50: the Mentor drafted the Portfolio summary; the Customer approves, edits, or declines it. */
	static final NotificationType REPUTATION_PORTFOLIO_SUMMARY_AWAITING_APPROVAL = new NotificationType(
			"reputation.portfolio_summary_awaiting_approval", Set.of(Audience.CUSTOMER), Channel.IN_APP_AND_EMAIL,
			EmailClass.TRANSACTIONAL, "briefcase", Set.of("job", "date"));

	/** FR-50, FR-66: the person's Portfolio Entry is public after their data-deletion confirmation. */
	static final NotificationType REPUTATION_PORTFOLIO_ENTRY_PUBLISHED = new NotificationType(
			"reputation.portfolio_entry_published", Set.of(Audience.MENTOR, Audience.CREW_EVER_ACTIVE), Channel.IN_APP,
			EmailClass.TRANSACTIONAL, "check", Set.of("job"));

	/** The Portfolio summary waits for the Mentor's draft (FR-50): until drafted or the window ends. */
	static final TodoType REPUTATION_DRAFT_PORTFOLIO_SUMMARY = new TodoType("reputation.draft_portfolio_summary",
			Set.of(Audience.MENTOR), 3, Set.of("job"));

	/** The drafted summary waits for the Customer (FR-50): until decided or the window ends. */
	static final TodoType REPUTATION_APPROVE_PORTFOLIO_SUMMARY = new TodoType("reputation.approve_portfolio_summary",
			Set.of(Audience.CUSTOMER), 3, Set.of("job"));

	/** FR-11: the Mentor sent follow-up Jobs drafted from the Assessment Report. */
	static final NotificationType DELIVERY_FOLLOW_UP_DRAFTS_READY = new NotificationType("delivery.follow_up_drafts_ready",
			Set.of(Audience.CUSTOMER), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "briefcase", Set.of("job", "n"));

	/** An accepted Assessment Report waits for follow-up drafts (FR-11): until the Mentor submits them. */
	static final TodoType DELIVERY_DRAFT_FOLLOW_UPS = new TodoType("delivery.draft_follow_ups", Set.of(Audience.MENTOR), 3,
			Set.of("job"));

	/** Submitted follow-ups wait for the Customer (FR-11): until each is opened or discarded. */
	static final TodoType DELIVERY_APPROVE_FOLLOW_UPS = new TodoType("delivery.approve_follow_ups",
			Set.of(Audience.CUSTOMER), 3, Set.of("job"));

	/** FR-36: a due-date Change Request reaches the other side. */
	static final NotificationType JOB_CHANGE_REQUEST_PROPOSED = new NotificationType("job.change_request_proposed",
			Set.of(Audience.CUSTOMER, Audience.MENTOR), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "alert",
			Set.of("job"));

	/** FR-36: the Mentor added the time impact; the Customer may decide. */
	static final NotificationType JOB_CHANGE_REQUEST_IMPACT_ADDED = new NotificationType(
			"job.change_request_impact_added", Set.of(Audience.CUSTOMER), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL,
			"info", Set.of("job"));

	/** FR-36: both sides agreed; the new due dates and Agreement version are in force. */
	static final NotificationType JOB_CHANGE_REQUEST_APPLIED = new NotificationType("job.change_request_applied",
			Set.of(Audience.CUSTOMER, Audience.MENTOR), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "check",
			Set.of("job"));

	/** FR-36: the request lapsed with the Milestone's acceptance or the Job's end. */
	static final NotificationType JOB_CHANGE_REQUEST_LAPSED = new NotificationType("job.change_request_lapsed",
			Set.of(Audience.CUSTOMER, Audience.MENTOR), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "info",
			Set.of("job"));

	/** FR-40: the other side is asked to confirm a cancellation, with the full refund of held money. */
	static final NotificationType JOB_CANCELLATION_PROPOSED = new NotificationType("job.cancellation_proposed",
			Set.of(Audience.CUSTOMER, Audience.MENTOR), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "alert",
			Set.of("job", "date"));

	/** FR-40: the copied days passed without an answer; a Dispute is the way on. */
	static final NotificationType JOB_CANCELLATION_UNANSWERED = new NotificationType("job.cancellation_unanswered",
			Set.of(Audience.CUSTOMER, Audience.MENTOR), Channel.IN_APP_AND_EMAIL, EmailClass.REMINDER, "info",
			Set.of("job"));

	/** J10, or J9 to Cancelled: the Job is cancelled. */
	static final NotificationType JOB_JOB_CANCELLED = new NotificationType("job.job_cancelled",
			Set.of(Audience.CUSTOMER, Audience.MENTOR, Audience.CREW_ACTIVE), Channel.IN_APP_AND_EMAIL,
			EmailClass.TRANSACTIONAL, "briefcase", Set.of("job"));

	/** FR-40: the Agreement was not confirmed within 14 days; either side may cancel alone. */
	static final NotificationType JOB_AGREEMENT_DEADLINE_PASSED = new NotificationType("job.agreement_deadline_passed",
			Set.of(Audience.CUSTOMER, Audience.MENTOR), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "alert",
			Set.of("job"));

	/** FR-40: the Job did not reach Active within 7 days of Starting; either side may cancel alone. */
	static final NotificationType JOB_STARTING_DEADLINE_PASSED = new NotificationType("job.starting_deadline_passed",
			Set.of(Audience.CUSTOMER, Audience.MENTOR), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "alert",
			Set.of("job"));

	/** FR-52: a Dispute froze the Job; the other side and every Administrator learn it. */
	static final NotificationType DISPUTE_DISPUTE_OPENED = new NotificationType("dispute.dispute_opened",
			Set.of(Audience.CUSTOMER, Audience.MENTOR, Audience.ADMIN), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL,
			"alert", Set.of("job"));

	/** AD-31: a decision's execution failed at a step; the Job stays frozen until a retry succeeds. */
	static final NotificationType DISPUTE_EXECUTION_FAILED = new NotificationType("dispute.execution_failed",
			Set.of(Audience.ADMIN), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "alert", Set.of("step"));

	/** FR-54: the decision was carried out; the Job unfroze. */
	static final NotificationType DISPUTE_DISPUTE_DECIDED = new NotificationType("dispute.dispute_decided",
			Set.of(Audience.CUSTOMER, Audience.MENTOR), Channel.IN_APP_AND_EMAIL, EmailClass.TRANSACTIONAL, "check",
			Set.of("job"));

	/** A cancellation proposal waits for the other side (FR-40): until confirmed or withdrawn. */
	static final TodoType JOB_ANSWER_CANCELLATION = new TodoType("job.answer_cancellation",
			Set.of(Audience.CUSTOMER, Audience.MENTOR), 2, Set.of("job", "date"));

	/** The Customer's Change Request waits for the Mentor's time impact (FR-36). */
	static final TodoType JOB_ADD_CHANGE_IMPACT = new TodoType("job.add_change_impact", Set.of(Audience.MENTOR), 2,
			Set.of("job"));

	/** A Change Request waits for this side's answer: the Customer's approval, then the Mentor's confirmation. */
	static final TodoType JOB_ANSWER_CHANGE_REQUEST = new TodoType("job.answer_change_request",
			Set.of(Audience.CUSTOMER, Audience.MENTOR), 2, Set.of("job"));

	/** The Rating Window is open (FR-49): until the person rated everyone they rate, or the window ends. */
	static final TodoType REPUTATION_RATE = new TodoType("reputation.rate", Set.of(Audience.CUSTOMER, Audience.MENTOR), 3,
			Set.of("party"));

	/** Private feedback may be given (FR-78): until it is, or the window ends. */
	static final TodoType REPUTATION_GIVE_PRIVATE_FEEDBACK = new TodoType("reputation.give_private_feedback",
			Set.of(Audience.CREW_EVER_ACTIVE), 3, Set.of());

	/** A closure proposal waits for an answer: tier 1 for the System's (FR-67), 2 for a person's (FR-37). */
	static final TodoType JOB_ANSWER_CLOSURE = new TodoType("job.answer_closure", Set.of(Audience.CUSTOMER, Audience.MENTOR), 1,
			Set.of("job", "date"));

	/** A Funded or Revision Requested Milestone waits for the Mentor's handover (FR-32): until M2. */
	static final TodoType DELIVERY_HAND_OVER = new TodoType("delivery.hand_over", Set.of(Audience.MENTOR), 2, Set.of("n"));

	/** A handed-over Milestone waits for the Customer's decision (FR-33): it decides itself if ignored. */
	static final TodoType DELIVERY_REVIEW_HANDOVER = new TodoType("delivery.review_handover", Set.of(Audience.CUSTOMER), 1,
			Set.of("n", "date"));

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
				JOB_AGREEMENT_AWAITING_CONFIRMATION, JOB_AGREEMENT_CONFIRMED, JOB_MILESTONE_AWAITING_FUNDING, JOB_CREW_RECONFIRM_NEEDED,
				PAYMENT_PAYMENT_RECEIVED, PAYMENT_PAYMENT_FAILED, PAYMENT_UNKNOWN_PAYMENT, JOB_MILESTONE_FUNDED, JOB_MILESTONE_SUBMITTED,
				JOB_MILESTONE_ACCEPTED, JOB_REVISION_REQUESTED, PAYMENT_FUNDS_RELEASED, DELIVERY_ACCEPTANCE_REMINDER,
				JOB_CLOSURE_PROPOSED, JOB_CLOSURE_DECLINED, JOB_CLOSURE_UNANSWERED, JOB_JOB_CLOSING, JOB_JOB_CLOSED,
				REPO_DATA_DELETION_CONFIRMATION_PENDING, REPUTATION_RATING_WINDOW_OPENED,
				REPUTATION_PRIVATE_FEEDBACK_INVITED, REPUTATION_RATINGS_PUBLISHED,
				REPUTATION_PORTFOLIO_SUMMARY_AWAITING_APPROVAL, REPUTATION_PORTFOLIO_ENTRY_PUBLISHED,
				DELIVERY_FOLLOW_UP_DRAFTS_READY, JOB_CHANGE_REQUEST_PROPOSED, JOB_CHANGE_REQUEST_IMPACT_ADDED,
				JOB_CHANGE_REQUEST_APPLIED, JOB_CHANGE_REQUEST_LAPSED, JOB_CANCELLATION_PROPOSED,
				JOB_CANCELLATION_UNANSWERED, JOB_JOB_CANCELLED, JOB_AGREEMENT_DEADLINE_PASSED, JOB_STARTING_DEADLINE_PASSED,
				DISPUTE_DISPUTE_OPENED, DISPUTE_EXECUTION_FAILED, DISPUTE_DISPUTE_DECIDED,
				JOB_JOB_STARTING,
				PAYMENT_REFUND_REQUESTED, PAYMENT_REFUND_CONFIRMED, WORK_WORK_ITEM_ASSIGNED,
				WORK_REVIEW_REQUESTED, WORK_WORK_ITEM_APPROVED, WORK_WORK_ITEM_RETURNED);
	}

	@Override
	public List<TodoType> todoTypes() {
		return List.of(COMPLETE_MENTOR_APPLICATION, VERIFY_STUDENT, SCANNING_RESOLVE_INTAKE_FINDING,
				MATCHING_CHOOSE_ASSIGNEE, MATCHING_SIGN_NDA, MATCHING_ANSWER_INVITATION_TO_APPLY, JOB_DRAFT_AGREEMENT,
				JOB_REVIEW_AGREEMENT, JOB_PICK_CREW, JOB_CONFIRM_CREW_TERMS, JOB_REVISE_AGREEMENT, JOB_CONFIRM_AGREEMENT_MENTOR,
				PAYMENT_FUND_MILESTONE, WORK_REVIEW_WORK_ITEM, WORK_FIX_RETURNED_ITEM, DELIVERY_HAND_OVER,
				DELIVERY_REVIEW_HANDOVER, JOB_ANSWER_CLOSURE, REPO_CONFIRM_DATA_DELETION, REPUTATION_RATE,
				REPUTATION_GIVE_PRIVATE_FEEDBACK, REPUTATION_DRAFT_PORTFOLIO_SUMMARY, REPUTATION_APPROVE_PORTFOLIO_SUMMARY,
				DELIVERY_DRAFT_FOLLOW_UPS, DELIVERY_APPROVE_FOLLOW_UPS, JOB_ADD_CHANGE_IMPACT, JOB_ANSWER_CHANGE_REQUEST,
				JOB_ANSWER_CANCELLATION);
	}

}
