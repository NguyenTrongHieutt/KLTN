package vn.vibefix.identity.internal;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.identity.PersonDirectory;
import vn.vibefix.identity.PersonDirectory.PersonCard;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.ReportableContent;

/**
 * FR-69: a user is reportable through the public profile the platform shows of them (PUB-4), by any other signed-in
 * user.
 */
@Component
class ProfileReportable implements ReportableContent {

	private final MentorDirectory mentors;

	private final PersonDirectory people;

	ProfileReportable(MentorDirectory mentors, PersonDirectory people) {
		this.mentors = mentors;
		this.people = people;
	}

	@Override
	public Set<String> kinds() {
		return Set.of("USER");
	}

	@Override
	public Optional<Reported> reportable(String kind, UUID id, Caller caller) {
		if (!"USER".equals(kind) || caller.isAnonymous() || caller.isAdministrator()
				|| id.equals(caller.requireAccountId())) {
			return Optional.empty();
		}
		Optional<MentorCard> mentor = this.mentors.cards(List.of(id)).stream().findFirst();
		if (mentor.isPresent()) {
			String login = mentor.get().githubLogin();
			return login != null && this.mentors.byLogin(login).isPresent()
					? Optional.of(new Reported(null, null, id, mentor.get().displayName())) : Optional.empty();
		}
		Optional<PersonCard> student = this.people.cards(List.of(id)).stream().findFirst();
		// An account without GitHub linked has no public profile to report (Story 20.3 lists every person's account), and
		// neither has a locked or an erased one.
		if (student.isEmpty() || student.get().githubLogin() == null || !student.get().available()) {
			return Optional.empty();
		}
		PersonCard card = student.get();
		return Optional.of(new Reported(null, null, id, card.name() != null ? card.name() : "@" + card.githubLogin()));
	}

}
