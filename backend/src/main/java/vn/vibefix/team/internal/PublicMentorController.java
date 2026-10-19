package vn.vibefix.team.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.shared.Access;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;
import vn.vibefix.team.internal.TeamViews.PublicMentorView;

/**
 * PUB-5 (`/mentor/{username}`, FR-51, FR-6): a Mentor's public profile with their Team, for anyone;
 * a signed-in Student also learns whether they may ask to join. Portfolio Entries and Ratings come
 * with Story 3.5.
 */
@RestController
@RequestMapping("/api/v1/mentors")
@Access(Gate.PUBLIC)
class PublicMentorController {

	private final TeamViews views;

	private final CurrentCaller callers;

	PublicMentorController(TeamViews views, CurrentCaller callers) {
		this.views = views;
		this.callers = callers;
	}

	@GetMapping("/{username}")
	PublicMentorView mentor(@PathVariable String username) {
		Caller caller = this.callers.get();
		return this.views.publicMentor(username, caller.hasRole(Role.STUDENT) ? caller.requireAccountId() : null);
	}

}
