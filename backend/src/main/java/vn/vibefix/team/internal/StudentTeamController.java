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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.StudentVerification;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;
import vn.vibefix.team.internal.TeamStore.Kind;
import vn.vibefix.team.internal.TeamStore.Request;
import vn.vibefix.team.internal.TeamViews.StudentTeamView;
import vn.vibefix.team.internal.TeamViews.TeamMentorSearchView;

/**
 * STU-6 (FR-6, FR-14, AD-36, AD-37), for the Student: their Team or their invitations and join
 * requests, the Mentor search for verified Students, and the answers. Any other role finds nothing.
 */
@RestController
@RequestMapping("/api/v1/team")
@Access(Gate.SIGNED_IN)
class StudentTeamController {

	record TeamJoinRequest(@NotNull UUID mentorId) {
	}

	record StudentTeamRequestView(UUID id, Kind kind, Instant createdAt, Instant lapsesAt) {

		static StudentTeamRequestView of(Request request) {
			return new StudentTeamRequestView(request.id(), request.kind(), request.createdAt(), request.lapsesAt());
		}

	}

	private final TeamViews views;

	private final TeamRequests requests;

	private final StudentVerification verification;

	private final CurrentCaller callers;

	StudentTeamController(TeamViews views, TeamRequests requests, StudentVerification verification,
			CurrentCaller callers) {
		this.views = views;
		this.requests = requests;
		this.verification = verification;
		this.callers = callers;
	}

	@GetMapping
	StudentTeamView team() {
		return this.views.studentTeam(student());
	}

	/** Approved Mentors by keyword and Skills, for verified Students (AD-36). */
	@GetMapping("/mentors")
	TeamMentorSearchView mentors(@RequestParam(defaultValue = "") @Size(max = 200) String q,
			@RequestParam(required = false) @Nullable List<UUID> skills,
			@ParameterObject @PageableDefault(size = 20) Pageable pageable) {
		UUID student = student();
		if (!this.verification.isVerified(student)) {
			throw new ApiException(ErrorCode.FORBIDDEN, TeamReason.MEMBER_NOT_VERIFIED);
		}
		return this.views.searchMentors(student, q, skills == null ? List.of() : skills, pageable);
	}

	/** "Xin vào nhóm". */
	@PostMapping("/join-requests")
	@ResponseStatus(HttpStatus.CREATED)
	StudentTeamRequestView requestToJoin(@Valid @RequestBody TeamJoinRequest body) {
		return StudentTeamRequestView.of(this.requests.requestToJoin(student(), body.mentorId()));
	}

	/** "Đồng ý". */
	@PostMapping("/invitations/{invitationId}/accept")
	StudentTeamRequestView accept(@PathVariable UUID invitationId) {
		return StudentTeamRequestView.of(this.requests.accept(student(), invitationId, Kind.INVITATION));
	}

	/** "Từ chối". */
	@PostMapping("/invitations/{invitationId}/decline")
	StudentTeamRequestView decline(@PathVariable UUID invitationId) {
		return StudentTeamRequestView.of(this.requests.decline(student(), invitationId, Kind.INVITATION));
	}

	private UUID student() {
		if (!this.callers.get().hasRole(Role.STUDENT)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return this.callers.get().requireAccountId();
	}

}
