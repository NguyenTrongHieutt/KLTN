import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedInUser, inEveryJob } from '@/test/fakeBackend'
import { draftDays, inOrder, moved } from './reviewTargets'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

const MENTOR = '00000000-0000-7000-8000-000000000001'

const milestone = (id: string, title: string, overrides: object = {}) => ({
  id,
  title,
  priceVnd: 400000,
  dueOn: '2026-10-30',
  revisionRounds: 2,
  criteria: [{ id: `${id}-c1`, statement: `Tiêu chí của ${title}`, checkSteps: '1. Mở trang.\n2. Đặt bàn.' }],
  ...overrides,
})

const content = {
  scope: 'Sửa lỗi hai khách đặt trùng bàn.',
  priceVnd: 800000,
  codeJob: true,
  assessment: false,
  skills: [],
  milestones: [milestone('m1', 'Sửa lỗi trùng giờ'), milestone('m2', 'Nhắc lịch', { dueOn: '2026-11-05' })],
  externalAccess: [{ id: 'a1', system: 'Supabase', purpose: 'Đọc bảng đặt bàn', environment: 'NON_PRODUCTION', accessLevel: 'READ' }],
}

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
}

const shared = (overrides: object = {}) => ({
  versionNo: 1,
  sharedAt: '2026-10-20T09:20:00Z',
  versions: [{ versionNo: 1, sharedAt: '2026-10-20T09:20:00Z' }],
  quote: { priceVnd: 800000, timelineDays: 7 },
  crew: ['minhtq'],
  crewConfirmed: false,
  latestVersionNo: 1,
  agreementStartedAt: '2026-10-19T03:00:00Z',
  comments: [],
  changeRequested: false,
  content,
  ...overrides,
})

const customerBackend = (agreement: () => Response, extra: Record<string, (request: Request) => Response | Promise<Response>> = {}) =>
  fakeBackend(signedInUser, {
    ...inEveryJob('CUSTOMER'),
    'GET /api/v1/jobs/j1': () => json(job),
    'GET /api/v1/jobs/j1/chat': () => json({ chatId: 'c1', unread: false }),
    'GET /api/v1/jobs/j1/agreement': agreement,
    ...extra,
  })

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('the review helpers', () => {
  it('reorders, moves, and counts the timeline days', () => {
    expect(moved(['a', 'b', 'c'], 2, -1)).toEqual(['a', 'c', 'b'])
    expect(moved(['a', 'b'], 0, -1)).toEqual(['a', 'b'])
    expect(inOrder([{ id: 'a' }, { id: 'b' }], ['b', 'a']).map((item) => item.id)).toEqual(['b', 'a'])
    expect(draftDays('2026-10-19T03:00:00Z', ['2026-10-30', '2026-11-05'])).toBe(17)
  })
})

