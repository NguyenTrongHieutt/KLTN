package vn.vibefix.job.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.product.ProductHidden;
import vn.vibefix.shared.ProcessedEvents;

/** A hidden Product's Open Job Posts are hidden with it (FR-60). Replay-safe. */
@Component
class JobPostListeners {

	static final String PRODUCT_HIDDEN_REASON = "Sản phẩm của tin tuyển này đã bị ẩn";

	private final JdbcClient jdbc;

	private final JobPostModeration posts;

	private final ProcessedEvents processedEvents;

	JobPostListeners(JdbcClient jdbc, JobPostModeration posts, ProcessedEvents processedEvents) {
		this.jdbc = jdbc;
		this.posts = posts;
		this.processedEvents = processedEvents;
	}

	@ApplicationModuleListener
	void onProductHidden(ProductHidden event) {
		if (!this.processedEvents.markProcessed("job.JobPostListeners.onProductHidden", event.eventId())) {
			return;
		}
		List<UUID> open = this.jdbc.sql("SELECT id FROM job.job WHERE product_id = :product AND state = 'OPEN' AND hidden_at IS NULL")
			.param("product", event.productId())
			.query(UUID.class)
			.list();
		open.forEach(post -> this.posts.hide(post, PRODUCT_HIDDEN_REASON));
	}

}
