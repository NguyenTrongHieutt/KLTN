package vn.vibefix.shared;

import tools.jackson.databind.JsonNode;

/**
 * The outcome of one sandbox job (AD-32).
 *
 * @param type the job type that ran
 * @param output what the {@link SandboxJobHandler} returned
 */
public record SandboxJobResult(String type, JsonNode output) {
}
