package vn.vibefix.shared;

import org.jspecify.annotations.Nullable;

/**
 * A sandbox job that did not complete (AD-32): the sandbox could not be reached or answered too
 * late, did not know the job type, refused the input, or the job failed. The message names the job
 * type and the HTTP status when there was one; it never carries the job's input or output.
 */
public class SandboxJobException extends RuntimeException {

	private final String type;

	public SandboxJobException(String type, String message, @Nullable Throwable cause) {
		super("Sandbox job " + type + " failed: " + message, cause);
		this.type = type;
	}

	/** The job type that failed. */
	public String type() {
		return this.type;
	}

}
