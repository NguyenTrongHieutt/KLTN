package vn.vibefix.admin.internal;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.operations.FailedOperations;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.StalledEventPublications;

/**
 * ADM-1's queue counts (UX-DR13, UX-DR69): the queues that exist so far, each with its oldest item.
 * The SPA polls it every 60 s; later stories add their queues here.
 */
@RestController
@RequestMapping("/api/v1/admin/overview")
@Access(Gate.ADMINISTRATOR)
class AdminOverviewController {

	/** @param oldestAt when the oldest waiting item arrived, absent for an empty queue */
	record Queue(int count, @Nullable Instant oldestAt) {

		static <T> Queue of(List<T> items, Function<T, Instant> arrivedAt) {
			return new Queue(items.size(), items.stream().map(arrivedAt).min(Instant::compareTo).orElse(null));
		}

	}

	/**
	 * @param failedOperations External Operations that failed for good (FR-64, "Thao tác lỗi")
	 * @param stalledEvents event publications that stopped after 5 attempts (AD-9)
	 */
	record OverviewView(Queue failedOperations, Queue stalledEvents) {
	}

	private final FailedOperations failedOperations;

	private final StalledEventPublications stalledEvents;

	AdminOverviewController(FailedOperations failedOperations, StalledEventPublications stalledEvents) {
		this.failedOperations = failedOperations;
		this.stalledEvents = stalledEvents;
	}

	@GetMapping
	OverviewView overview() {
		return new OverviewView(Queue.of(this.failedOperations.findAll(), FailedOperations.FailedOperation::failedAt),
				Queue.of(this.stalledEvents.findAll(), StalledEventPublications.StalledPublication::stalledAt));
	}

}
