package vn.vibefix.operations;

import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * An operation as its requester sees it.
 * @param result the stored result, once {@link OperationStatus#SUCCEEDED}
 * @param errorCode the last failure's code, if any
 */
public record OperationTicket(UUID id, String key, OperationStatus status, @Nullable Map<String, Object> result,
		@Nullable String errorCode) {

}
