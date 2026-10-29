import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import type { Session } from '@/features/identity/api'
import { fakeBackend, json, signedInUser } from '@/test/fakeBackend'
import { personReputation, publicPerson } from '@/test/people'

/**
 * Until Story 21.7 deletes the Teams, MEN-6 and STU-6 open for a session that still names the account role the
 * retired Team screens were built on; the backend sends none since Story 20.6, so in the product they show nothing.
 */
function teamSession(role: 'MENTOR' | 'STUDENT'): Session {
  return { ...signedInUser, account: { ...signedInUser.account!, role } } as Session
}

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

const catalog = () =>
  json({ categories: [{ id: 'c1', name: 'Dữ liệu', skills: [{ id: 'supabase', name: 'Supabase', hint: 'x' }] }] })

const student = (overrides: object = {}) => ({
  accountId: 's1',
  githubLogin: 'minhtq',
  name: 'Trần Quang Minh',
  school: 'ĐH Bách khoa Hà Nội',
  major: 'Khoa học máy tính',
  skills: ['React', 'Supabase'],
  verified: true,
  verifiedUntil: '2027-09-30',
  ...overrides,
})

const emptyTeam = { status: 'OPEN', size: 0, sizeLimit: 10, invitesLeft: 20, members: [], joinRequests: [], invitations: [] }

const hung = {
  accountId: 'm1',
  githubLogin: 'hungdm',
  displayName: 'Đỗ Mạnh Hùng',
  yearsOfExperience: 8,
  skills: ['React', 'Supabase'],
  available: true,
  teamSize: 6,
}

