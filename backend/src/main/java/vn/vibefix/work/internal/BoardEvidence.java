package vn.vibefix.work.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.work.WorkEvidence;

/** {@link WorkEvidence} over {@code work.work_item} and {@code work.page}. */
@Component
class BoardEvidence implements WorkEvidence {

	private final JdbcClient jdbc;

	BoardEvidence(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public List<ItemLine> workItems(UUID jobId) {
		return this.jdbc.sql("SELECT id, number, version, status FROM work.work_item WHERE job_id = :job ORDER BY number")
			.param("job", jobId)
			.query((rs, n) -> new ItemLine(rs.getObject("id", UUID.class), rs.getInt("number"), rs.getLong("version"),
					rs.getString("status")))
			.list();
	}

	@Override
	@Transactional(readOnly = true)
	public List<PageLine> pages(UUID jobId) {
		return this.jdbc.sql("SELECT id, version_no FROM work.page WHERE job_id = :job ORDER BY seq")
			.param("job", jobId)
			.query((rs, n) -> new PageLine(rs.getObject("id", UUID.class), rs.getInt("version_no")))
			.list();
	}

}
