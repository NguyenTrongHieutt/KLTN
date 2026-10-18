package vn.vibefix.audit;

/**
 * The append-only Audit Log (AD-8, FR-63). There is deliberately no method to change or delete an
 * entry; the Administrator's reading view arrives with its own story.
 */
public interface AuditLog {

	/**
	 * Writes {@code entry} in the caller's transaction, stamped with the injected clock's time and
	 * the request's correlation ID when there is one.
	 * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is
	 * active: an entry must commit or roll back with the change it describes
	 */
	void record(AuditEntry entry);

}
