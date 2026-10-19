package vn.vibefix.identity.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes Administrator sign-in failures that no longer count and locks that have ended, every hour. */
@Component
@ConditionalOnBooleanProperty(name = "vibefix.scheduling.enabled", matchIfMissing = true)
class AdminSignInPurge {

	private final AdminSignIns signIns;

	AdminSignInPurge(AdminSignIns signIns) {
		this.signIns = signIns;
	}

	@Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M")
	void run() {
		this.signIns.purge();
	}

}
