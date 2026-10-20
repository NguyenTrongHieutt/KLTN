package vn.vibefix.job.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.AgreementSkills;

/** {@link AgreementSkills} from the latest confirmed Agreement version's content. */
@Component
class JobAgreementSkills implements AgreementSkills {

	private final JdbcClient jdbc;

	JobAgreementSkills(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public List<UUID> skillIdsOf(UUID jobId) {
		return this.jdbc.sql("""
				SELECT (s.skill ->> 'id')::uuid
				FROM (SELECT content FROM job.confirmed_agreement WHERE job_id = :job ORDER BY version_no DESC LIMIT 1) a,
				     jsonb_array_elements(coalesce(a.content -> 'skills', '[]'::jsonb)) WITH ORDINALITY AS s(skill, n)
				WHERE s.skill ->> 'id' IS NOT NULL
				ORDER BY s.n
				""").param("job", jobId).query(UUID.class).list();
	}

}
