package vn.vibefix.job.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.job.internal.JobLifecycle.Applying;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * J6 (FR-41): Milestone 1 is being funded, and the Job starts. Its guards are FR-17's confirmed
 * Agreement and FR-75's Crew, re-checked here; a Code Job also takes the Product's advisory lock and
 * needs no other Code Job of the Product in Starting, Active, or Closing (AD-4, backed by an index).
 * It brings the latest Crew Terms version into force before M1 pins it (AD-41), and for a Code Job
 * tells everyone the repositories are being prepared. It only runs in M1's cascade.
 */
@Component
class J6Start implements JobLifecycle.Step {

	static final String STARTING = "job.job_starting";

	private final JdbcClient jdbc;

	private final CrewService crews;

	private final Notifications notifications;

	J6Start(JdbcClient jdbc, CrewService crews, Notifications notifications) {
		this.jdbc = jdbc;
		this.crews = crews;
		this.notifications = notifications;
	}

	@Override
	public Transition transition() {
		return Transition.J6;
	}

	/**
	 * @throws ApiException {@code GUARD_FAILED AGREEMENT_NOT_CONFIRMED}, {@code CREW_NOT_READY}, or
	 * {@code PRODUCT_HAS_RUNNING_JOB}
	 */
	@Override
	public void check(Applying applying) {
		Job job = applying.job();
		if (job.agreementVersionNo() == null) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.AGREEMENT_NOT_CONFIRMED);
		}
		if (!this.crews.crewReady(job.id())) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.CREW_NOT_READY);
		}
		if (job.code()) {
			if (!applying.dryRun()) {
				this.jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:product, 6))")
					.param("product", job.productId().toString())
					.query(Object.class)
					.single();
			}
			Boolean running = this.jdbc.sql("""
					SELECT EXISTS (SELECT 1 FROM job.job WHERE product_id = :product AND id <> :id
					    AND state IN ('STARTING', 'ACTIVE', 'CLOSING') AND job_types <> ARRAY['ASSESSMENT']::TEXT[])
					""").param("product", job.productId()).param("id", job.id()).query(Boolean.class).single();
			if (running) {
				throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.PRODUCT_HAS_RUNNING_JOB);
			}
		}
	}

	@Override
	public void apply(Applying applying) {
		applying.fact("crewTermsVersionNo", String.valueOf(this.crews.bringIntoForce(applying.job().id())));
	}

	@Override
	public void afterWrite(Applying applying) {
		Job job = applying.job();
		if (!job.code()) {
			return;
		}
		List<Recipient> everyone = new ArrayList<>(List.of(new Recipient(job.customerId(), Audience.CUSTOMER),
				new Recipient(Objects.requireNonNull(job.mentorId()), Audience.MENTOR)));
		this.crews.rows(job.id())
			.stream()
			.filter(row -> "ACTIVE".equals(row.status()))
			.forEach(row -> everyone.add(new Recipient(row.studentId(), Audience.CREW_ACTIVE)));
		this.notifications.send(new NotificationRequest(STARTING, applying.transitionId(), everyone, Map.of("job", job.title()),
				"/cong-viec/" + job.id(), job.id(), null));
	}

}
