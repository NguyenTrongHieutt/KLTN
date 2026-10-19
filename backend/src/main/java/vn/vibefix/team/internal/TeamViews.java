package vn.vibefix.team.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.identity.MentorDirectory.MentorProfile;
import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.identity.StudentDirectory.StudentCard;
import vn.vibefix.identity.StudentVerification;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.team.Teams;
import vn.vibefix.team.internal.TeamStore.Kind;
import vn.vibefix.team.internal.TeamStore.Member;
import vn.vibefix.team.internal.TeamStore.Request;
import vn.vibefix.team.internal.TeamStore.Status;
import vn.vibefix.team.internal.TeamStore.Team;

/**
 * What MEN-6, STU-6, the invite Sheet, PUB-4, and PUB-5 show (FR-6, FR-51, AD-36): public profile
 * fields from {@code identity}, with each person's place in the Team. A hidden name stays hidden
 * everywhere; the Student themself sees their own.
 */
@Component
class TeamViews {

	/** A Student as Mentors see them: never contact details, the name and school only while shown. */
	record TeamStudentView(UUID accountId, @Nullable String githubLogin, @Nullable String name,
			@Nullable String school, @Nullable Integer yearOfStudy, List<String> skills, boolean verified,
			@Nullable LocalDate verifiedUntil) {

		static TeamStudentView of(StudentCard card) {
			return new TeamStudentView(card.accountId(), card.githubLogin(), card.name(), card.school(),
					card.yearOfStudy(), card.skills(), card.verified(), card.verifiedUntil());
		}

	}

	/** A Mentor as Students and visitors see them, with the size of their Team. */
	record TeamMentorView(UUID accountId, @Nullable String githubLogin, String displayName, int yearsOfExperience,
			List<String> skills, boolean available, int teamSize) {
	}

	/** @param onCrew on a running Crew: they can neither leave nor be removed (AD-37) */
	record TeamMemberRow(UUID membershipId, TeamStudentView student, Instant joinedAt, boolean onCrew) {
	}

	/** An invitation or join request on MEN-6, with its Student. */
	record TeamStudentRequestRow(UUID id, TeamStudentView student, Instant createdAt, Instant lapsesAt) {
	}

	/**
	 * MEN-6.
	 * @param status {@code OPEN}, or {@code CLOSED} once the Mentor is revoked or locked
	 */
	record MentorTeamView(Status status, int size, int sizeLimit, int invitesLeft, List<TeamMemberRow> members,
			List<TeamStudentRequestRow> joinRequests, List<TeamStudentRequestRow> invitations) {
	}

	/** Where a found Student stands with the searching Mentor's Team. */
	enum StudentRelation {
		NONE, INVITED, REQUESTED, MEMBER, OTHER_TEAM
	}

	record TeamStudentResult(TeamStudentView student, StudentRelation relation) {
	}

	record TeamStudentSearchView(List<TeamStudentResult> items, int page, int size, long total, int invitesLeft,
			int teamSize) {
	}

	/** PUB-4 in the Sheet: the Student, their place, and their Team's Mentor if they are in one. */
	record TeamStudentProfileView(TeamStudentView student, StudentRelation relation,
			@Nullable TeamMentorView teamMentor) {
	}

	/** An invitation or join request on STU-6, with its Mentor. */
	record TeamMentorRequestRow(UUID id, TeamMentorView mentor, Instant createdAt, Instant lapsesAt) {
	}

	/** A Team member another member may see: only those who show their name, and the viewer. */
	record TeamListedMember(String name, @Nullable String school, boolean you) {
	}

	/** @param onCrew the Student is on a running Crew, so they cannot leave yet (AD-37) */
	record StudentTeamMembership(TeamMentorView mentor, Status status, Instant joinedAt, int size,
			List<TeamListedMember> listed, boolean onCrew) {
	}

	/** STU-6. */
	record StudentTeamView(boolean verified, @Nullable StudentTeamMembership team,
			List<TeamMentorRequestRow> invitations, List<TeamMentorRequestRow> joinRequests, int requestsLeft) {
	}

	/** Where a found Mentor stands with the searching Student. */
	enum MentorRelation {
		NONE, INVITED, REQUESTED, MEMBER
	}

	record TeamMentorResult(TeamMentorView mentor, MentorRelation relation, boolean teamFull) {
	}

