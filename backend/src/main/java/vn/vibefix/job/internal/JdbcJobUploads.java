package vn.vibefix.job.internal;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.JobUploads;

/** The screenshots' share of the Job's upload quota (AD-34). */
@Component
class JdbcJobUploads implements JobUploads {

	private final JdbcClient jdbc;

	JdbcJobUploads(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public long screenshotBytes(UUID jobId) {
		return this.jdbc.sql("SELECT coalesce(sum(size_bytes), 0) FROM job.job_screenshot WHERE job_id = :job")
			.param("job", jobId)
			.query(Long.class)
			.single();
	}

}
