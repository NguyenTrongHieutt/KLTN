package vn.vibefix.delivery.internal;

import java.util.Map;
import java.util.UUID;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.delivery.internal.Handovers.CodeRecord;
import vn.vibefix.delivery.internal.Handovers.Handover;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.operations.ExternalOperations;
import vn.vibefix.operations.OperationRequest;
import vn.vibefix.shared.ProcessedEvents;

/**
 * M3 and M4 close the older handover's pull request (FR-33, R26): each one that names a Code Job's handover
 * with a pull request requests {@value #TYPE}, keyed by the handover, so a redelivery joins the same
 * operation. {@link HandoverPullCloseOperation} posts the reason comment once, adds "(lần {round})" to the
 * title, and closes it; the {@code handover/{handoverId}} branch is kept as evidence.
 */
@Component
class HandoverPullCloses {

	static final String TYPE = "delivery.handover_pull_close";

	/** M4's reason comment, with the handover's ID. */
	static String revisionComment(UUID handoverId) {
		return "Khách hàng đã yêu cầu chỉnh sửa. Yêu cầu hợp nhất này được đóng; nhánh handover/" + handoverId
				+ " được giữ làm bằng chứng. Lần bàn giao sau mở yêu cầu hợp nhất mới.";
	}

	/** M3's reason comment, with the handover's ID. */
	static String voidComment(UUID handoverId) {
		return "Bàn giao đã bị hủy vì nhánh bàn giao thay đổi trước khi nghiệm thu. Yêu cầu hợp nhất này được đóng; "
				+ "nhánh handover/" + handoverId + " được giữ làm bằng chứng. Người hướng dẫn sẽ bàn giao lại.";
	}

	private final Handovers handovers;

	private final ExternalOperations operations;

	private final ProcessedEvents processedEvents;

	HandoverPullCloses(Handovers handovers, ExternalOperations operations, ProcessedEvents processedEvents) {
		this.handovers = handovers;
		this.operations = operations;
		this.processedEvents = processedEvents;
	}

	@ApplicationModuleListener
	void onJobTransitioned(JobTransitioned event) {
		requested(event);
	}

	/** What the listener does, in the caller's transaction. Replay-safe. */
	@Transactional
	void requested(JobTransitioned event) {
		String handoverId = event.facts().get("handoverId");
		boolean closes = "M3".equals(event.transition()) || "M4".equals(event.transition());
		if (!closes || handoverId == null
				|| !this.processedEvents.markProcessed("delivery.HandoverPullCloses.onJobTransitioned", event.eventId())) {
			return;
		}
		UUID id = UUID.fromString(handoverId);
		Handover handover = this.handovers.find(id).orElse(null);
		CodeRecord code = this.handovers.code(id).orElse(null);
		if (handover == null || code == null || code.pullNumber() <= 0) {
			// An Assessment Report, or a handover without a pull request: nothing on GitHub to close.
			return;
		}
		this.operations.request(new OperationRequest(TYPE, id, Map.of("jobId", handover.jobId().toString(), "handoverId",
				id.toString(), "pull", code.pullNumber(), "round", handover.round(), "transition", event.transition()), null,
				null, 0));
	}

}
