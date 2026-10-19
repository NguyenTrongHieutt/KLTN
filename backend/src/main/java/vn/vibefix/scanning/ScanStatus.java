package vn.vibefix.scanning;

/** Where a scan run stands. */
public enum ScanStatus {

	/** Requested; its operation waits for the scan lane. */
	PENDING,

	/** An attempt is reading the history. */
	RUNNING,

	/** An attempt failed or timed out; the next one is scheduled. */
	RETRYING,

	/** The history was read and its Findings recorded. */
	SUCCEEDED,

	/** Every attempt failed, or the repository cannot be scanned; it waits in the Administrator queue. */
	FAILED;

	/** Whether CUS-4 keeps polling (every 5 s). */
	public boolean inProgress() {
		return this == PENDING || this == RUNNING;
	}

}
