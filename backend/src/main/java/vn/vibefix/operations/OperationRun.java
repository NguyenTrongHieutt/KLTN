package vn.vibefix.operations;

import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * One claimed attempt of an operation, as its handler sees it.
 * @param id the operation's ID, also the {@code correlationId} of every log line the attempt writes
 * @param key {@code type:causingRecordId}
 * @param attempt this attempt's number, from 1
 * @param payload the immutable request, without personal data
 * @param personalData what the request holds about a person; {@code null} once the account was erased
 */
public record OperationRun(UUID id, String key, String type, int attempt, Map<String, Object> payload,
		@Nullable Map<String, Object> personalData) {

}