	record TeamMentorSearchView(List<TeamMentorResult> items, int page, int size, long total, int requestsLeft,
			boolean inTeam) {
	}

	record PublicSkillGroup(@Nullable String category, List<String> skills) {
	}

	record PublicTeamMember(String name, @Nullable String school, boolean verified) {
	}

	/**
	 * What PUB-5 says to a signed-in Student about joining.
	 * @param pending their waiting join request (or the Mentor's invitation), while there is one
	 */
	record PublicMentorViewer(MentorRelation relation, boolean inTeam, boolean verified, int requestsLeft,
			@Nullable TeamPendingRequest pending) {
	}

	record TeamPendingRequest(UUID id, Kind kind, Instant createdAt, Instant lapsesAt) {
	}

	/** PUB-5. */
	record PublicMentorView(UUID accountId, @Nullable String githubLogin, String displayName, String experience,
			int yearsOfExperience, Instant memberSince, List<PublicSkillGroup> skillGroups, boolean available,
			int teamSize, int sizeLimit, List<PublicTeamMember> listedMembers, @Nullable PublicMentorViewer viewer) {
	}

	private final TeamStore store;

	private final TeamRequests requests;

	private final StudentDirectory students;

	private final MentorDirectory mentors;

	private final StudentVerification verification;

	TeamViews(TeamStore store, TeamRequests requests, StudentDirectory students, MentorDirectory mentors,
			StudentVerification verification) {
		this.store = store;
		this.requests = requests;
		this.students = students;
		this.mentors = mentors;
		this.verification = verification;
	}

	/** MEN-6; empty for an approved Mentor whose Team the listener has not created yet. */
	@Transactional(readOnly = true)
	Optional<MentorTeamView> mentorTeam(UUID mentorId, boolean approved) {
		Optional<Team> team = this.store.byMentor(mentorId);
		if (team.isEmpty()) {
			return approved ? Optional.of(new MentorTeamView(Status.OPEN, 0, Teams.SIZE_LIMIT,
					TeamRequests.INVITES_PER_DAY, List.of(), List.of(), List.of())) : Optional.empty();
		}
		UUID teamId = team.get().id();
		List<Member> members = this.store.members(teamId);
		List<Request> joinRequests = this.store.pendingOfTeam(teamId, Kind.JOIN_REQUEST);
		List<Request> invitations = this.store.pendingOfTeam(teamId, Kind.INVITATION);
		Set<UUID> onCrew = this.store.reservedAmong(members.stream().map(Member::membershipId).toList());
		Map<UUID, StudentCard> cards = studentCards(concat(members.stream().map(Member::studentId).toList(),
				joinRequests.stream().map(Request::studentId).toList(), invitations.stream().map(Request::studentId).toList()));
		return Optional.of(new MentorTeamView(team.get().status(), members.size(), Teams.SIZE_LIMIT,
				this.requests.invitesLeft(teamId),
				members.stream()
					.filter(member -> cards.containsKey(member.studentId()))
					.map(member -> new TeamMemberRow(member.membershipId(),
							TeamStudentView.of(cards.get(member.studentId())), member.joinedAt(),
							onCrew.contains(member.membershipId())))
					.toList(),
				studentRows(joinRequests, cards), studentRows(invitations, cards)));
	}

	/** The invite Sheet's search, or the exact username when {@code login} is given. */
	@Transactional(readOnly = true)
	TeamStudentSearchView searchStudents(UUID mentorId, String query, Collection<UUID> skillIds,
			@Nullable String login, Pageable pageable) {
		Optional<Team> team = this.store.byMentor(mentorId);
		List<StudentCard> found;
		long total;
		if (login != null && !login.isBlank()) {
			found = this.students.byLogin(login).stream().toList();
			total = found.size();
		}
		else {
			Page<StudentCard> page = this.students.search(query, skillIds, pageable);
			found = page.getContent();
			total = page.getTotalElements();
		}
		List<TeamStudentResult> items = found.stream()
			.map(card -> new TeamStudentResult(TeamStudentView.of(card), relation(team, card.accountId())))
			.toList();
		return new TeamStudentSearchView(items, pageable.getPageNumber(), pageable.getPageSize(), total,
				team.map(found2 -> this.requests.invitesLeft(found2.id())).orElse(TeamRequests.INVITES_PER_DAY),
				team.map(found2 -> this.store.size(found2.id())).orElse(0));
	}