const noTeam = (overrides: object = {}) => ({ verified: true, invitations: [], joinRequests: [], requestsLeft: 5, ...overrides })

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('MEN-6, the Mentor\'s Team (FR-6, AD-37)', () => {
  it('shows an empty Team with its size and the way to invite', async () => {
    fakeBackend(teamSession('MENTOR'), { 'GET /api/v1/mentor/team': () => json(emptyTeam) })
    renderAt('/nhom')

    expect(await screen.findByText('0/10 sinh viên')).toBeInTheDocument()
    expect(screen.getByText('Nhóm của bạn chưa có sinh viên nào.')).toBeInTheDocument()
    expect(screen.getByText('Chưa có yêu cầu nào.')).toBeInTheDocument()
    expect(screen.getByText('Bạn cần ít nhất một sinh viên rảnh trong nhóm để ứng tuyển.')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Mời sinh viên' })).toHaveLength(2)
  })

  it('accepts a join request after the confirmation', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    const seen = fakeBackend(teamSession('MENTOR'), {
      'GET /api/v1/mentor/team': () =>
        json({
          ...emptyTeam,
          size: 1,
          members: [{ membershipId: 'ms1', student: student({ accountId: 's2', name: 'Lê Thảo Vy', githubLogin: 'vy-le' }), joinedAt: '2026-10-01T03:00:00Z' }],
          joinRequests: [{ id: 'r1', student: student(), createdAt: '2026-10-18T03:00:00Z', lapsesAt: '2026-11-01T03:00:00Z' }],
        }),
      'POST /api/v1/mentor/team/join-requests/r1/accept': () =>
        json({ id: 'r1', kind: 'JOIN_REQUEST', createdAt: '2026-10-18T03:00:00Z', lapsesAt: '2026-11-01T03:00:00Z' }),
    })
    renderAt('/nhom')

    expect(await screen.findByText('Yêu cầu vào nhóm · 1')).toBeInTheDocument()
    expect(screen.getByText(/gửi 18\/10 · hết hạn 01\/11/)).toBeInTheDocument()
    const members = screen.getByRole('table', { name: 'Thành viên trong nhóm' })
    expect(within(members).getByText('Lê Thảo Vy')).toBeInTheDocument()
    expect(within(members).getByText('Đang rảnh')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Nhận vào nhóm' }))
    const dialog = await screen.findByRole('dialog', { name: 'Nhận Trần Quang Minh vào nhóm?' })
    expect(within(dialog).getByText(/Nhóm sẽ có 2\/10 sinh viên/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Nhận vào nhóm' }))

    await waitFor(() =>
      expect(seen.some((request) => request.method === 'POST' && request.url.endsWith('/join-requests/r1/accept'))).toBe(true),
    )
  })

  it('searches Students in the invite Sheet and invites one', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let invited: unknown
    fakeBackend(teamSession('MENTOR'), {
      'GET /api/v1/mentor/team': () => json(emptyTeam),
      'GET /api/v1/catalog': catalog,
      'GET /api/v1/mentor/team/students': (request) => {
        const url = new URL(request.url)
        if (url.searchParams.get('login')) {
          return json({ items: [], page: 0, size: 20, total: 0, invitesLeft: 20, teamSize: 0 })
        }
        return json({
          items: [
            { student: student(), relation: 'NONE' },
            { student: student({ accountId: 's3', name: undefined, githubLogin: 'linh-do', school: undefined }), relation: 'OTHER_TEAM' },
            { student: student({ accountId: 's4', name: 'Đào Minh Khôi', githubLogin: 'khoi' }), relation: 'INVITED' },
          ],
          page: 0,
          size: 20,
          total: 3,
          invitesLeft: 8,
          teamSize: 0,
        })
      },
      'POST /api/v1/mentor/team/invitations': async (request) => {
        invited = await request.json()
        return json({ id: 'i1', kind: 'INVITATION', createdAt: '2026-10-19T03:00:00Z', lapsesAt: '2026-11-02T03:00:00Z' }, 201)
      },
    })
    renderAt('/nhom')

    await user.click((await screen.findAllByRole('button', { name: 'Mời sinh viên' }))[0])
    const sheet = await screen.findByRole('dialog', { name: 'Mời sinh viên vào nhóm' })
    expect(within(sheet).getAllByRole('button', { name: 'Tìm' })[0]).toBeDisabled()
    await user.type(within(sheet).getByLabelText('Tìm sinh viên'), 'supabase{Enter}')

    expect(await within(sheet).findByText('Kết quả cho “supabase” (3)')).toBeInTheDocument()
    expect(within(sheet).getByText('Còn 8 lời mời hôm nay · nhóm 0/10')).toBeInTheDocument()
    expect(within(sheet).getByText('@linh-do đang ở trong một nhóm khác.')).toBeInTheDocument()
    expect(within(sheet).getByRole('button', { name: 'Đã mời' })).toBeDisabled()
    await user.click(within(sheet).getByRole('button', { name: 'Mời Trần Quang Minh vào nhóm' }))
    await waitFor(() => expect(invited).toEqual({ studentId: 's1' }))

    await user.type(within(sheet).getByLabelText('Tên người dùng GitHub'), 'khong-co')
    await user.click(within(sheet).getAllByRole('button', { name: 'Tìm' })[1])
    expect(await within(sheet).findByText('Không tìm thấy sinh viên nào với tên người dùng này.')).toBeInTheDocument()
  })

  it('removes a member after the confirmation, and not one on a Crew', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    const seen = fakeBackend(teamSession('MENTOR'), {
      'GET /api/v1/mentor/team': () =>
        json({
          ...emptyTeam,
          size: 2,
          members: [
            { membershipId: 'ms1', student: student(), joinedAt: '2026-10-01T03:00:00Z', onCrew: false },
            { membershipId: 'ms2', student: student({ accountId: 's2', name: 'Lê Thảo Vy', githubLogin: 'vy-le' }), joinedAt: '2026-10-01T03:00:00Z', onCrew: true },
          ],
        }),
      'DELETE /api/v1/mentor/team/members/ms1': () => new Response(null, { status: 204 }),
    })
    renderAt('/nhom')

    expect(await screen.findByRole('button', { name: 'Xóa Lê Thảo Vy khỏi nhóm' })).toBeDisabled()
    expect(screen.getByText('Lê Thảo Vy đang làm một công việc; chưa thể rời nhóm cho đến khi công việc kết thúc.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Xóa Trần Quang Minh khỏi nhóm' }))
    const dialog = await screen.findByRole('dialog', { name: 'Xóa Trần Quang Minh khỏi nhóm?' })
    await user.click(within(dialog).getByRole('button', { name: 'Xóa khỏi nhóm' }))

    await waitFor(() =>
      expect(seen.some((request) => request.method === 'DELETE' && request.url.endsWith('/members/ms1'))).toBe(true),
    )
  })

  it('shows a closed Team without actions', async () => {
    fakeBackend(teamSession('MENTOR'), { 'GET /api/v1/mentor/team': () => json({ ...emptyTeam, status: 'CLOSED' }) })
    renderAt('/nhom')

    expect(await screen.findByText('Nhóm tạm ngừng nhận thành viên')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Mời sinh viên' })).not.toBeInTheDocument()
  })
})

describe('STU-6, the Student\'s Team (FR-6, AD-36)', () => {
  it('answers an invitation after the confirmation', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    let accepted = false
    fakeBackend(teamSession('STUDENT'), {
      'GET /api/v1/team': () =>
        json(noTeam({ invitations: [{ id: 'i1', mentor: hung, createdAt: '2026-10-17T03:00:00Z', lapsesAt: '2026-10-31T03:00:00Z' }] })),
      'GET /api/v1/catalog': catalog,
      'POST /api/v1/team/invitations/i1/accept': () => {
        accepted = true
        return json({ id: 'i1', kind: 'INVITATION', createdAt: '2026-10-17T03:00:00Z', lapsesAt: '2026-10-31T03:00:00Z' })
      },
    })
    renderAt('/nhom')

    expect(await screen.findByText('Lời mời vào nhóm · 1')).toBeInTheDocument()
    expect(screen.getByText('Đỗ Mạnh Hùng mời bạn vào nhóm')).toBeInTheDocument()
    expect(screen.getByText(/mời 17\/10 · hết hạn 31\/10/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Đồng ý' }))
    const dialog = await screen.findByRole('dialog', { name: 'Vào nhóm của Đỗ Mạnh Hùng?' })
    await user.click(within(dialog).getByRole('button', { name: 'Đồng ý' }))

    await waitFor(() => expect(accepted).toBe(true))
  })

  it('searches Mentors and opens PUB-4 in the Sheet (Story 21.3)', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(teamSession('STUDENT'), {
      'GET /api/v1/team': () => json(noTeam()),
      'GET /api/v1/catalog': catalog,
      'GET /api/v1/team/mentors': () =>
        json({ items: [{ mentor: hung, relation: 'NONE', teamFull: false }], page: 0, size: 20, total: 1, requestsLeft: 5, inTeam: false }),
      'GET /api/v1/users/hungdm': () => json(publicPerson('hungdm')),
      'GET /api/v1/users/hungdm/reputation': () => json(personReputation()),
    })
    renderAt('/nhom')

    await user.type(await screen.findByLabelText('Từ khóa'), 'supabase{Enter}')
    expect(await screen.findByText('Kết quả cho “supabase” (1)')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Xem hồ sơ của Đỗ Mạnh Hùng' }))

    const sheet = await screen.findByRole('dialog')
    expect(await within(sheet).findByText('Giao diện web')).toBeInTheDocument()
    expect(within(sheet).getByText('Kỹ sư phần mềm.')).toBeInTheDocument()
    expect(within(sheet).getByRole('link', { name: 'Mở hồ sơ đầy đủ' })).toHaveAttribute('href', '/nguoi-dung/hungdm')
  })

  it('asks an unverified Student to verify first', async () => {
    fakeBackend(teamSession('STUDENT'), { 'GET /api/v1/team': () => json(noTeam({ verified: false })) })
    renderAt('/nhom')

    expect(await screen.findByText('Xác minh là sinh viên để vào nhóm của một người hướng dẫn.')).toBeInTheDocument()
    expect(screen.queryByLabelText('Từ khóa')).not.toBeInTheDocument()
  })

  it('leaves the Team after the confirmation, and not while on a Crew', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    let left = false
    const membership = (onCrew: boolean) => ({
      verified: true,
      team: { mentor: hung, status: 'OPEN', joinedAt: '2026-06-20T03:00:00Z', size: 1, listed: [], onCrew },
      invitations: [],
      joinRequests: [],
      requestsLeft: 5,
    })
    fakeBackend(teamSession('STUDENT'), {
      'GET /api/v1/team': () => json(membership(false)),
      'DELETE /api/v1/team/membership': () => {
        left = true
        return new Response(null, { status: 204 })
      },
    })
    renderAt('/nhom')

    expect(await screen.findByText('Sau khi rời, bạn có thể xin vào nhóm của người hướng dẫn khác.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Rời nhóm' }))
    const dialog = await screen.findByRole('dialog', { name: 'Rời nhóm của Đỗ Mạnh Hùng?' })
    await user.click(within(dialog).getByRole('button', { name: 'Rời nhóm' }))
    await waitFor(() => expect(left).toBe(true))
  })

  it('keeps a Student on a Crew in the Team', async () => {
    fakeBackend(teamSession('STUDENT'), {
      'GET /api/v1/team': () =>
        json({
          verified: true,
          team: { mentor: hung, status: 'OPEN', joinedAt: '2026-06-20T03:00:00Z', size: 1, listed: [], onCrew: true },
          invitations: [],
          joinRequests: [],
          requestsLeft: 5,
        }),
    })
    renderAt('/nhom')

    expect(await screen.findByText('Bạn đang làm một công việc; chưa thể rời nhóm cho đến khi công việc kết thúc.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Rời nhóm' })).toBeDisabled()
  })

  it('shows the Mentor and the listed members once in a Team', async () => {
    fakeBackend(teamSession('STUDENT'), {
      'GET /api/v1/team': () =>
        json({
          verified: true,
          team: {
            mentor: hung,
            status: 'OPEN',
            joinedAt: '2026-06-20T03:00:00Z',
            size: 6,
            listed: [
              { name: 'Trần Quang Minh', school: 'ĐH Bách khoa Hà Nội', you: true },
              { name: 'Lê Thảo Vy', school: 'ĐH Bách khoa Hà Nội', you: false },
            ],
          },
          invitations: [],
          joinRequests: [],
          requestsLeft: 5,
        }),
    })
    renderAt('/nhom')

    expect(await screen.findByRole('link', { name: 'Đỗ Mạnh Hùng' })).toHaveAttribute('href', '/mentor/hungdm')
    expect(screen.getByText('8 năm kinh nghiệm · bạn ở trong nhóm từ 20/06/2026')).toBeInTheDocument()
    expect(screen.getByText('6 sinh viên · 4 người chọn không hiện tên công khai')).toBeInTheDocument()
    expect(screen.getByText('Trần Quang Minh (bạn)')).toBeInTheDocument()
  })
})
