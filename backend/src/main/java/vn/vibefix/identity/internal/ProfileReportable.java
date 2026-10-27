package vn.vibefix.identity.internal;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.identity.StudentDirectory.StudentCard;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.ReportableContent;

/**
 * FR-69: a user is reportable through the public profile the platform shows of them, a Mentor's (PUB-3) or a
 * Student's (PUB-4), by any other signed-in user.
 */
@Component
class ProfileReportable implements ReportableContent {

	private final MentorDirectory mentors;

	private final StudentDirectory students;

	ProfileReportable(MentorDirectory mentors, StudentDirectory students) {
		this.mentors = mentors;
		this.students = students;
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
		Optional<StudentCard> student = this.students.cards(List.of(id)).stream().findFirst();
		// An account without GitHub linked has no public profile to report (Story 20.3 lists every person's account).
		if (student.isEmpty() || student.get().githubLogin() == null
				|| this.students.profileByLogin(student.get().githubLogin()).isEmpty()) {
			return Optional.empty();
		}
		StudentCard card = student.get();
		return Optional.of(new Reported(null, null, id, card.name() != null ? card.name() : "@" + card.githubLogin()));
	}

}
