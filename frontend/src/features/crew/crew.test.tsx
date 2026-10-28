import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedInUser, inEveryJob } from '@/test/fakeBackend'
import { crewSplit } from './crewShares'

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

const draft = {
  revision: 1,
  state: 'AGREEMENT',
  review: { comments: [], draftMatchesShared: false },
  versions: [],
  problems: [],
  content: {
    scope: 'Sửa lỗi hai khách đặt trùng bàn.',
    priceVnd: 2000000,
    codeJob: true,
    assessment: false,
    skills: [],
    milestones: [
      {
        id: 'm1',
        title: 'Sửa lỗi trùng giờ đặt bàn',
        priceVnd: 2000000,
        dueOn: '2026-11-20',
        revisionRounds: 2,
        criteria: [{ id: 'c1', statement: 'Khách chọn giờ đã qua thì không đặt được', checkSteps: '1. Mở.' }],
      },
    ],
    externalAccess: [],
  },
}

const minh = (overrides: object = {}) => ({
  rowId: 'r1',
  studentId: 's-minh',
  name: 'Trần Quang Minh',
  githubLogin: 'minhtq',
  status: 'ASSIGNED',
  confirmedLatest: false,
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
  mentor: { accountId: MENTOR, displayName: 'Đỗ Mạnh Hùng', githubLogin: 'hungdm' },
  rows: [minh()],
  team: [{ studentId: 's-minh', name: 'Trần Quang Minh', githubLogin: 'minhtq', state: 'IN_CREW' }],
  milestones: [{ title: 'Sửa lỗi trùng giờ đặt bàn', priceVnd: 2000000 }],
  reconfirmOwed: false,
  ...overrides,
})

const mentorBackend = (crew: () => Response, extra: Record<string, (request: Request) => Response | Promise<Response>> = {}) =>
  fakeBackend(signedInUser, {
    ...inEveryJob('MENTOR'),
    'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
    'GET /api/v1/mentor/jobs/j1/agreement': () => json(draft),
    'GET /api/v1/mentor/jobs/j1/crew': crew,
    'GET /api/v1/jobs/j1/chat': () => json({ chatId: 'c1', unread: false }),
    'GET /api/v1/catalog': () => json({ categories: [] }),
    ...extra,
  })

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('CrewSplit in the browser (FR-75)', () => {
  it('floors each part, gives the remainder to the Mentor, and leaves out zero parts', () => {
    const parts = crewSplit(1000, new Map([[MENTOR, 0], ['a', 33], ['b', 67]]), MENTOR)
    expect([...parts.entries()]).toEqual([
      ['a', 330],
      ['b', 670],
    ])
    expect(crewSplit(1001, new Map([[MENTOR, 20], ['a', 80]]), MENTOR).get(MENTOR)).toBe(201)
  })
})

