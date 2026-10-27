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
import vn.vibefix.team.internal.TeamViews.StudentTeamView;
import vn.vibefix.team.internal.TeamViews.TeamMentorSearchView;

/**
 * STU-6 (FR-6, FR-14, AD-36, AD-37): a Team or its invitations and join requests, the Mentor search, the answers,
 * and leaving, until Story 21.7 deletes this class. No Team can be formed since Story 19.7, and no account role
 * tells a Student apart (Story 20.3), so every signed-in caller finds nothing, whatever body it sends.
 */
@RestController
@RequestMapping("/api/v1/team")
@Access(Gate.SIGNED_IN)
class StudentTeamController {

	record TeamJoinRequest(@NotNull UUID mentorId) {
	}

	record StudentTeamRequestView(UUID id, Kind kind, Instant createdAt, Instant lapsesAt) {
	}

	@GetMapping
	StudentTeamView team() {
		throw refused();
	}

	/** Unlocked Mentors by keyword and Skills, for verified Students (AD-36). */
	@GetMapping("/mentors")
	TeamMentorSearchView mentors(@RequestParam(defaultValue = "") @Size(max = 200) String q,
			@RequestParam(required = false) @Nullable List<UUID> skills,
			@ParameterObject @PageableDefault(size = 20) Pageable pageable) {
		throw refused();
	}

	/** "Xin vào nhóm". */
	@PostMapping("/join-requests")
	@ResponseStatus(HttpStatus.CREATED)
	StudentTeamRequestView requestToJoin(@RequestBody TeamJoinRequest body) {
		throw refused();
	}

	/** "Đồng ý". */
	@PostMapping("/invitations/{invitationId}/accept")
	StudentTeamRequestView accept(@PathVariable UUID invitationId) {
		throw refused();
	}

	/** "Rời nhóm". */
	@DeleteMapping("/membership")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void leave() {
		throw refused();
	}

	/** "Từ chối". */
	@PostMapping("/invitations/{invitationId}/decline")
	StudentTeamRequestView decline(@PathVariable UUID invitationId) {
		throw refused();
	}

	/** Every signed-in caller finds nothing here (Story 20.3). */
	private static ApiException refused() {
		return new ApiException(ErrorCode.NOT_FOUND);
	}

}
