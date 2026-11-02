package vn.vibefix.dispute.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.chat.ChatEvidence;
import vn.vibefix.chat.ChatEvidence.ChatExtent;
import vn.vibefix.delivery.DeliveryEvidence;
import vn.vibefix.identity.PersonSignals;
import vn.vibefix.job.MilestoneSplits;
import vn.vibefix.job.MilestoneSplits.SplitCase;
import vn.vibefix.work.WorkEvidence;

/**
 * A Split Resolution decision's evidence snapshot (SG-27, AD-24, AD-40), written with the decision from the queries the Split
 * Evidence Package reads, as {@link EvidenceSnapshots} does for a Dispute: IDs, versions, counts, never copies of the content
 * and never an account ID. It stays with the decision after the Job's Crew Chat and Work Items go, so the decision remains
 * explainable.
 */
@Component
class SplitEvidenceSnapshots {

	/** @param confirmations how many parties confirmed it */
	record SplitSnapshotVersion(int versionNo, String kind, int confirmations, Instant createdAt) {
	}

	record SplitSnapshotWorkItem(UUID workItemId, int number, long version, String status) {
	}

	record SplitSnapshotCommit(UUID handoverId, String sha) {
	}

	/** @param lastMessageId the newest Crew Chat message of the Milestone's interval when the decision was made */
	record SplitSnapshotChat(int messageCount, @Nullable UUID lastMessageId, @Nullable Instant lastSentAt) {
	}

	/**
	 * Everything the decision was based on, as it stood at {@code takenAt}.
	 * @param personLinks how many matched pairs the package listed
	 */
	record SplitEvidenceSnapshot(Instant takenAt, List<SplitSnapshotVersion> versions, List<SplitSnapshotWorkItem> workItems,
			List<SplitSnapshotCommit> commits, SplitSnapshotChat crewChat, int personLinks) {
	}

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final MilestoneSplits splits;

	private final WorkEvidence work;

	private final DeliveryEvidence deliveries;

	private final ChatEvidence chat;

	private final PersonSignals signals;

	SplitEvidenceSnapshots(MilestoneSplits splits, WorkEvidence work, DeliveryEvidence deliveries, ChatEvidence chat,
			PersonSignals signals) {
		this.splits = splits;
		this.work = work;
		this.deliveries = deliveries;
		this.chat = chat;
		this.signals = signals;
	}

	/** The split's evidence as it stands at {@code at}, read by the same queries as the package. */
	SplitEvidenceSnapshot take(SplitCase split, List<UUID> parties, Instant at) {
		ChatExtent extent = this.chat.crewChat(split.jobId(), split.fundedAt(), split.releasedAt());
		return new SplitEvidenceSnapshot(at,
				this.splits.versionsOf(split.splitId())
					.stream()
					.map(version -> new SplitSnapshotVersion(version.versionNo(), version.kind().name(),
							(int) version.lines().stream().filter(line -> line.confirmedAt() != null).count(), version.createdAt()))
					.toList(),
				this.work.milestoneItems(split.jobId(), split.milestoneId(), parties)
					.stream()
					.map(item -> new SplitSnapshotWorkItem(item.workItemId(), item.number(), item.version(), item.status()))
					.toList(),
				this.deliveries.commitsOf(split.milestoneId())
					.stream()
					.map(commit -> new SplitSnapshotCommit(commit.handoverId(), commit.sha()))
					.toList(),
				new SplitSnapshotChat(extent.messageCount(), extent.lastMessageId(), extent.lastSentAt()),
				this.signals.matchesAmong(involved(split, parties)).size());
	}

	/** The parties and the Customer: who a Split Evidence Package matches (AD-19). */
	static List<UUID> involved(SplitCase split, List<UUID> parties) {
		UUID customer = split.customerId();
		if (customer == null || parties.contains(customer)) {
			return parties;
		}
		return Stream.concat(parties.stream(), Stream.of(customer)).toList();
	}

	static String json(SplitEvidenceSnapshot snapshot) {
		return JSON.writeValueAsString(snapshot);
	}

	static SplitEvidenceSnapshot parse(String json) {
		return JSON.readValue(json, SplitEvidenceSnapshot.class);
	}

}
