package vn.vibefix.team.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.shared.Access;
import vn.vibefix.shared.Gate;
import vn.vibefix.team.internal.TeamViews.PublicMentorView;

/**
 * PUB-5 (`/mentor/{username}`, FR-51, FR-6): a Mentor's public profile with their Team, for anyone, and the
 * profile Sheets of CUS-8 and the Mentor search. It names no viewer: only an account role told a Student who may
 * ask to join, and no account carries one since Story 20.3. Portfolio Entries and Ratings come with Story 3.5.
 */
@RestController
@RequestMapping("/api/v1/mentors")
@Access(Gate.PUBLIC)
class PublicMentorController {

	private final TeamViews views;

	PublicMentorController(TeamViews views) {
		this.views = views;
	}

	@GetMapping("/{username}")
	PublicMentorView mentor(@PathVariable String username) {
		return this.views.publicMentor(username, null);
	}

}
