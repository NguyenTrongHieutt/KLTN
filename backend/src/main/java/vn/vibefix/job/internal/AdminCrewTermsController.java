package vn.vibefix.job.internal;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.job.internal.CrewTermsGrants.Share;
import vn.vibefix.job.internal.CrewTermsGrants.Version;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.Gate;

/**
 * The Team Evidence Package's Crew Terms versions (FR-79, AD-20, AD-40, UX-DR84): every version of the Job
 * with each party's share and confirmation, read only under the assigned Administrator's grant and audited.
 * Each party's role is their role in this Job ({@code job.roleOf}, AD-43), never which directory describes them.
 */
@RestController
@Access(Gate.ADMINISTRATOR)
class AdminCrewTermsController {

	/** @param role {@code MENTOR} or {@code STUDENT} */
	record CrewTermsShareEvidenceView(String name, String role, int percent, @Nullable Instant confirmedAt) {
	}

	record CrewTermsVersionEvidenceView(int versionNo, String status, Instant createdAt,
			List<CrewTermsShareEvidenceView> shares) {
	}

	record CrewTermsEvidenceView(UUID jobId, List<CrewTermsVersionEvidenceView> versions) {
	}

	private final CrewTermsGrants grants;

	private final StudentDirectory students;

	private final MentorDirectory mentors;

	private final CurrentCaller callers;

	private final JobRoleReader roles;

	AdminCrewTermsController(CrewTermsGrants grants, StudentDirectory students, MentorDirectory mentors, CurrentCaller callers,
			JobRoleReader roles) {
		this.roles = roles;
		this.grants = grants;
		this.students = students;
		this.mentors = mentors;
		this.callers = callers;
	}

	@GetMapping("/api/v1/admin/jobs/{jobId}/crew-terms")
	CrewTermsEvidenceView crewTerms(@PathVariable UUID jobId) {
		List<Version> versions = this.grants.crewTerms(this.callers.get().requireAccountId(), jobId);
		List<UUID> parties = versions.stream().flatMap(version -> version.shares().stream()).map(Share::accountId).distinct().toList();
		Map<UUID, String> names = new HashMap<>();
		Map<UUID, String> partyRoles = new HashMap<>();
		List<UUID> jobMentors = parties.stream().filter(party -> this.roles.roleOf(jobId, party).isMentor()).toList();
		List<UUID> crew = parties.stream().filter(party -> !jobMentors.contains(party)).toList();
		if (!crew.isEmpty()) {
			this.students.cards(crew).forEach(card -> {
				names.put(card.accountId(), card.shownName());
				partyRoles.put(card.accountId(), "STUDENT");
			});
		}
		if (!jobMentors.isEmpty()) {
			this.mentors.cards(jobMentors).forEach(card -> names.put(card.accountId(), card.displayName()));
			jobMentors.forEach(mentor -> partyRoles.put(mentor, "MENTOR"));
		}
		return new CrewTermsEvidenceView(jobId, versions.stream()
			.map(version -> new CrewTermsVersionEvidenceView(version.versionNo(), version.status(), version.createdAt(),
					version.shares()
						.stream()
						.map(share -> new CrewTermsShareEvidenceView(names.getOrDefault(share.accountId(), ""),
								partyRoles.getOrDefault(share.accountId(), "STUDENT"), share.percent(), share.confirmedAt()))
						.toList()))
			.toList());
	}

}