describe('JOB-6 for the Mentor: the Crew panel (FR-75, UX-DR60)', () => {
  it('adds a free Team Student; the others say why they cannot be added', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let added: unknown
    mentorBackend(
      () =>
        json(
          panel({
            crewVersion: 0,
            total: 0,
            rows: [],
            team: [
              { studentId: 's-minh', name: 'Trần Quang Minh', githubLogin: 'minhtq', state: 'FREE' },
              { studentId: 's-hai', name: 'Nguyễn Văn Hải', state: 'ON_CREW', onCrewJob: 'Viết hướng dẫn sử dụng' },
              { studentId: 's-huy', name: 'Bùi Gia Huy', state: 'NOT_VERIFIED' },
            ],
          }),
        ),
      {
        'POST /api/v1/mentor/jobs/j1/crew': async (request) => {
          added = await request.json()
          return json(panel({ crewVersion: 1 }))
        },
      },
    )
    renderAt('/cong-viec/j1/thoa-thuan')

    expect(await screen.findByText(/Khách hàng chỉ thấy tên tài khoản GitHub của nhóm thực hiện/)).toBeInTheDocument()
    expect(screen.getByText('Nhóm thực hiện: 0/0 đã xác nhận')).toBeInTheDocument()
    // The role in the Job decides the words of JOB-H (UX-DR87).
    expect(screen.getByText('Đang soạn thỏa thuận · chọn nhóm thực hiện')).toBeInTheDocument()
    expect(await screen.findByText('Bạn là: Người hướng dẫn')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Thêm thành viên' }))
    const team = screen.getByRole('list', { name: 'Thành viên trong nhóm của bạn' })
    expect(within(team).getByText('Đang làm Viết hướng dẫn sử dụng')).toBeInTheDocument()
    expect(within(team).getByText('Chưa xác minh')).toBeInTheDocument()
    expect(within(team).queryByRole('button', { name: 'Thêm Bùi Gia Huy' })).toBeNull()

    await user.click(within(team).getByRole('button', { name: 'Thêm Trần Quang Minh' }))

    expect(await screen.findByText('Đã thêm Trần Quang Minh vào nhóm thực hiện.')).toBeInTheDocument()
    expect(added).toEqual({ studentId: 's-minh', crewVersion: 0 })
    expect(await screen.findByText('Đã phân công · chưa xác nhận')).toBeInTheDocument()
    expect(screen.getByText('Chưa ký cam kết')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Thêm thành viên' })).toBeDisabled()
    expect(screen.getByText('Hiện mỗi công việc chỉ có một thành viên trong nhóm thực hiện.')).toBeInTheDocument()
  })

  it('with large Crews on, takes up to five Students before the add closes (Story 11.1)', { timeout: 20_000 }, async () => {
    const rows = (n: number) =>
      Array.from({ length: n }, (_, index) => minh({ rowId: `r${index}`, studentId: `s${index}`, name: `Thành viên ${index + 1}` }))
    let count = 2
    mentorBackend(() => json(panel({ largeCrews: true, total: count, rows: rows(count) })))
    renderAt('/cong-viec/j1/thoa-thuan')

    expect(await screen.findByText('Nhóm thực hiện: 0/2 đã xác nhận')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Thêm thành viên' })).toBeEnabled()
    cleanup()
    count = 5
    mentorBackend(() => json(panel({ largeCrews: true, total: count, rows: rows(count) })))
    renderAt('/cong-viec/j1/thoa-thuan')
    expect(await screen.findByText('Nhóm thực hiện đã đủ 5 thành viên.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Thêm thành viên' })).toBeDisabled()
  })

  it('checks the shares live, shows the example amounts, and saves a version', { timeout: 25_000 }, async () => {
    const user = userEvent.setup()
    let saved: unknown
    mentorBackend(() => json(panel()), {
      'PUT /api/v1/mentor/jobs/j1/crew/terms': async (request) => {
        saved = await request.json()
        return json(
          panel({
            crewVersion: 3,
            latest: { versionNo: 1, status: 'PENDING', createdAt: '2026-10-20T03:00:00Z' },
            mentor: { accountId: MENTOR, displayName: 'Đỗ Mạnh Hùng', githubLogin: 'hungdm', percent: 20 },
            rows: [minh({ percent: 80 })],
          }),
        )
      },
    })
    renderAt('/cong-viec/j1/thoa-thuan')

    const mentorShare = await screen.findByRole('textbox', { name: 'Phần của Đỗ Mạnh Hùng, phần trăm' })
    const minhShare = screen.getByRole('textbox', { name: 'Phần của Trần Quang Minh, phần trăm' })
    const save = screen.getByRole('button', { name: 'Lưu và gửi cho nhóm xác nhận' })
    await user.type(mentorShare, '45')
    await user.type(minhShare, '55')
    expect(screen.getByText('Tối đa 40% cho người hướng dẫn.')).toBeInTheDocument()
    expect(mentorShare).toHaveAttribute('aria-invalid', 'true')
    expect(save).toBeDisabled()

    await user.clear(mentorShare)
    await user.type(mentorShare, '20')
    await user.clear(minhShare)
    await user.type(minhShare, '0')
    expect(screen.getByText('Phần của mỗi thành viên phải lớn hơn 0%.')).toBeInTheDocument()
    expect(screen.getByText('Tổng: 20% · cần bằng 100%')).toBeInTheDocument()

    await user.clear(minhShare)
    await user.type(minhShare, '80')
    expect(screen.getByText('Tổng: 100%')).toBeInTheDocument()
    expect(screen.getByText(/Ví dụ Giai đoạn 1/).closest('p')).toHaveTextContent(/Hùng 400\.000 ₫.*Minh 1\.600\.000 ₫/)
    await user.click(save)

    expect(await screen.findByText('Đã lưu phiên bản 1 và gửi cho nhóm xác nhận.')).toBeInTheDocument()
    expect(saved).toEqual({
      crewVersion: 2,
      shares: [
        { accountId: MENTOR, percent: 20 },
        { accountId: 's-minh', percent: 80 },
      ],
    })
    expect(await screen.findByText(/Điều khoản nhóm thực hiện · phiên bản 1/)).toBeInTheDocument()
  })

  it('asks before clearing confirmations, and reloads the panel when it was stale', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let loads = 0
    mentorBackend(
      () => {
        loads += 1
        return json(
          panel({
            confirmedCount: 1,
            latest: { versionNo: 1, status: 'PENDING', createdAt: '2026-10-20T03:00:00Z' },
            mentor: { accountId: MENTOR, displayName: 'Đỗ Mạnh Hùng', githubLogin: 'hungdm', percent: 20 },
            rows: [minh({ status: 'ACTIVE', confirmedLatest: true, signedAt: '2026-10-20T04:00:00Z', percent: 80 })],
          }),
        )
      },
      { 'PUT /api/v1/mentor/jobs/j1/crew/terms': () => json({ status: 409, code: 'STALE' }, 409) },
    )
    renderAt('/cong-viec/j1/thoa-thuan')

    expect(await screen.findByText('Nhóm thực hiện: 1/1 đã xác nhận')).toBeInTheDocument()
    expect(screen.getByText('Đang tham gia · đã xác nhận phiên bản 1')).toBeInTheDocument()
    expect(screen.getByText('Đã ký cam kết 20/10')).toBeInTheDocument()
    const save = screen.getByRole('button', { name: 'Lưu và gửi cho nhóm xác nhận' })
    expect(save).toBeDisabled()

    await user.clear(screen.getByRole('textbox', { name: 'Phần của Đỗ Mạnh Hùng, phần trăm' }))
    await user.type(screen.getByRole('textbox', { name: 'Phần của Đỗ Mạnh Hùng, phần trăm' }), '30')
    await user.clear(screen.getByRole('textbox', { name: 'Phần của Trần Quang Minh, phần trăm' }))
    await user.type(screen.getByRole('textbox', { name: 'Phần của Trần Quang Minh, phần trăm' }), '70')
    await user.click(save)
    const dialog = await screen.findByRole('dialog', { name: 'Lưu phần chia mới?' })
    await user.click(within(dialog).getByRole('button', { name: 'Lưu và gửi' }))

    expect((await screen.findAllByText('Thông tin đã thay đổi trong lúc bạn xem.')).length).toBeGreaterThan(0)
    await waitFor(() => expect(loads).toBe(2))
  })

  it('is read-only once the Crew is locked by the Agreement', { timeout: 15_000 }, async () => {
    mentorBackend(() =>
      json(
        panel({
          locked: true,
          crewConfirmed: true,
          confirmedCount: 1,
          latest: { versionNo: 1, status: 'IN_FORCE', createdAt: '2026-10-20T03:00:00Z' },
          mentor: { accountId: MENTOR, displayName: 'Đỗ Mạnh Hùng', githubLogin: 'hungdm', percent: 20 },
          rows: [minh({ status: 'ACTIVE', confirmedLatest: true, signedAt: '2026-10-20T04:00:00Z', percent: 80 })],
        }),
      ),
    )
    renderAt('/cong-viec/j1/thoa-thuan')

    expect(await screen.findByText(/Nhóm thực hiện đã chốt theo thỏa thuận/)).toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: /phần trăm/ })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Thêm thành viên' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Bỏ Trần Quang Minh khỏi nhóm' })).toBeNull()
    expect(screen.getByText('80%')).toBeInTheDocument()
  })
})

