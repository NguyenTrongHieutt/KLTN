package vn.vibefix.admin.internal;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.Administrators;
import vn.vibefix.identity.StudentCardQueue;
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
	 * @param activeAdministrators Administrators who can sign in; at 1, ADM-1 warns (SG-10)
	 * @param studentCards student cards waiting for a decision (FR-5, ADM-3)
	 */
	record OverviewView(Queue failedOperations, Queue stalledEvents, long activeAdministrators, Queue studentCards) {
	}

	private final FailedOperations failedOperations;

	private final StalledEventPublications stalledEvents;

	private final Administrators administrators;

	private final StudentCardQueue studentCards;

	AdminOverviewController(FailedOperations failedOperations, StalledEventPublications stalledEvents,
			Administrators administrators, StudentCardQueue studentCards) {
		this.failedOperations = failedOperations;
		this.stalledEvents = stalledEvents;
		this.administrators = administrators;
		this.studentCards = studentCards;
	}

	@GetMapping
	OverviewView overview() {
		return new OverviewView(Queue.of(this.failedOperations.findAll(), FailedOperations.FailedOperation::failedAt),
				Queue.of(this.stalledEvents.findAll(), StalledEventPublications.StalledPublication::stalledAt),
				this.administrators.activeCount(), studentCards());
	}

	private Queue studentCards() {
		StudentCardQueue.Pending pending = this.studentCards.pending();
		return new Queue(Math.toIntExact(pending.count()), pending.oldestUploadedAt());
	}

}
