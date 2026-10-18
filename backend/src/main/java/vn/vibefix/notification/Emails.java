package vn.vibefix.notification;

/** Sends vibefix emails reliably: at least once, through the email lane (AD-6, AD-27). */
public interface Emails {

	/**
	 * Records {@code request} as an External Operation in the caller's transaction; the email lane
	 * sends it after the transaction commits. Sending the same causing record again changes nothing.
	 * @throws IllegalArgumentException for an unknown template, a template parameter without a value,
	 * or an address that is not one
	 * @throws org.springframework.transaction.IllegalTransactionStateException without a transaction
	 */
	void send(EmailRequest request);

}
