package vn.vibefix.product.internal;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * R12's daily look at each Product's workflow file (Story 23.5c): every live {@code READY} Product with a recorded
 * blob is followed from the accepted set's end dates, with no GitHub call ({@link SourceStatuses#followEndDate}). A
 * version past its end date records {@code WORKFLOW_MISMATCH}; one ending within {@value SourceStatuses#LEAD_DAYS}
 * days opens {@code product.add_workflow} with the end date as its deadline and tells the Customer once per
 * (Product, blob) with {@code repo.workflow_check_failed}; one no longer ending closes that to-do. One Product's
 * failure never stops the Products after it.
 */
@Component
class WorkflowEndDates {

	private static final Logger log = LoggerFactory.getLogger(WorkflowEndDates.class);

	private final SourceStatuses statuses;

	WorkflowEndDates(SourceStatuses statuses) {
		this.statuses = statuses;
	}

	void run() {
		for (UUID productId : this.statuses.withBlob()) {
			try {
				this.statuses.followEndDate(productId);
			}
			catch (RuntimeException failed) {
				log.warn("The workflow end date of Product {} was not followed; the next Product is read anyway", productId,
						failed);
			}
		}
	}

	@Component
	@ConditionalOnBooleanProperty(name = "vibefix.scheduling.enabled", matchIfMissing = true)
	static class Daily {

		private final WorkflowEndDates endDates;

		Daily(WorkflowEndDates endDates) {
			this.endDates = endDates;
		}

		@Scheduled(cron = "${vibefix.product.workflow-ends-cron:0 15 5 * * *}", zone = "Asia/Ho_Chi_Minh")
		void run() {
			this.endDates.run();
		}

	}

}
