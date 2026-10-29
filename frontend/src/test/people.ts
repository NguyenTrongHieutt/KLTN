/** The three Verified facts in their fixed order: GitHub linked in 10/2026, the rest "Chưa xác minh" by default. */
export function verifiedFacts(
  login: string,
  student: { school?: string; name?: string; major?: string } | null = null,
): Record<string, unknown>[] {
  return [
    { kind: 'GITHUB', verifiedAt: '2026-10-05T03:00:00Z', githubLogin: login },
    student ? { kind: 'STUDENT', verifiedAt: '2026-09-02T03:00:00Z', ...student } : { kind: 'STUDENT' },
    { kind: 'LINKEDIN' },
  ]
}

/** PUB-4's identity half (`GET /api/v1/users/{username}`), unlocked, not the viewer's own. */
export function publicPerson(login: string, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    locked: false,
    displayName: 'Đỗ Mạnh Hùng',
    githubLogin: login,
    verified: verifiedFacts(login),
    experience: 'Kỹ sư phần mềm.',
    yearsOfExperience: 8,
    links: [],
    repositories: [],
    skillGroups: [{ category: 'Giao diện web', skills: ['React'] }],
    memberSince: '2026-06-01T03:00:00Z',
    own: false,
    ...overrides,
  }
}

/** One role's history with nothing in it: "Chưa có". */
export function emptyRole(): Record<string, unknown> {
  return { jobs: 0, jobTypes: [], count: 0, portfolio: [] }
}

/** PUB-4's two histories (`GET /api/v1/users/{username}/reputation`), empty by default. */
export function personReputation(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return { mentor: emptyRole(), crew: emptyRole(), newcomer: true, ...overrides }
}

/** One person's histories in short (`GET /api/v1/reputation/histories`), empty by default. */
export function shortHistory(username: string, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    username,
    mentor: { jobs: 0, count: 0, portfolio: 0 },
    crew: { jobs: 0, count: 0, portfolio: 0 },
    newcomer: true,
    ...overrides,
  }
}

/** The Fit metrics of a person with no history at all. */
export function emptyMetrics(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    sharedSkills: [],
    skillsSought: 2,
    jobTypeMatches: [],
    jobsAsMentor: 0,
    jobsAsCrew: 0,
    activeJobsAsMentor: 0,
    verifications: ['GITHUB'],
    ...overrides,
  }
}
