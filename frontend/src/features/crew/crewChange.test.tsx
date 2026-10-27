import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedIn } from '@/test/fakeBackend'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

afterEach(() => {
  vi.unstubAllGlobals()
})

const MENTOR = '00000000-0000-7000-8000-000000000001'

const mentorJob = {
  id: 'j1',
  title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'ACTIVE',
  version: 7,
  jobTypes: ['BUG_FIX'],
  selectedAt: '2026-10-18T03:00:00Z',
  codeJob: false,
  commitEmailNoticeAcknowledged: true,
  agreementConfirmed: true,
}

const draft = {
  revision: 1,
  state: 'ACTIVE',
  review: { comments: [], draftMatchesShared: true },
  versions: [],
  problems: [],
  content: {
    scope: 'Sửa lỗi hai khách đặt trùng bàn.',
    priceVnd: 3000000,
    codeJob: false,
    assessment: false,
    skills: [],
    milestones: [{ id: 'm1', title: 'Sửa lỗi trùng giờ', priceVnd: 1500000, dueOn: '2026-11-20', revisionRounds: 2, criteria: [] }],
    externalAccess: [],
  },
}

const row = (studentId: string, name: string, login: string, overrides: object = {}) => ({
  rowId: `r-${studentId}`,
  studentId,
  name,
  githubLogin: login,
  status: 'ACTIVE',
  signedAt: '2026-10-18T04:00:00Z',
  confirmedLatest: true,
  percent: 30,
  leaving: false,
  ...overrides,
})

const panel = (overrides: object = {}) => ({
  crewVersion: 4,
  capPercent: 40,
  largeCrews: true,
  locked: true,
  crewConfirmed: true,
  confirmedCount: 2,
  total: 2,
  mentor: { accountId: MENTOR, displayName: 'Đỗ Mạnh Hùng', githubLogin: 'hungdm', percent: 40 },
  rows: [row('s-vy', 'Lê Thảo Vy', 'vy-le'), row('s-tung', 'Phạm Hải Tùng', 'tungph')],
  latest: { versionNo: 1, status: 'IN_FORCE', createdAt: '2026-10-18T03:00:00Z' },
  team: [
    { studentId: 's-vy', name: 'Lê Thảo Vy', githubLogin: 'vy-le', state: 'IN_CREW' },
    { studentId: 's-tung', name: 'Phạm Hải Tùng', githubLogin: 'tungph', state: 'IN_CREW' },
    { studentId: 's-anh', name: 'Ngô Bảo Anh', githubLogin: 'baoanh-ng', state: 'FREE' },
  ],
  milestones: [{ title: 'Sửa lỗi trùng giờ', priceVnd: 1500000 }],
  reconfirmOwed: false,
  change: { held: false, inForceVersionNo: 1, confirmed: 0, total: 0 },
  ...overrides,
})

const mentorBackend = (crew: () => Response, extra: Record<string, (request: Request) => Response | Promise<Response>> = {}) =>
  fakeBackend(signedIn('MENTOR'), {
    'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
    'GET /api/v1/mentor/jobs/j1/agreement': () => json(draft),
    'GET /api/v1/mentor/jobs/j1/crew': crew,
    'GET /api/v1/jobs/j1/chat': () => json({ chatId: 'c1', unread: false }),
    'GET /api/v1/catalog': () => json({ categories: [] }),
    ...extra,
  })

