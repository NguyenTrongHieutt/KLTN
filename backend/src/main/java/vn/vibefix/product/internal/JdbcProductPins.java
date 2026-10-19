package vn.vibefix.product.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.product.ProductPins;

/** {@link ProductPins} on {@code product.job_pin}. */
@Component
class JdbcProductPins implements ProductPins {

	private final JdbcClient jdbc;

	private final Clock clock;

	JdbcProductPins(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void pinForJob(UUID productId, UUID jobId) {
		this.jdbc.sql("""
				INSERT INTO product.job_pin (product_id, job_id, pinned_at) VALUES (:product, :job, :now)
				ON CONFLICT DO NOTHING
				""")
			.param("product", productId)
			.param("job", jobId)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void releaseForJob(UUID productId, UUID jobId) {
		this.jdbc.sql("DELETE FROM product.job_pin WHERE product_id = :product AND job_id = :job")
			.param("product", productId)
			.param("job", jobId)
			.update();
	}

}
