package vn.vibefix.team.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.MentorGate;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;
import vn.vibefix.team.internal.TeamStore.Kind;
import vn.vibefix.team.internal.TeamStore.Request;
import vn.vibefix.team.internal.TeamViews.MentorTeamView;
import vn.vibefix.team.internal.TeamViews.TeamStudentProfileView;
import vn.vibefix.team.internal.TeamViews.TeamStudentSearchView;

/**
 * MEN-6 and the invite-to-Team Sheet (FR-6, FR-14, AD-36, AD-37), for the Mentor. Inviting,
 * searching, reading PUB-4, and accepting join requests are new-work commands that require an
 * approved Mentor; reading the Team, declining, and removing a Student need only an unlocked account
 * (AD-19). Any other role finds nothing.
 */
@RestController
@RequestMapping("/api/v1/mentor/team")
@Access(Gate.SIGNED_IN)
class MentorTeamController {

	record TeamInvitationRequest(@NotNull UUID studentId) {
	}

	/** A sent or answered invitation or join request. */
	record TeamRequestView(UUID id, Kind kind, Instant createdAt, Instant lapsesAt) {

		static TeamRequestView of(Request request) {
			return new TeamRequestView(request.id(), request.kind(), request.createdAt(), request.lapsesAt());
		}

	}

	private final TeamViews views;

	private final TeamRequests requests;

	private final TeamMemberships memberships;

	private final MentorGate mentorGate;

	private final CurrentCaller callers;

	MentorTeamController(TeamViews views, TeamRequests requests, TeamMemberships memberships, MentorGate mentorGate,
			CurrentCaller callers) {
		this.views = views;
		this.requests = requests;
		this.memberships = memberships;
		this.mentorGate = mentorGate;
		this.callers = callers;
	}

	/** The Team with its members, join requests, and pending invitations. */
	@GetMapping
	MentorTeamView team() {
		UUID mentor = mentor();
		this.mentorGate.requireActiveAccount(mentor);
		boolean approved = approved(mentor);
		return this.views.mentorTeam(mentor, approved).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	/**
	 * Verified Students by keyword and Skills, or the one with the exact GitHub username {@code login},
	 * each with their place relative to the Mentor's Team.
	 */
	@GetMapping("/students")
	TeamStudentSearchView students(@RequestParam(defaultValue = "") @Size(max = 200) String q,
			@RequestParam(required = false) @Nullable List<UUID> skills, @RequestParam(required = false) @Nullable String login,
			@ParameterObject @PageableDefault(size = 20) Pageable pageable) {
		UUID mentor = mentor();
		this.mentorGate.requireApprovedMentor(mentor);
		return this.views.searchStudents(mentor, q, skills == null ? List.of() : skills, login, pageable);
	}

	/** PUB-4 in the Sheet. */
	@GetMapping("/students/{studentId}")
	TeamStudentProfileView student(@PathVariable UUID studentId) {
		UUID mentor = mentor();
		this.mentorGate.requireApprovedMentor(mentor);
		return this.views.studentProfile(mentor, studentId);
	}

	/** "Mời vào nhóm". */
	@PostMapping("/invitations")
	@ResponseStatus(HttpStatus.CREATED)
	TeamRequestView invite(@Valid @RequestBody TeamInvitationRequest body) {
		return TeamRequestView.of(this.requests.invite(mentor(), body.studentId()));
	}

	/** "Nhận vào nhóm". */
	@PostMapping("/join-requests/{joinRequestId}/accept")
	TeamRequestView accept(@PathVariable UUID joinRequestId) {
		return TeamRequestView.of(this.requests.acceptOrCancel(this.requests, mentor(), joinRequestId, Kind.JOIN_REQUEST));
	}

	/** "Xóa khỏi nhóm": needs only an unlocked account, so a revoked Mentor can still let Students go. */
	@DeleteMapping("/members/{membershipId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void remove(@PathVariable UUID membershipId) {
		this.memberships.remove(mentor(), membershipId);
	}

	/** "Từ chối". */
	@PostMapping("/join-requests/{joinRequestId}/decline")
	TeamRequestView decline(@PathVariable UUID joinRequestId) {
		return TeamRequestView.of(this.requests.decline(mentor(), joinRequestId, Kind.JOIN_REQUEST));
	}

	private UUID mentor() {
		if (!this.callers.get().hasRole(Role.MENTOR)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return this.callers.get().requireAccountId();
	}

	private boolean approved(UUID mentor) {
		try {
			this.mentorGate.requireApprovedMentor(mentor);
			return true;
		}
		catch (ApiException ex) {
			return false;
		}
	}

}