describe('Story 11.3: "Đổi nhóm thực hiện" on JOB-6 (FR-75, AD-41, UX-DR60)', () => {
  it('swaps a member in a draft and saves it to wait for every confirmation', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let saved: unknown
    let current = panel()
    mentorBackend(() => json(current), {
      'PUT /api/v1/mentor/jobs/j1/crew/change': async (request) => {
        saved = await request.json()
        current = panel({
          crewVersion: 5,
          latest: { versionNo: 2, status: 'PENDING', createdAt: '2026-10-21T03:00:00Z' },
          rows: [
            row('s-vy', 'Lê Thảo Vy', 'vy-le', { confirmedLatest: false }),
            row('s-tung', 'Phạm Hải Tùng', 'tungph', { confirmedLatest: false, percent: null, leaving: true }),
            row('s-anh', 'Ngô Bảo Anh', 'baoanh-ng', { status: 'ASSIGNED', signedAt: null, confirmedLatest: false }),
          ],
          change: { held: false, waitingVersionNo: 2, inForceVersionNo: 1, confirmed: 0, total: 2 },
        })
        return json(current)
      },
    })
    renderAt('/cong-viec/j1/thoa-thuan')

    expect(await screen.findByText(/Công việc đang chạy: nhóm chỉ thay đổi qua/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Đổi nhóm thực hiện' }))
    const editor = screen.getByRole('region', { name: 'Đổi nhóm thực hiện' })
    await user.click(within(editor).getByRole('button', { name: 'Bỏ Phạm Hải Tùng khỏi nhóm' }))
    expect(within(editor).getByText('Sẽ rời nhóm khi áp dụng')).toBeInTheDocument()
    await user.click(within(editor).getByRole('button', { name: 'Thêm sinh viên' }))
    await user.click(within(editor).getByRole('button', { name: 'Thêm Ngô Bảo Anh' }))
    const apply = within(editor).getByRole('button', { name: 'Áp dụng khi mọi người đã xác nhận' })
    expect(apply).toBeDisabled()
    await user.type(within(editor).getByRole('textbox', { name: 'Phần của Ngô Bảo Anh, phần trăm' }), '30')
    await user.click(apply)

    await waitFor(() =>
      expect(saved).toEqual({
        crewVersion: 4,
        shares: [
          { accountId: MENTOR, percent: 40 },
          { accountId: 's-vy', percent: 30 },
          { accountId: 's-anh', percent: 30 },
        ],
      }),
    )
    expect(await screen.findByText('Đang chờ áp dụng: 0/2 đã xác nhận')).toBeInTheDocument()
    expect(screen.getByText('Đang tham gia · sẽ rời khi áp dụng phiên bản 2')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sửa thay đổi' })).toBeEnabled()
  })

  it('waits while a Milestone is Held', { timeout: 20_000 }, async () => {
    mentorBackend(() => json(panel({ change: { held: true, inForceVersionNo: 1, confirmed: 0, total: 0 } })))
    renderAt('/cong-viec/j1/thoa-thuan')

    expect(await screen.findByRole('button', { name: 'Đổi nhóm thực hiện' })).toBeDisabled()
    expect(screen.getByText(/Đang có giai đoạn giữ tiền/)).toBeInTheDocument()
  })

  it('leaves a member the lock released out of the change', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    mentorBackend(() =>
      json(
        panel({
          rows: [
            row('s-vy', 'Lê Thảo Vy', 'vy-le'),
            row('s-tung', 'Phạm Hải Tùng', 'tungph', { status: 'RELEASED', releasedReason: 'LEFT', percent: undefined }),
          ],
        }),
      ),
    )
    renderAt('/cong-viec/j1/thoa-thuan')

    await user.click(await screen.findByRole('button', { name: 'Đổi nhóm thực hiện' }))
    const editor = screen.getByRole('region', { name: 'Đổi nhóm thực hiện' })
    // Story 20.4: a lock counts as leaving, so the member is gone before the change.
    expect(within(editor).queryByText('Phạm Hải Tùng')).toBeNull()
    expect(within(editor).queryByRole('button', { name: 'Giữ Phạm Hải Tùng lại trong nhóm' })).toBeNull()
    expect(within(editor).queryByRole('textbox', { name: 'Phần của Phạm Hải Tùng, phần trăm' })).toBeNull()
    expect(within(editor).getByRole('textbox', { name: 'Phần của Lê Thảo Vy, phần trăm' })).toBeInTheDocument()
  })
})

describe('Story 11.3: an active member confirms a waiting change on STU-4', () => {
  it('shows the new version to confirm, without "Từ chối"', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/crew/assignments': () =>
        json([
          {
            jobId: 'j1',
            title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
            jobState: 'ACTIVE',
            productName: 'Sổ đặt bàn Quán Mây',
            mentorName: 'Đỗ Mạnh Hùng',
            status: 'ACTIVE',
            scope: 'Sửa lỗi hai khách đặt trùng bàn.',
            milestones: [],
            sharePercent: 35,
            versionNo: 2,
            versionSentAt: '2026-10-21T03:00:00Z',
            signed: true,
            codeJob: false,
            commitEmailNoticeAcknowledged: true,
            changeWaiting: true,
          },
        ]),
      'GET /api/v1/team': () =>
        json({ invitations: [], joinRequests: [], requestsLeft: 3, verified: true, team: { joinedAt: '2026-10-01T03:00:00Z' } }),
    })
    renderAt('/cong-viec')

    expect(await screen.findByText('Phần của bạn: 35% số tiền mỗi giai đoạn được nghiệm thu')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ký và xác nhận' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Từ chối' })).toBeNull()
    expect(screen.getByText(/Bạn vẫn ở trong nhóm theo phiên bản đang áp dụng/)).toBeInTheDocument()
    expect(screen.queryByText(/Phần của bạn: 35% · điều khoản phiên bản 2/)).toBeNull()
  })
})
