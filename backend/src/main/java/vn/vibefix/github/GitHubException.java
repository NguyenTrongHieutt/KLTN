package vn.vibefix.github;

/**
 * GitHub refused or could not be reached; {@link #status()} is the HTTP status, 0 without an answer.
 * Its message never holds a token or a key.
 */
public class GitHubException extends RuntimeException {

	private final int status;

	public GitHubException(int status, String message) {
		super(message);
		this.status = status;
	}

	public int status() {
		return this.status;
	}

}
