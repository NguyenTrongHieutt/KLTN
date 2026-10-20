package vn.vibefix.work.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.work.MilestoneWork;

/**
 * {@link MilestoneWork} (FR-10, FR-32, AD-39): a Work Item counts as done for a handover only in "Xong"
 * with the Mentor's approval as its latest review entry, as {@code work.criteriaDone} counts it.
 */
@Component
class WorkMilestones implements MilestoneWork {

	private final JdbcClient jdbc;

	WorkMilestones(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public List<ItemState> items(UUID jobId, UUID milestoneId) {
		return this.jdbc.sql("""
				SELECT w.id, b.code, w.number, w.title, w.status,
				       (SELECT h.action FROM work.work_item_history h
				        WHERE h.work_item_id = w.id AND h.action IN ('SUBMITTED', 'APPROVED', 'RETURNED')
				        ORDER BY h.seq DESC LIMIT 1) AS review
				FROM work.work_item w JOIN work.board b ON b.job_id = w.job_id
				WHERE w.job_id = :job AND w.milestone_id = :milestone ORDER BY w.number
				""")
			.param("job", jobId)
			.param("milestone", milestoneId)
			.query((rs, n) -> new ItemState(rs.getObject("id", UUID.class), rs.getString("code") + "-" + rs.getInt("number"),
					rs.getString("title"), rs.getString("status"),
					"DONE".equals(rs.getString("status")) && "APPROVED".equals(rs.getString("review"))))
			.list();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<ReportVersion> currentReport(UUID jobId) {
		return this.jdbc.sql("""
				SELECT v.page_id, v.version_no, v.title, v.body
				FROM work.page p JOIN work.page_version v ON v.page_id = p.id AND v.version_no = p.version_no
				WHERE p.job_id = :job AND p.template = 'ASSESSMENT_REPORT'
				""")
			.param("job", jobId)
			.query((rs, n) -> new ReportVersion(rs.getObject("page_id", UUID.class), rs.getInt("version_no"),
					rs.getString("title"), rs.getString("body")))
			.optional();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<ReportVersion> report(UUID pageId, int versionNo) {
		return this.jdbc.sql("""
				SELECT page_id, version_no, title, body FROM work.page_version WHERE page_id = :page AND version_no = :version
				""")
			.param("page", pageId)
			.param("version", versionNo)
			.query((rs, n) -> new ReportVersion(rs.getObject("page_id", UUID.class), rs.getInt("version_no"),
					rs.getString("title"), rs.getString("body")))
			.optional();
	}

}
