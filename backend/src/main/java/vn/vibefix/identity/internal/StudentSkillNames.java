package vn.vibefix.identity.internal;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.catalog.SkillRenamed;
import vn.vibefix.shared.ProcessedEvents;

/**
 * Keeps the Student profiles' {@code skill_names} current after a rename (AD-38, AD-9): it records
 * the event as processed and rewrites the names in one transaction, so a replay changes nothing.
 */
@Component
class StudentSkillNames {

	static final String LISTENER = "identity.StudentSkillNames.onSkillRenamed";

	private final Profiles profiles;

	private final ProcessedEvents processedEvents;

	StudentSkillNames(Profiles profiles, ProcessedEvents processedEvents) {
		this.profiles = profiles;
		this.processedEvents = processedEvents;
	}

	@ApplicationModuleListener
	void onSkillRenamed(SkillRenamed event) {
		if (!this.processedEvents.markProcessed(LISTENER, event.eventId())) {
			return;
		}
		this.profiles.refreshSkillNames(event.skillId());
		this.profiles.refreshMentorSkillNames(event.skillId());
	}

}
