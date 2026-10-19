package vn.vibefix.timers.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The pollers of {@code timers} (AD-7: {@code @Scheduled} is used only for pollers). */
@Component
@ConditionalOnBooleanProperty(name = "vibefix.scheduling.enabled", matchIfMissing = true)
class DeadlineScheduler {

	private final DeadlinePoller poller;

	DeadlineScheduler(DeadlinePoller poller) {
		this.poller = poller;
	}

	@Scheduled(fixedDelayString = "${vibefix.timers.poll-interval}", initialDelayString = "${vibefix.timers.poll-interval}")
	void poll() {
		this.poller.runDue();
	}

	@Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT15M")
	void purge() {
		this.poller.purge();
	}

}