describe('JOB-6 for the Customer: review and approve the draft (FR-16, FR-17)', () => {
  it('compares with the quote, comments, and asks for changes with the new order', { timeout: 25_000 }, async () => {
    const user = userEvent.setup()
    let comment: unknown
    let changes: unknown
    customerBackend(() => json(shared()), {
      'POST /api/v1/jobs/j1/agreement/comments': async (request) => {
        comment = await request.json()
        return json({ id: 'k1', versionNo: 1, targetKind: 'MILESTONE', targetId: 'm2', body: 'Giá hơi cao', createdAt: '2026-10-20T10:00:00Z' }, 201)
      },
      'POST /api/v1/jobs/j1/agreement/changes': async (request) => {
        changes = await request.json()
        return new Response(null, { status: 204 })
      },
    })
    renderAt('/cong-viec/j1/thoa-thuan')

    const compare = await screen.findByRole('heading', { name: 'So với báo giá' })
    const rows = within(compare.closest('section') as HTMLElement).getAllByRole('listitem')
    expect(rows[0]).toHaveTextContent('Khớp báo giá')
    expect(rows[1]).toHaveTextContent(/17 ngày.*Báo giá: 7 ngày.*Khác báo giá/)
    expect(screen.getAllByText(/1\. Mở trang\./)).toHaveLength(2)
    expect(screen.getByText('Chờ nhóm thực hiện xác nhận')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Góp ý về Giai đoạn 2 · Nhắc lịch' }))
    await user.type(screen.getByRole('textbox', { name: 'Góp ý về Giai đoạn 2 · Nhắc lịch' }), 'Giá hơi cao')
    await user.click(screen.getByRole('button', { name: 'Gửi góp ý' }))
    expect((await screen.findAllByText('Đã gửi góp ý.')).length).toBeGreaterThan(0)
    expect(comment).toEqual({ versionNo: 1, targetKind: 'MILESTONE', targetId: 'm2', body: 'Giá hơi cao' })

    await user.click(screen.getByRole('button', { name: 'Đưa Giai đoạn 2 · Nhắc lịch lên' }))
    expect(screen.getAllByRole('heading', { level: 3 }).map((heading) => heading.textContent)).toEqual([
      'Giai đoạn 1 · Nhắc lịch',
      'Giai đoạn 2 · Sửa lỗi trùng giờ',
    ])
    expect(screen.getByRole('button', { name: 'Duyệt thỏa thuận' })).toBeDisabled()
    expect(screen.getByText(/hãy gửi yêu cầu sửa trước khi duyệt/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Yêu cầu sửa' }))
    const dialog = await screen.findByRole('dialog', { name: 'Yêu cầu sửa bản nháp 1?' })
    expect(within(dialog).getByText('Kèm thứ tự giai đoạn mới.')).toBeInTheDocument()
    await user.type(within(dialog).getByRole('textbox', { name: 'Bạn muốn sửa gì?' }), 'Làm nhắc lịch trước')
    await user.click(within(dialog).getByRole('button', { name: 'Gửi yêu cầu sửa' }))

    expect((await screen.findAllByText('Đã gửi yêu cầu sửa cho người hướng dẫn.')).length).toBeGreaterThan(0)
    expect(changes).toEqual({ version: 7, versionNo: 1, note: 'Làm nhắc lịch trước', milestoneOrder: ['m2', 'm1'] })
  })

  it('warns before a live system, needs N-6, and approves with the choices', { timeout: 25_000 }, async () => {
    const user = userEvent.setup()
    let approved: unknown
    customerBackend(() => json(shared()), {
      'POST /api/v1/jobs/j1/agreement/approve': async (request) => {
        approved = await request.json()
        return new Response(null, { status: 204 })
      },
    })
    renderAt('/cong-viec/j1/thoa-thuan')

    const live = await screen.findByRole('checkbox', { name: 'Đây là hệ thống thật đang dùng' })
    await user.click(live)
    let warning = await screen.findByRole('dialog', { name: 'Chọn hệ thống thật?' })
    await user.click(within(warning).getByRole('button', { name: 'Dùng môi trường thử' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Chọn hệ thống thật?' })).toBeNull())
    expect(live).not.toBeChecked()
    await user.click(live)
    warning = await screen.findByRole('dialog', { name: 'Chọn hệ thống thật?' })
    await user.click(within(warning).getByRole('button', { name: 'Vẫn chọn hệ thống thật' }))
    expect(await screen.findByText('Hệ thống thật')).toBeInTheDocument()

    const approve = screen.getByRole('button', { name: 'Duyệt thỏa thuận' })
    expect(approve).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Cách tạm dừng đồng bộ' }))
    expect(screen.getByText(/Lovable: đừng sửa dự án/)).toBeInTheDocument()
    await user.click(screen.getByRole('checkbox', { name: /tôi sẽ không thay đổi dự án gốc/ }))
    await user.click(approve)
    const dialog = await screen.findByRole('dialog', { name: 'Duyệt bản nháp 1?' })
    expect(within(dialog).getByText('Người thực hiện được vào 1 hệ thống thật bạn đã chọn.')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Duyệt' }))

    expect((await screen.findAllByText('Đã duyệt bản nháp. Người hướng dẫn được báo để xác nhận.')).length).toBeGreaterThan(0)
    expect(approved).toEqual({ version: 7, versionNo: 1, freezeCommitment: true, liveAccessIds: ['a1'] })
  })

  it('reads the confirmed Agreement with its confirmers and no decision left', { timeout: 15_000 }, async () => {
    customerBackend(() =>
      json(
        shared({
          approvedAt: '2026-10-20T11:00:00Z',
          crewConfirmed: true,
          versions: [
            { versionNo: 1, sharedAt: '2026-10-19T09:20:00Z' },
            { versionNo: 2, sharedAt: '2026-10-20T09:20:00Z' },
          ],
          versionNo: 2,
          latestVersionNo: 2,
          confirmed: {
            versionNo: 1,
            draftVersionNo: 2,
            crew: ['minhtq'],
            liveAccessIds: ['a1'],
            freezeCommitted: true,
            approvedAt: '2026-10-20T11:00:00Z',
            confirmedAt: '2026-10-20T12:00:00Z',
            content,
          },
        }),
      ),
    )
    renderAt('/cong-viec/j1/thoa-thuan')

    expect(await screen.findByText('Khách hàng đã duyệt · Người hướng dẫn đã xác nhận')).toBeInTheDocument()
    expect(screen.getByRole('option', { name: /Phiên bản 1 · đã xác nhận/ })).toBeInTheDocument()
    expect(screen.getByText('Hệ thống thật')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Duyệt thỏa thuận' })).toBeNull()
    expect(screen.queryByRole('button', { name: /Góp ý về/ })).toBeNull()
    expect(screen.queryByText('Chờ nhóm thực hiện xác nhận')).toBeNull()
  })
})

const mentorJob = {
  id: 'j1',
  title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'AGREEMENT',
  version: 9,
  jobTypes: ['BUG_FIX'],
  selectedAt: '2026-10-18T03:00:00Z',
  codeJob: true,
  commitEmailNoticeAcknowledged: true,
}

const draft = (review: object = {}, overrides: object = {}) => ({
  revision: 4,
  state: 'AGREEMENT',
  versions: [{ versionNo: 1, sharedAt: '2026-10-20T09:20:00Z' }],
  problems: [],
  quote: { priceVnd: 800000, timelineDays: 7 },
  content,
  review: { latestVersionNo: 1, comments: [], draftMatchesShared: true, ...review },
  ...overrides,
})

const panel = (overrides: object = {}) => ({
  crewVersion: 2,
  capPercent: 40,
  largeCrews: false,
  locked: false,
  crewConfirmed: false,
  confirmedCount: 0,
  total: 1,
  mentor: { accountId: MENTOR, displayName: 'Đỗ Mạnh Hùng', githubLogin: 'hungdm', percent: 20 },
  rows: [{ rowId: 'r1', studentId: 's1', name: 'Trần Quang Minh', githubLogin: 'minhtq', status: 'ASSIGNED', confirmedLatest: false, percent: 80 }],
  latest: { versionNo: 1, status: 'PENDING', createdAt: '2026-10-20T03:00:00Z' },
  team: [],
  milestones: [],
  reconfirmOwed: false,
  ...overrides,
})

const mentorBackend = (agreement: () => Response, crew: () => Response, extra: Record<string, (request: Request) => Response | Promise<Response>> = {}) =>
  fakeBackend(signedInUser, {
    ...inEveryJob('MENTOR'),
    'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
    'GET /api/v1/mentor/jobs/j1/agreement': agreement,
    'GET /api/v1/mentor/jobs/j1/crew': crew,
    'GET /api/v1/jobs/j1/chat': () => json({ chatId: 'c1', unread: false }),
    'GET /api/v1/catalog': () => json({ categories: [] }),
    ...extra,
  })

describe('JOB-6 for the Mentor: the Customer review and the confirmation (FR-16, FR-17)', () => {
  it("shows the Customer's comments and order, and says why it cannot confirm yet", { timeout: 25_000 }, async () => {
    const user = userEvent.setup()
    mentorBackend(
      () =>
        json(
          draft({
            comments: [{ id: 'k1', versionNo: 1, targetKind: 'MILESTONE', targetId: 'm2', body: 'Giá hơi cao', createdAt: '2026-10-20T10:00:00Z' }],
            changeRequest: { versionNo: 1, note: 'Làm nhắc lịch trước', milestoneOrder: ['m2', 'm1'], createdAt: '2026-10-20T10:05:00Z' },
          }),
        ),
      () => json(panel()),
    )
    renderAt('/cong-viec/j1/thoa-thuan')

    expect(await screen.findByRole('heading', { name: 'Góp ý của khách hàng · bản nháp 1' })).toBeInTheDocument()
    expect(screen.getByText(/Giai đoạn 2 · Nhắc lịch · Khách hàng/)).toBeInTheDocument()
    expect(screen.getByText('Làm nhắc lịch trước')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Xác nhận thỏa thuận' })).toBeDisabled()
    expect(
      screen.getByText('Chưa xác nhận được: khách hàng chưa duyệt bản nháp mới nhất, và nhóm thực hiện chưa xác nhận đủ.'),
    ).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Áp dụng thứ tự' }))
    expect(screen.getAllByRole('textbox', { name: 'Tên giai đoạn' }).map((input) => (input as HTMLInputElement).value)).toEqual([
      'Nhắc lịch',
      'Sửa lỗi trùng giờ',
    ])
    expect(screen.getByRole('button', { name: 'Lưu bản nháp' })).toBeEnabled()
  })

  it('confirms at the loaded versions and shows the confirmed Agreement', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let body: unknown
    const confirmed = {
      versionNo: 1,
      draftVersionNo: 1,
      crew: ['minhtq'],
      liveAccessIds: [],
      freezeCommitted: true,
      approvedAt: '2026-10-20T11:00:00Z',
      confirmedAt: '2026-10-20T12:00:00Z',
      content,
    }
    const current = () => draft({ approvedAt: '2026-10-20T11:00:00Z' }, body ? { confirmed } : {})
    mentorBackend(
      () => json(current()),
      () => json(panel({ crewConfirmed: true, confirmedCount: 1, locked: body !== undefined })),
      {
        'POST /api/v1/mentor/jobs/j1/agreement/confirm': async (request) => {
          body = await request.json()
          return json(current())
        },
      },
    )
    renderAt('/cong-viec/j1/thoa-thuan')

    expect(await screen.findByText(/Khách hàng đã duyệt bản nháp 1 lúc/)).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: 'Xác nhận thỏa thuận' }))
    const dialog = await screen.findByRole('dialog', { name: 'Xác nhận thỏa thuận?' })
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận thỏa thuận' }))

    expect(await screen.findByText('Thỏa thuận · phiên bản 1')).toBeInTheDocument()
    expect(body).toEqual({ version: 9, crewVersion: 2 })
    expect(screen.queryByRole('button', { name: 'Lưu bản nháp' })).toBeNull()
  })

  it('reopens the Crew after a lock and re-confirms it', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let body: unknown
    mentorBackend(
      () => json(draft({ approvedAt: '2026-10-20T11:00:00Z' })),
      () =>
        json(
          panel({
            reconfirmOwed: true,
            crewConfirmed: true,
            // Story 20.4: the lock released the member's row as LEFT, so nobody is removed before re-confirming.
            rows: [
              {
                rowId: 'r1',
                studentId: 's1',
                name: 'Trần Quang Minh',
                githubLogin: 'minhtq',
                status: 'RELEASED',
                releasedReason: 'LEFT',
                confirmedLatest: false,
              },
              { rowId: 'r2', studentId: 's2', name: 'Phạm Hải Tùng', githubLogin: 'tungph', status: 'ACTIVE', confirmedLatest: true, percent: 80 },
            ],
          }),
        ),
      {
        'POST /api/v1/mentor/jobs/j1/crew/reconfirm': async (request) => {
          body = await request.json()
          return json(panel({ crewVersion: 3, locked: true, crewConfirmed: true }))
        },
      },
    )
    renderAt('/cong-viec/j1/thoa-thuan')

    expect(await screen.findByText('Cần xác nhận lại nhóm thực hiện')).toBeInTheDocument()
    expect(screen.getByText('Đã rời')).toBeInTheDocument()
    expect(screen.getByText(/Một thành viên đã rời nhóm sau khi thỏa thuận được xác nhận/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Xác nhận lại nhóm thực hiện' }))

    expect((await screen.findAllByText('Đã xác nhận lại nhóm thực hiện.')).length).toBeGreaterThan(0)
    expect(body).toEqual({ crewVersion: 2 })
    expect(await screen.findByText(/Nhóm thực hiện đã chốt theo thỏa thuận/)).toBeInTheDocument()
  })
})
