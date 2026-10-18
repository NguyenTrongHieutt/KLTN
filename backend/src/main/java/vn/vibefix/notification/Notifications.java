package vn.vibefix.notification;

/** Sends notifications (AD-27): in-app items and email, from one type key. */
public interface Notifications {

	/**
	 * Records the notification for each recipient in the caller's transaction and, when the type's
	 * channel sends email, an email to each recipient with a known address.
	 * @throws IllegalArgumentException for an unknown type, a recipient whose audience class the type
	 * does not allow or that is not confirmed, a parameter the type does not declare or lacks, or a
	 * link that is not a platform path; nothing is recorded then
	 * @throws org.springframework.transaction.IllegalTransactionStateException without a transaction
	 */
	void send(NotificationRequest request);

}
