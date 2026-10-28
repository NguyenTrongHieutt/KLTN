import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedInUser, inEveryJob } from '@/test/fakeBackend'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

const mentorJob = {
  id: 'j1',
  title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'AGREEMENT',
  version: 3,
  jobTypes: ['BUG_FIX'],
  selectedAt: '2026-10-18T03:00:00Z',
  codeJob: true,
  commitEmailNoticeAcknowledged: true,
}

const milestone = (overrides: object = {}) => ({
  id: 'm1',
  title: 'Sửa lỗi trùng giờ đặt bàn',
  priceVnd: 800000,
  dueOn: '2026-11-20',
  revisionRounds: 2,
  criteria: [{ id: 'c1', statement: 'Khách chọn giờ đã qua thì không đặt được', checkSteps: '' }],
  ...overrides,
})

const draft = (overrides: object = {}, content: object = {}) => ({
  revision: 1,
  state: 'AGREEMENT',
  review: { comments: [], draftMatchesShared: false },
  quote: { priceVnd: 800000, timelineDays: 7 },
  versions: [],
  problems: [],
  content: {
    scope: 'Sửa lỗi hai khách đặt trùng bàn.',
    priceVnd: 800000,
    codeJob: true,
    assessment: false,
    skills: [{ id: 's1', name: 'React' }],
    milestones: [milestone()],
    externalAccess: [],
    ...content,
  },
  ...overrides,
})

const emptyCrew = {
  crewVersion: 0,
  capPercent: 40,
  largeCrews: false,
  locked: false,
  crewConfirmed: false,
  confirmedCount: 0,
  total: 0,
  mentor: { accountId: '00000000-0000-7000-8000-000000000001', displayName: 'Đỗ Mạnh Hùng', githubLogin: 'hungdm' },
  rows: [],
  team: [],
  milestones: [],
}

const mentorBackend = (agreement: object, extra: Record<string, (request: Request) => Response | Promise<Response>> = {}) =>
  fakeBackend(signedInUser, {
    ...inEveryJob('MENTOR'),
    'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
    'GET /api/v1/mentor/jobs/j1/agreement': () => json(agreement),
    'GET /api/v1/mentor/jobs/j1/crew': () => json(emptyCrew),
    'GET /api/v1/jobs/j1/chat': () => json({ chatId: 'c1', unread: false }),
    'GET /api/v1/catalog': () => json({ categories: [] }),
    ...extra,
  })

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('JOB-6 for the Mentor: draft the Agreement (FR-16)', () => {
  it('lists what is missing, then saves and shares once nothing is', { timeout: 25_000 }, async () => {
    const user = userEvent.setup()
    let saved: { milestones: { id: string; criteria: { id: string; checkSteps: string }[] }[] } | undefined
    let sharedAt: unknown
    mentorBackend(draft(), {
      'PUT /api/v1/mentor/jobs/j1/agreement': async (request) => {
        saved = await request.json()
        return json(draft({ revision: 2 }, { milestones: [milestone({ criteria: [{ id: 'c1', statement: 'Khách chọn giờ đã qua thì không đặt được', checkSteps: '1. Mở. 2. Chọn.' }] })] }))
      },
      'POST /api/v1/mentor/jobs/j1/agreement/share': async (request) => {
        sharedAt = await request.json()
        return json(draft({ revision: 2, versions: [{ versionNo: 1, sharedAt: '2026-10-20T03:00:00Z' }] }))
      },
    })
    renderAt('/cong-viec/j1/thoa-thuan')

    expect(await screen.findByRole('link', { name: 'Thỏa thuận' })).toHaveAttribute('aria-current', 'page')
    expect(await screen.findByText(/Báo giá trong đơn ứng tuyển: 800.000 ₫ · 7 ngày/)).toBeInTheDocument()
    expect(screen.getByText(/Các thay đổi về cơ sở dữ liệu và cấu hình/)).toBeInTheDocument()
    expect(screen.getByText('Quyền đối với mã')).toBeInTheDocument()
    const alert = screen.getByRole('alert')
    expect(within(alert).getByText('Tiêu chí 1 của Giai đoạn 1 chưa có nội dung hoặc cách kiểm tra.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Gửi cho khách hàng' })).toBeDisabled()

    await user.type(screen.getByRole('textbox', { name: 'Cách kiểm tra' }), '1. Mở. 2. Chọn.')
    expect(screen.queryByRole('alert')).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Gửi cho khách hàng' }))

    await waitFor(() => expect(sharedAt).toEqual({ revision: 2 }))
    expect(saved?.milestones[0].id).toBe('m1')
    expect(saved?.milestones[0].criteria[0]).toMatchObject({ id: 'c1', checkSteps: '1. Mở. 2. Chọn.' })
    expect((await screen.findAllByText('Đã gửi bản nháp 1 cho khách hàng.')).length).toBeGreaterThan(0)
  })

  it('asks for a plain-language version of a technical-only criterion', { timeout: 15_000 }, async () => {
    mentorBackend(draft({}, { milestones: [milestone({ criteria: [{ id: 'c1', statement: 'Thêm unique constraint cho bảng bookings', checkSteps: '1. Thử.' }] })] }))
    renderAt('/cong-viec/j1/thoa-thuan')

    expect(await screen.findByText(/Tiêu chí này chỉ có thuật ngữ kỹ thuật/)).toBeInTheDocument()
  })

  it('adds a Milestone with new IDs and its first criterion', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    mentorBackend(draft())
    renderAt('/cong-viec/j1/thoa-thuan')

    await user.click(await screen.findByRole('button', { name: 'Thêm giai đoạn' }))

    expect(screen.getByRole('heading', { level: 3, name: 'Giai đoạn 2' })).toBeInTheDocument()
    expect(within(screen.getByRole('alert')).getByText('Giai đoạn 2 chưa có đủ tên, giá và hạn.')).toBeInTheDocument()
  })

  it('keeps an Assessment Job\'s template and edits only the price and the due date', { timeout: 15_000 }, async () => {
    mentorBackend(
      draft({}, {
        assessment: true,
        codeJob: false,
        scope: '',
        milestones: [milestone({ title: 'Báo cáo đánh giá', criteria: [{ id: 'c1', statement: 'Báo cáo mô tả hiện trạng', checkSteps: '1. Mở trang.' }] })],
      }),
    )
    renderAt('/cong-viec/j1/thoa-thuan')

    expect(await screen.findByText(/Công việc đánh giá có một giai đoạn/)).toBeInTheDocument()
    expect(screen.getByText('Báo cáo mô tả hiện trạng')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Thêm giai đoạn' })).toBeNull()
    expect(screen.getByRole('textbox', { name: 'Giá' })).toBeEnabled()
  })
})

