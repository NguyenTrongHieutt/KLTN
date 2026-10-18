package vn.vibefix.shared;

import tools.jackson.databind.JsonNode;

/**
 * Runs one type of sandbox job inside the sandbox worker (AD-32).
 * <p>
 * Implementations are plain classes, never {@code @Component}: the sandbox worker picks up every
 * implementation under {@code vn.vibefix} by type, and the application context never instantiates
 * them. A handler may take constructor dependencies that exist in the worker, such as the
 * {@link org.springframework.core.env.Environment}; the worker has no database and no secrets.
 */
public interface SandboxJobHandler {

	/** The job type this handler runs, for example {@code HEALTH}; unique across handlers. */
	String type();

	/**
	 * Runs the job.
	 * @param input the job input, an empty object when the request had none
	 * @return the job output, sent back to the caller of {@link SandboxJobs#run}
	 * @throws IllegalArgumentException if the input is invalid (the caller gets a refusal)
	 */
	JsonNode handle(JsonNode input);

}
