package vn.vibefix.shared;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The body the application sends to the sandbox worker for one job (AD-32).
 *
 * @param type the job type, which names exactly one {@link SandboxJobHandler}
 * @param input the job input; the worker passes an empty object when it is missing
 */
public record SandboxJobRequest(String type, @Nullable JsonNode input) {
}