describe('JOB-6 for the Customer: read the shared draft (FR-16)', () => {
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
    version: 3,
    mentor: { displayName: 'Đỗ Mạnh Hùng', githubLogin: 'hungdm' },
    canCancelSelection: false,
    agreementShared: true,
  }
  const version = (versionNo: number) => ({
    versionNo,
    sharedAt: '2026-10-20T09:20:00Z',
    versions: [
      { versionNo: 1, sharedAt: '2026-10-19T03:00:00Z' },
      { versionNo: 2, sharedAt: '2026-10-20T09:20:00Z' },
    ],
    quote: { priceVnd: 800000, timelineDays: 7 },
    crew: ['minhtq'],
    crewConfirmed: false,
    latestVersionNo: 2,
    comments: [],
    changeRequested: false,
    content: {
      scope: 'Sửa lỗi hai khách đặt trùng bàn.',
      priceVnd: 800000,
      codeJob: true,
      assessment: false,
      skills: [{ id: 's1', name: 'React' }],
      milestones: [milestone({ criteria: [{ id: 'c1', statement: 'Khách chọn giờ đã qua thì không đặt được', checkSteps: '1. Mở. 2. Chọn. 3. Thấy nút mờ.' }] })],
      externalAccess: [{ id: 'a1', system: 'Supabase', purpose: 'Đọc bảng đặt bàn', environment: 'NON_PRODUCTION', accessLevel: 'READ' }],
    },
  })

  it('shows the shared version with the quote, the criteria, and the versions', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const seen = fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(job),
      'GET /api/v1/jobs/j1/chat': () => json({ chatId: 'c1', unread: false }),
      'GET /api/v1/jobs/j1/agreement': (request) =>
        json(version(new URL(request.url).searchParams.get('version') === '1' ? 1 : 2)),
    })
    renderAt('/cong-viec/j1/thoa-thuan')

    expect(await screen.findByText(/Người hướng dẫn gửi bản nháp 2 lúc/)).toBeInTheDocument()
    // The role in the Job decides the words of JOB-H (UX-DR87).
    expect(screen.getByText('Đang thống nhất thỏa thuận')).toBeInTheDocument()
    expect(await screen.findByText('Bạn là: Khách hàng')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 3, name: 'Giai đoạn 1 · Sửa lỗi trùng giờ đặt bàn' })).toBeInTheDocument()
    expect(screen.getByText('· 3 bước kiểm tra')).toBeInTheDocument()
    expect(screen.getByText('khớp báo giá')).toBeInTheDocument()
    expect(screen.getByText('Môi trường thử')).toBeInTheDocument()
    expect(screen.getByText('minhtq')).toBeInTheDocument()
    expect(screen.getByText('Chờ nhóm thực hiện xác nhận')).toBeInTheDocument()
    expect(screen.queryByText(/%/)).toBeNull()
    expect(screen.queryByRole('button', { name: 'Gửi cho khách hàng' })).toBeNull()

    await user.selectOptions(screen.getByRole('combobox', { name: 'Phiên bản' }), '1')

    await waitFor(() => expect(seen.some((request) => request.url.includes('version=1'))).toBe(true))
    expect(await screen.findByText(/Người hướng dẫn gửi bản nháp 1 lúc/)).toBeInTheDocument()
  })

  it('goes back to the overview before anything is shared', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json({ ...job, agreementShared: false }),
      'GET /api/v1/jobs/j1/chat': () => json({ chatId: 'c1', unread: false }),
      'GET /api/v1/jobs/j1/applications': () => json([]),
    })
    const router = renderAt('/cong-viec/j1/thoa-thuan')

    await waitFor(() => expect(router.state.location.pathname).toBe('/cong-viec/j1'))
  })
})
