package vn.vibefix.shared;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * FR-69, AD-2: content a user may file an Abuse Report on. The module that owns a kind implements it with
 * its own read rules, so a report names only what the reporter can see now; {@code admin} files the
 * report, queues it, and decides it.
 */
public interface ReportableContent {

	/**
	 * The kinds this module answers for: {@code PRODUCT}, {@code JOB_POST}, {@code PAGE}, {@code RATING},
	 * {@code JOB_CHAT_MESSAGE}, {@code CREW_CHAT_MESSAGE}, {@code WORK_ITEM_NOTE}, or {@code USER}.
	 */
	Set<String> kinds();

	/**
	 * What a report on the item names.
	 * @param jobId the Job the item belongs to, for a grant and the queue; {@code null} for a Product or a user
	 * @param productId the Product whose deletion deletes the report; {@code null} for a user
	 * @param authorId who wrote the item, or the reported user
	 * @param label what ADM-6 shows: a title or a name, never a message's or a note's text
	 */
	record Reported(@Nullable UUID jobId, @Nullable UUID productId, @Nullable UUID authorId, String label) {
	}

	/** The item when {@code caller} can see it now; empty otherwise, or for a kind this module does not answer. */
	Optional<Reported> reportable(String kind, UUID id, Caller caller);

}