const assignment = (overrides: object = {}) => ({
  jobId: 'j1',
  title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
  productName: 'Sổ đặt bàn Quán Mây',
  mentorName: 'Đỗ Mạnh Hùng',
  status: 'ASSIGNED',
  scope: 'Sửa lỗi hai khách đặt trùng bàn cùng giờ.',
  milestones: [{ title: 'Sửa lỗi trùng giờ đặt bàn', dueOn: '2026-10-30', priceVnd: 2000000, yourPartVnd: 600000 }],
  sharePercent: 30,
  versionNo: 2,
  versionSentAt: '2026-10-17T03:00:00Z',
  signed: false,
  codeJob: true,
  commitEmailNoticeAcknowledged: false,
  ...overrides,
})

/** The Job of the assignment in "Công việc" (`/my-jobs`), where the user is a Crew member. */
const crewJob = (crewKind = 'ASSIGNED') => ({
  jobId: 'j1',
  title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'AGREEMENT',
  role: 'CREW',
  crewKind,
  updatedAt: '2026-10-20T03:00:00Z',
})

describe('"Việc tôi tham gia" on CUS-7: the Crew assignment panel (FR-75, N-19, UX-DR86)', () => {
  it('signs the NDA and confirms the version shown once N-5 and N-23 are ticked', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let body: unknown
    let confirmed = false
    fakeBackend(signedInUser, {
      'GET /api/v1/my-jobs': () => json([crewJob(confirmed ? 'ACTIVE' : 'ASSIGNED')]),
      'GET /api/v1/crew/assignments': () => json([assignment(confirmed ? { status: 'ACTIVE', signed: true } : {})]),
      'POST /api/v1/crew/jobs/j1/confirm': async (request) => {
        body = await request.json()
        confirmed = true
        return new Response(null, { status: 204 })
      },
    })
    renderAt('/cong-viec')

    expect(await screen.findByRole('heading', { level: 2, name: 'Sửa lỗi đặt bàn và thêm nhắc lịch' })).toBeInTheDocument()
    expect(screen.getByText('Phần của bạn: 30% số tiền mỗi giai đoạn được nghiệm thu')).toBeInTheDocument()
    expect(screen.getByText('600.000 ₫')).toBeInTheDocument()
    expect(screen.getByText(/Bạn chỉ làm một công việc tại một thời điểm/)).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Toàn văn cam kết bảo mật' })).toBeInTheDocument()
    const confirm = screen.getByRole('button', { name: 'Ký và xác nhận' })
    expect(confirm).toBeDisabled()

    await user.click(screen.getByRole('checkbox', { name: /kho mã công khai trước khi được nghiệm thu/ }))
    expect(confirm).toBeDisabled()
    await user.click(screen.getByRole('checkbox', { name: 'Tôi đã hiểu' }))
    await user.click(confirm)

    expect(await screen.findByText('Đã ký và xác nhận phần chia.')).toBeInTheDocument()
    expect(body).toEqual({
      crewTermsVersionNo: 2,
      ndaVersion: '1.0',
      publicCodeAcknowledged: true,
      commitEmailNoticeAcknowledged: true,
    })
    expect(await screen.findByRole('heading', { level: 2, name: 'Đang tham gia' })).toBeInTheDocument()
    expect(screen.getByText('Phần của bạn: 30% · điều khoản phiên bản 2')).toBeInTheDocument()
  })

  it('declines after a confirmation and lands on the empty list', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let declined: unknown
    fakeBackend(signedInUser, {
      'GET /api/v1/my-jobs': () => json(declined ? [] : [crewJob()]),
      'GET /api/v1/crew/assignments': () => json(declined ? [] : [assignment()]),
      'POST /api/v1/crew/jobs/j1/decline': async (request) => {
        declined = await request.json()
        return new Response(null, { status: 204 })
      },
    })
    renderAt('/cong-viec')

    await user.click(await screen.findByRole('button', { name: 'Từ chối' }))
    const dialog = await screen.findByRole('dialog', { name: 'Từ chối tham gia công việc này?' })
    await user.click(within(dialog).getByRole('button', { name: 'Từ chối' }))

    expect(await screen.findByText('Bạn chưa có công việc nào.')).toBeInTheDocument()
    expect(declined).toEqual({ crewTermsVersionNo: 2 })
  })

  it('says the shares changed, and reloads when a newer version arrived meanwhile', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      'GET /api/v1/my-jobs': () => json([crewJob('ACTIVE')]),
      'GET /api/v1/crew/assignments': () => json([assignment({ signed: true, codeJob: false, versionNo: 3, sharePercent: 35 })]),
      'POST /api/v1/crew/jobs/j1/confirm': () => json({ status: 409, code: 'STALE' }, 409),
    })
    renderAt('/cong-viec')

    expect(await screen.findByText('Phần chia đã thay đổi')).toBeInTheDocument()
    expect(screen.getByText(/lên phiên bản 3/)).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Toàn văn cam kết bảo mật' })).toBeNull()
    expect(screen.queryByRole('checkbox', { name: 'Tôi đã hiểu' })).toBeNull()

    await user.click(screen.getByRole('checkbox', { name: /kho mã công khai/ }))
    await user.click(screen.getByRole('button', { name: 'Ký và xác nhận' }))

    expect((await screen.findAllByText('Phần chia đã thay đổi. Hãy xem lại phiên bản mới rồi xác nhận.')).length).toBeGreaterThan(0)
  })
})
