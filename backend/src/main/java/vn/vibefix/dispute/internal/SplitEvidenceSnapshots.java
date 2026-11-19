package vn.vibefix.dispute.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.chat.ChatEvidence;
import vn.vibefix.chat.ChatEvidence.ChatExtent;
import vn.vibefix.delivery.DeliveryEvidence;
import vn.vibefix.identity.PersonSignals;
import vn.vibefix.job.MilestoneSplits;
import vn.vibefix.job.MilestoneSplits.SplitCase;

/**
 * A Split Resolution decision's evidence snapshot (SG-27, AD-24, AD-40), written with the decision from the queries the Split
 * Evidence Package reads, as {@link EvidenceSnapshots} does for a Dispute: IDs, versions, counts, never copies of the content
 * and never an account ID. The Milestone's handovers are listed with their counts of criteria, images and links (Story
 * 24.3b). It stays with the decision after the Job's Crew Chat and handovers go, so the decision remains explainable.
 */
@Component
class SplitEvidenceSnapshots {

	/** @param confirmations how many parties confirmed it */
	record SplitSnapshotVersion(int versionNo, String kind, int confirmations, Instant createdAt) {
	}

	record SplitSnapshotCommit(UUID handoverId, String sha) {
	}

	/**
	 * One handover of the case's Milestone, as the decision saw it (Story 24.3b).
	 * @param status {@code SUBMITTED}, {@code ACCEPTED}, {@code REVISION_REQUESTED}, or {@code VOIDED}
	 * @param criteria how many criteria its report listed
	 * @param images how many images it carried
	 * @param links how many links it carried
	 */
	record SplitSnapshotHandover(UUID handoverId, int round, String status, int criteria, int images, int links) {
	}

	/** @param lastMessageId the newest Crew Chat message of the Milestone's interval when the decision was made */
	record SplitSnapshotChat(int messageCount, @Nullable UUID lastMessageId, @Nullable Instant lastSentAt) {
	}

	/**
	 * Everything the decision was based on, as it stood at {@code takenAt}.
	 * @param handovers the Milestone's handovers, oldest first; none in a snapshot taken before Story 24.3b, which
	 * recorded none
	 * @param personLinks how many matched pairs the package listed
	 */
	record SplitEvidenceSnapshot(Instant takenAt, List<SplitSnapshotVersion> versions,
			List<SplitSnapshotHandover> handovers, List<SplitSnapshotCommit> commits, SplitSnapshotChat crewChat,
			int personLinks) {

		SplitEvidenceSnapshot {
			handovers = handovers == null ? List.of() : List.copyOf(handovers);
		}

	}

	/**
	 * Reads a snapshot stored before a field was added as one with that field empty: a decision is never rewritten,
	 * and ADM-4 must still explain it (Story 24.3b).
	 */
	private static final JsonMapper JSON = JsonMapper.builder()
		.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
		.build();

	private final MilestoneSplits splits;

	private final DeliveryEvidence deliveries;

	private final ChatEvidence chat;

	private final PersonSignals signals;

	SplitEvidenceSnapshots(MilestoneSplits splits, DeliveryEvidence deliveries, ChatEvidence chat,
			PersonSignals signals) {
		this.splits = splits;
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
				this.deliveries.reports(split.jobId())
					.stream()
					.filter(report -> report.milestoneId().equals(split.milestoneId()))
					.map(report -> new SplitSnapshotHandover(report.handoverId(), report.round(), report.status(),
							report.criteria().size(), report.images().size(), report.links().size()))
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
