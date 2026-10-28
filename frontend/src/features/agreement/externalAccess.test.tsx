import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedInUser, inEveryJob, heldJobs } from '@/test/fakeBackend'

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

const job = {
  id: 'j1',
  productId: 'p1',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'AGREEMENT',
  title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
  jobTypes: ['BUG_FIX'],
  skills: [],
  screenshots: [],
  createdAt: '2026-10-12T03:00:00Z',
  updatedAt: '2026-10-18T03:00:00Z',
  version: 7,
  mentor: { displayName: 'Đỗ Mạnh Hùng', githubLogin: 'hungdm' },
  canCancelSelection: false,
  agreementShared: true,
  agreementConfirmed: true,
}

const supabase = {
  id: 'a1',
  system: 'Supabase',
  purpose: 'đọc và ghi bảng đặt bàn',
  environment: 'NON_PRODUCTION',
  accessLevel: 'WRITE',
}

const content = {
  scope: 'Sửa lỗi hai khách đặt trùng bàn.',
  priceVnd: 800000,
  codeJob: true,
  assessment: false,
  skills: [],
  milestones: [
    { id: 'm1', title: 'Sửa lỗi trùng giờ', priceVnd: 800000, dueOn: '2026-11-20', revisionRounds: 2, criteria: [] },
  ],
  externalAccess: [supabase],
}

const agreement = {
  versionNo: 1,
  sharedAt: '2026-10-19T03:00:00Z',
  versions: [{ versionNo: 1, sharedAt: '2026-10-19T03:00:00Z' }],
  quote: { priceVnd: 800000, timelineDays: 7 },
  crew: ['vy-le'],
  crewConfirmed: true,
  latestVersionNo: 1,
  comments: [],
  changeRequested: false,
  approvedAt: '2026-10-19T05:00:00Z',
  content,
  confirmed: {
    versionNo: 1,
    draftVersionNo: 1,
    crew: ['vy-le'],
    liveAccessIds: [],
    freezeCommitted: true,
    approvedAt: '2026-10-19T05:00:00Z',
    confirmedAt: '2026-10-19T06:00:00Z',
    content,
  },
}

const item = (overrides: object = {}) => ({ ...supabase, live: false, identifiers: [], ...overrides })

describe('FR-66 External Access after confirmation (N-8, UX-DR83)', () => {
  it('shows the Customer each account beside its GitHub username and records "Đã cấp quyền"', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let granted = false
    const identifiers = [
      { githubLogin: 'hungdm', identifier: 'hungdm.work@gmail.com' },
      { githubLogin: 'vy-le', identifier: 'vy.le.dev@gmail.com' },
    ]
    const view = () => ({ viewer: 'CUSTOMER', open: true, items: [item({ granted, identifiers })] })
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(job),
      'GET /api/v1/jobs/j1/chat': () => json({ chatId: 'c1', unread: false }),
      'GET /api/v1/jobs/j1/agreement': () => json(agreement),
      'GET /api/v1/jobs/j1/external-access': () => json(view()),
      'POST /api/v1/jobs/j1/external-access/a1/granted': () => {
        granted = true
        return json(view())
      },
    })
    renderAt('/cong-viec/j1/thoa-thuan')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Cấp quyền cho người thực hiện' })).closest('section')!
    const card = within(section).getByRole('heading', { level: 3, name: 'Supabase · đọc và ghi bảng đặt bàn' }).closest('article')!
    expect(within(card).getByText('Môi trường thử')).toBeInTheDocument()
    expect(within(card).getByText(/Mời đúng các tài khoản dưới đây/)).toBeInTheDocument()
    expect(within(card).getByText(/Đừng gửi mật khẩu của bạn qua Trò chuyện/)).toBeInTheDocument()
    expect(within(card).getByText('@vy-le')).toBeInTheDocument()
    expect(within(card).getByText('vy.le.dev@gmail.com')).toBeInTheDocument()

    const tick = within(card).getByRole('checkbox', { name: 'Đã cấp quyền' })
    await user.click(tick)
    await waitFor(() => expect(tick).toBeChecked())
    expect(tick).toBeDisabled()
  })

  it('lets a Crew member give their own account and shows a refusal plainly', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedInUser, {
      ...inEveryJob('CREW'),
      ...heldJobs({ jobId: 'j1', title: 'Sửa lỗi đặt bàn và thêm nhắc lịch', role: 'CREW', crewKind: 'ACTIVE' }),
      'GET /api/v1/crew/assignments': () =>
        json([
          {
            jobId: 'j1',
            title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
            productName: 'Sổ đặt bàn Quán Mây',
            mentorName: 'Đỗ Mạnh Hùng',
            status: 'ASSIGNED',
            scope: 'Sửa lỗi hai khách đặt trùng bàn cùng giờ.',
            milestones: [],
            sharePercent: 30,
            versionNo: 2,
            versionSentAt: '2026-10-17T03:00:00Z',
            signed: true,
            codeJob: true,
            commitEmailNoticeAcknowledged: true,
          },
        ]),
      'GET /api/v1/team': () =>
        json({ invitations: [], joinRequests: [], requestsLeft: 3, verified: true, team: { joinedAt: '2026-10-01T03:00:00Z' } }),
      'GET /api/v1/jobs/j1/external-access': () => json({ viewer: 'CREW', open: true, items: [item()] }),
      'PUT /api/v1/jobs/j1/external-access/a1/identifier': async (request) => {
        const body = (await request.json()) as { identifier: string }
        sent.push(body)
        if (body.identifier.startsWith('sk')) {
          return json({ code: 'GUARD_FAILED', reason: 'IDENTIFIER_LOOKS_LIKE_SECRET' }, 422)
        }
        return json({ viewer: 'CREW', open: true, items: [item({ mine: body.identifier })] })
      },
    })
    renderAt('/cong-viec')

    expect(await screen.findByRole('heading', { level: 3, name: 'Tài khoản để khách hàng mời' })).toBeInTheDocument()
    const field = screen.getByRole('textbox', { name: 'Tài khoản của bạn trên Supabase' })
    expect(screen.getByText(/Chỉ khách hàng thấy tài khoản này/)).toBeInTheDocument()
    await user.type(field, 'sk_test_value')
    await user.click(screen.getByRole('button', { name: 'Lưu' }))
    expect(await screen.findByText(/Đây giống một mật khẩu hoặc khóa bí mật/)).toBeInTheDocument()

    await user.clear(field)
    await user.type(field, 'vy.le.dev@gmail.com')
    await user.click(screen.getByRole('button', { name: 'Lưu' }))
    await waitFor(() => expect(sent).toHaveLength(2))
    expect(sent[1]).toEqual({ identifier: 'vy.le.dev@gmail.com' })
    await waitFor(() => expect(screen.queryByText(/Đây giống một mật khẩu hoặc khóa bí mật/)).not.toBeInTheDocument())
  })
})
