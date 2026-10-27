package vn.vibefix.team.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.team.internal.TeamStore.Kind;
import vn.vibefix.team.internal.TeamViews.MentorTeamView;
import vn.vibefix.team.internal.TeamViews.TeamStudentProfileView;
import vn.vibefix.team.internal.TeamViews.TeamStudentSearchView;

/**
 * MEN-6 and the invite-to-Team Sheet (FR-6, FR-14, AD-36, AD-37), until Story 21.7 deletes this class. No Team can
 * be formed since Story 19.7, and no account role tells a Mentor apart (Story 20.3), so every signed-in caller
 * finds nothing, whatever body it sends.
 */
@RestController
@RequestMapping("/api/v1/mentor/team")
@Access(Gate.SIGNED_IN)
class MentorTeamController {

	record TeamInvitationRequest(@NotNull UUID studentId) {
	}

	/** A sent or answered invitation or join request. */
	record TeamRequestView(UUID id, Kind kind, Instant createdAt, Instant lapsesAt) {
	}

	/** The Team with its members, join requests, and pending invitations. */
	@GetMapping
	MentorTeamView team() {
		throw refused();
	}

	/**
	 * Verified Students by keyword and Skills, or the one with the exact GitHub username {@code login},
	 * each with their place relative to the Mentor's Team.
	 */
	@GetMapping("/students")
	TeamStudentSearchView students(@RequestParam(defaultValue = "") @Size(max = 200) String q,
			@RequestParam(required = false) @Nullable List<UUID> skills, @RequestParam(required = false) @Nullable String login,
			@ParameterObject @PageableDefault(size = 20) Pageable pageable) {
		throw refused();
	}

	/** PUB-4 in the Sheet. */
	@GetMapping("/students/{studentId}")
	TeamStudentProfileView student(@PathVariable UUID studentId) {
		throw refused();
	}

	/** "Mời vào nhóm". */
	@PostMapping("/invitations")
	@ResponseStatus(HttpStatus.CREATED)
	TeamRequestView invite(@RequestBody TeamInvitationRequest body) {
		throw refused();
	}

	/** "Nhận vào nhóm". */
	@PostMapping("/join-requests/{joinRequestId}/accept")
	TeamRequestView accept(@PathVariable UUID joinRequestId) {
		throw refused();
	}

	/** "Xóa khỏi nhóm". */
	@DeleteMapping("/members/{membershipId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void remove(@PathVariable UUID membershipId) {
		throw refused();
	}

	/** "Từ chối". */
	@PostMapping("/join-requests/{joinRequestId}/decline")
	TeamRequestView decline(@PathVariable UUID joinRequestId) {
		throw refused();
	}

	/** Every signed-in caller finds nothing here (Story 20.3). */
	private static ApiException refused() {
		return new ApiException(ErrorCode.NOT_FOUND);
	}

}
