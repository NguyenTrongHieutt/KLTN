package vn.vibefix.job.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.catalog.SkillRenamed;
import vn.vibefix.shared.ProcessedEvents;

/**
 * Keeps the Jobs' {@code skill_names} and the Skills their recruitment seeks ({@code recruit_skill_names}, Story 21.1)
 * current after a rename (AD-38, AD-9): the event is recorded as processed and the names rewritten in one
 * transaction, so a replay changes nothing.
 */
@Component
class JobSkillNames {

	static final String LISTENER = "job.JobSkillNames.onSkillRenamed";

	private final JdbcClient jdbc;

	private final ProcessedEvents processedEvents;

	JobSkillNames(JdbcClient jdbc, ProcessedEvents processedEvents) {
		this.jdbc = jdbc;
		this.processedEvents = processedEvents;
	}

	@ApplicationModuleListener
	void onSkillRenamed(SkillRenamed event) {
		if (!this.processedEvents.markProcessed(LISTENER, event.eventId())) {
			return;
		}
		this.jdbc.sql("""
				UPDATE job.job SET skill_names[array_position(skill_ids, CAST(:skill AS UUID))] = :name
				WHERE skill_ids @> ARRAY[CAST(:skill AS UUID)]
				""").param("skill", event.skillId()).param("name", event.name()).update();
		this.jdbc.sql("""
				UPDATE job.job
				SET recruit_skill_names[array_position(recruit_skill_ids, CAST(:skill AS UUID))] = :name
				WHERE recruit_skill_ids @> ARRAY[CAST(:skill AS UUID)]
				""").param("skill", event.skillId()).param("name", event.name()).update();
	}

}
