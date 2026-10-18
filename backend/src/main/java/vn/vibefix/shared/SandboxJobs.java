package vn.vibefix.shared;

import tools.jackson.databind.JsonNode;

/**
 * The job channel to the sandbox (AD-32). Untrusted content (clones, scans, ZIP builds, image
 * re-encoding) is handled by the {@code sandbox} service, which runs the same image under the
 * profile {@code sandbox} with no platform secrets and no database. A module sends a job by type and
 * waits for the output of the {@link SandboxJobHandler} that runs it there. Outbound URL checks run
 * in the {@code checker} service instead: the same worker, serving only the job types it lists.
 */
public interface SandboxJobs {

	/**
	 * Runs one job in the sandbox and returns its result.
	 * @param type the job type, which names exactly one {@link SandboxJobHandler}
	 * @param input the job input; the handler validates it
	 * @throws SandboxJobException if the sandbox cannot be reached or answers too late, does not
	 * know the type, refuses the input, or the job fails
	 */
	SandboxJobResult run(String type, JsonNode input);

}
