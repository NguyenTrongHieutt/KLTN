package vn.vibefix.shared.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the {@link EventPublicationMaintenance} poller (AD-9); off when scheduling is off. */
@Component
@ConditionalOnBooleanProperty(name = SchedulingConfiguration.ENABLED, matchIfMissing = true)
class EventPublicationScheduler {

	private final EventPublicationMaintenance maintenance;

	EventPublicationScheduler(EventPublicationMaintenance maintenance) {
		this.maintenance = maintenance;
	}

	@Scheduled(fixedDelayString = "${vibefix.events.maintenance-interval}",
			initialDelayString = "${vibefix.events.maintenance-interval}")
	void maintain() {
		this.maintenance.resubmitDue();
		this.maintenance.markStalled();
		this.maintenance.purgeCompleted();
	}

}
