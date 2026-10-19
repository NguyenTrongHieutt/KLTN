package vn.vibefix.team.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.shared.Access;
import vn.vibefix.shared.Gate;
import vn.vibefix.team.internal.TeamViews.PublicStudentView;

/**
 * PUB-4 (`/sv/{username}`, FR-51): a Student's public profile with their Team, for anyone. Portfolio
 * Entries and Ratings come from {@code reputation}.
 */
@RestController
@RequestMapping("/api/v1/students")
@Access(Gate.PUBLIC)
class PublicStudentController {

	private final TeamViews views;

	PublicStudentController(TeamViews views) {
		this.views = views;
	}

	@GetMapping("/{studentLogin}")
	PublicStudentView student(@PathVariable String studentLogin) {
		return this.views.publicStudent(studentLogin);
	}

}