	/**
	 * PUB-4 in the Sheet, for an approved Mentor: a verified Student, or one in a relation with the
	 * Mentor's Team (a member whose verification lapsed, a pending request). Anyone else finds nothing,
	 * as search would not find them (AD-36).
	 */
	@Transactional(readOnly = true)
	TeamStudentProfileView studentProfile(UUID mentorId, UUID studentId) {
		Optional<StudentCard> card = this.students.cards(List.of(studentId)).stream().filter(StudentCard::available).findFirst();
		StudentRelation relation = relation(this.store.byMentor(mentorId), studentId);
		boolean related = relation != StudentRelation.NONE && relation != StudentRelation.OTHER_TEAM;
		if (card.isEmpty() || !(card.get().verified() || related)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		Optional<Member> membership = this.store.activeMembership(studentId);
		TeamMentorView teamMentor = membership.flatMap(member -> mentorViews(List.of(member.mentorId())).stream().findFirst())
			.orElse(null);
		return new TeamStudentProfileView(TeamStudentView.of(card.get()), relation, teamMentor);
	}

	/** STU-6. */
	@Transactional(readOnly = true)
	StudentTeamView studentTeam(UUID studentId) {
		Optional<Member> membership = this.store.activeMembership(studentId);
		StudentTeamMembership team = membership.flatMap(member -> {
			Optional<TeamMentorView> mentor = mentorViews(List.of(member.mentorId())).stream().findFirst();
			return mentor.map(found -> {
				List<Member> members = this.store.members(member.teamId());
				Map<UUID, StudentCard> cards = studentCards(members.stream().map(Member::studentId).toList());
				List<TeamListedMember> listed = new ArrayList<>();
				for (Member other : members) {
					StudentCard card = cards.get(other.studentId());
					boolean you = other.studentId().equals(studentId);
					if (card != null && (card.name() != null || you)) {
						listed.add(new TeamListedMember(card.name() != null ? card.name() : "@" + card.githubLogin(),
								card.school(), you));
					}
				}
				return new StudentTeamMembership(found, this.store.byId(member.teamId()).status(), member.joinedAt(),
						members.size(), listed, this.store.reserved(member.membershipId()));
			});
		}).orElse(null);
		List<Request> pending = this.store.pendingOfStudent(studentId);
		Map<UUID, TeamMentorView> mentorViews = mentorViews(pending.stream().map(Request::mentorId).distinct().toList())
			.stream()
			.collect(Collectors.toMap(TeamMentorView::accountId, Function.identity()));
		return new StudentTeamView(this.verification.isVerified(studentId), team,
				mentorRows(pending, Kind.INVITATION, mentorViews), mentorRows(pending, Kind.JOIN_REQUEST, mentorViews),
				this.requests.joinRequestsLeft(studentId));
	}

	/** STU-6's Mentor search. */
	@Transactional(readOnly = true)
	TeamMentorSearchView searchMentors(UUID studentId, String query, Collection<UUID> skillIds, Pageable pageable) {
		Page<MentorCard> page = this.mentors.search(query, skillIds, pageable);
		List<TeamMentorView> views = withSizes(page.getContent());
		List<TeamMentorResult> items = views.stream()
			.map(view -> new TeamMentorResult(view, mentorRelation(studentId, view.accountId()),
					view.teamSize() >= Teams.SIZE_LIMIT))
			.toList();
		return new TeamMentorSearchView(items, pageable.getPageNumber(), pageable.getPageSize(), page.getTotalElements(),
				this.requests.joinRequestsLeft(studentId), this.store.activeMembership(studentId).isPresent());
	}

	/** PUB-5, for anyone; {@code student} is the signed-in Student viewing it, if any. */
	@Transactional(readOnly = true)
	PublicMentorView publicMentor(String login, @Nullable UUID student) {
		MentorProfile profile = this.mentors.byLogin(login).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		MentorCard card = profile.card();
		UUID mentorId = card.accountId();
		Optional<Team> team = this.store.byMentor(mentorId);
		List<Member> members = team.map(found -> this.store.members(found.id())).orElse(List.of());
		Map<UUID, StudentCard> cards = studentCards(members.stream().map(Member::studentId).toList());
		List<PublicTeamMember> listed = members.stream()
			.map(member -> cards.get(member.studentId()))
			.filter(found -> found != null && found.name() != null)
			.map(found -> new PublicTeamMember(found.name(), found.school(), found.verified()))
			.toList();
		PublicMentorViewer viewer = null;
		if (student != null) {
			Optional<Request> pending = team.flatMap(found -> this.store.pendingBetween(found.id(), student));
			viewer = new PublicMentorViewer(mentorRelation(student, mentorId),
					this.store.activeMembership(student).isPresent(), this.verification.isVerified(student),
					this.requests.joinRequestsLeft(student),
					pending.map(found -> new TeamPendingRequest(found.id(), found.kind(), found.createdAt(), found.lapsesAt()))
						.orElse(null));
		}
		boolean open = team.map(found -> found.status() == Status.OPEN).orElse(true);
		return new PublicMentorView(mentorId, card.githubLogin(), card.displayName(), profile.experience(),
				card.yearsOfExperience(), profile.memberSince(),
				profile.skillGroups().stream().map(group -> new PublicSkillGroup(group.category(), group.skills())).toList(),
				card.available() && open, members.size(), Teams.SIZE_LIMIT, listed, viewer);
	}

	private StudentRelation relation(Optional<Team> team, UUID studentId) {
		Optional<Member> membership = this.store.activeMembership(studentId);
		if (membership.isPresent()) {
			return team.filter(found -> found.id().equals(membership.get().teamId())).isPresent() ? StudentRelation.MEMBER
					: StudentRelation.OTHER_TEAM;
		}
		return team.flatMap(found -> this.store.pendingBetween(found.id(), studentId))
			.map(request -> request.kind() == Kind.INVITATION ? StudentRelation.INVITED : StudentRelation.REQUESTED)
			.orElse(StudentRelation.NONE);
	}

	private MentorRelation mentorRelation(UUID studentId, UUID mentorId) {
		Optional<Team> team = this.store.byMentor(mentorId);
		if (team.isEmpty()) {
			return MentorRelation.NONE;
		}
		if (this.store.activeMembership(studentId).filter(member -> member.teamId().equals(team.get().id())).isPresent()) {
			return MentorRelation.MEMBER;
		}
		return this.store.pendingBetween(team.get().id(), studentId)
			.map(request -> request.kind() == Kind.INVITATION ? MentorRelation.INVITED : MentorRelation.REQUESTED)
			.orElse(MentorRelation.NONE);
	}

	private List<TeamMentorView> mentorViews(List<UUID> mentorIds) {
		return withSizes(this.mentors.cards(mentorIds));
	}

	private List<TeamMentorView> withSizes(List<MentorCard> cards) {
		return cards.stream()
			.map(card -> new TeamMentorView(card.accountId(), card.githubLogin(), card.displayName(),
					card.yearsOfExperience(), card.skills(), card.available(), this.store.sizeOf(card.accountId())))
			.toList();
	}

	private Map<UUID, StudentCard> studentCards(List<UUID> ids) {
		return this.students.cards(ids.stream().distinct().toList())
			.stream()
			.collect(Collectors.toMap(StudentCard::accountId, Function.identity()));
	}

	private static List<TeamStudentRequestRow> studentRows(List<Request> requests, Map<UUID, StudentCard> cards) {
		return requests.stream()
			.filter(request -> cards.containsKey(request.studentId()))
			.map(request -> new TeamStudentRequestRow(request.id(), TeamStudentView.of(cards.get(request.studentId())),
					request.createdAt(), request.lapsesAt()))
			.toList();
	}

	private static List<TeamMentorRequestRow> mentorRows(List<Request> requests, Kind kind,
			Map<UUID, TeamMentorView> mentors) {
		return requests.stream()
			.filter(request -> request.kind() == kind && mentors.containsKey(request.mentorId()))
			.map(request -> new TeamMentorRequestRow(request.id(), mentors.get(request.mentorId()), request.createdAt(),
					request.lapsesAt()))
			.toList();
	}

	@SafeVarargs
	private static List<UUID> concat(List<UUID>... lists) {
		List<UUID> all = new ArrayList<>();
		for (List<UUID> list : lists) {
			all.addAll(list);
		}
		return all;
	}

}
