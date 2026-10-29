import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { notificationTitle } from '@/features/notification/texts'
import { fakeBackend, heldJobs, inEveryJob, json, profileView, signedInUser } from '@/test/fakeBackend'

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
  json({
    categories: [
      {
        id: 'c1',
        name: 'Giao diện web',
        skills: [
          { id: 'react', name: 'React', hint: 'Thư viện dựng giao diện web.' },
          { id: 'supabase', name: 'Supabase', hint: 'Cơ sở dữ liệu có sẵn.' },
        ],
      },
    ],
  })

const mentorJob = (overrides: object = {}) => ({
  id: 'j1',
  title: 'Sửa lỗi đặt bàn',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'AGREEMENT',
  version: 3,
  jobTypes: ['BUG_FIX'],
  selectedAt: '2026-10-18T03:00:00Z',
  codeJob: true,
  commitEmailNoticeAcknowledged: true,
  agreementConfirmed: false,
  ...overrides,
})

const recruitment = (overrides: object = {}) => ({
  crewVersion: 0,
  state: 'AGREEMENT',
  available: true,
  recruiting: false,
  openSlots: 0,
  maxSlots: 5,
  cap: 5,
  signedMember: false,
  skills: [],
  crew: [],
  ...overrides,
})

const verified = [
  { kind: 'GITHUB', verifiedAt: '2026-09-01T03:00:00Z', githubLogin: 'minh-dev' },
  { kind: 'STUDENT' },
  { kind: 'LINKEDIN' },
]

const card = (overrides: object = {}) => ({
  id: 'a1',
  status: 'PENDING',
  note: 'Mình đã làm kiểm thử phân quyền cho đồ án.',
  skills: [{ id: 'react', name: 'React' }],
  submittedAt: '2026-10-20T03:00:00Z',
  lapsesAt: '2026-11-03T03:00:00Z',
  applicant: { displayName: 'Trần Quang Minh', githubLogin: 'minh-dev', verified, memberSince: '2026-06-01T03:00:00Z' },
  proof: [
    {
      position: 1,
      kind: 'LINK',
      caption: 'Dự án môn học của tôi',
      url: 'https://docs.example.vn/du-an',
      domain: 'docs.example.vn',
    },
  ],
  fitMetrics: {
    sharedSkills: ['React'],
    skillsSought: 2,
    jobTypeMatches: [],
    jobsAsMentor: 0,
    jobsAsCrew: 0,
    activeJobsAsMentor: 0,
    verifications: ['GITHUB'],
  },
  ...overrides,
})

type Handlers = Record<string, (request: Request) => Response | Promise<Response>>

const jobNine = (overrides: Handlers = {}) => ({
  ...inEveryJob('MENTOR'),
  'GET /api/v1/mentor/jobs/j1': () => json(mentorJob()),
  'GET /api/v1/mentor/jobs/j1/recruitment': () => json(recruitment()),
  'GET /api/v1/mentor/jobs/j1/crew-applications': () => json([]),
  'GET /api/v1/catalog': catalog,
  'GET /api/v1/reputation/histories': () => json([]),
  ...overrides,
})

const post = (overrides: object = {}) => ({
  id: 'j1',
  phase: 'FINDING_CREW',
  need: 'CREW',
  title: 'Sửa lỗi đặt bàn',
  productName: 'Sổ đặt bàn Quán Mây',
  productDescription: 'Đặt bàn cho quán.',
  productTools: ['LOVABLE'],
  productOpenFindings: 0,
  problem: 'Đơn đặt bàn bị trùng.',
  jobTypes: ['BUG_FIX'],
  skills: [],
  budget: 'FROM_2M_TO_5M',
  deadline: '2026-11-10',
  openedAt: '2026-10-15T03:00:00Z',
  screenshots: [],
  mentor: { displayName: 'Đỗ Mạnh Hùng', githubLogin: 'hung-mentor' },
  recruitment: { openSlots: 2, skills: ['React', 'Supabase'], note: 'Cần một bạn kiểm thử phân quyền' },
  ...overrides,
})

const crewApplication = (overrides: object = {}) => ({
  id: 'a1',
  jobId: 'j1',
  jobTitle: 'Sửa lỗi đặt bàn',
  status: 'PENDING',
  note: 'Mình đã làm kiểm thử phân quyền cho đồ án.',
  skills: [{ id: 'react', name: 'React' }],
  submittedAt: '2026-10-20T03:00:00Z',
  lapsesAt: '2026-11-03T03:00:00Z',
  proof: [],
  ...overrides,
})

const menThree = (overrides: Handlers = {}) => ({
  'GET /api/v1/mentor/job-posts/j1': () => json(post()),
  'GET /api/v1/mentor/job-posts/j1/customer-rating': () => json({ count: 0 }),
  'GET /api/v1/mentor/job-posts/j1/crew-application-files': () => json([]),
  'GET /api/v1/catalog': catalog,
  ...overrides,
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('JOB-9, the Mentor recruits the Crew (UX-DR102, Story 21.4)', () => {
  it('turns recruitment on with two slots, the Skills sought and a note, under N-20 and R7', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let sent: Record<string, unknown> | undefined
    fakeBackend(
      signedInUser,
      jobNine({
        'GET /api/v1/mentor/jobs/j1/recruitment': () =>
          json(sent ? recruitment({ recruiting: true, openSlots: 2, crewVersion: 1 }) : recruitment()),
        'PUT /api/v1/mentor/jobs/j1/recruitment': async (request) => {
          sent = (await request.json()) as Record<string, unknown>
          return json(recruitment({ recruiting: true, openSlots: 2, crewVersion: 1 }))
        },
      }),
    )
    renderAt('/cong-viec/j1/tuyen-thanh-vien')

    expect(await screen.findByRole('heading', { level: 1, name: 'Tuyển thành viên' })).toBeInTheDocument()
    expect(screen.getByText(/Công việc chỉ bắt đầu khi có ít nhất một thành viên đã ký/)).toBeInTheDocument()
    expect(screen.getByText(/Khách hàng không thấy đơn xin vào nhóm, hồ sơ hay phần chia tiền/)).toBeInTheDocument()
    expect(screen.getByText('Đang không nhận đơn xin vào nhóm.')).toBeInTheDocument()
    await user.click(screen.getByRole('switch', { name: 'Nhận đơn xin vào nhóm' }))
    const slots = screen.getByLabelText('Cần thêm (người)')
    await user.clear(slots)
    await user.type(slots, '2')
    await user.type(screen.getByLabelText('Lời nhắn cho người xin vào nhóm'), 'Cần một bạn kiểm thử phân quyền')
    await user.click(screen.getByRole('button', { name: 'Lưu' }))

    await waitFor(() => expect(sent).toBeDefined())
    expect(sent).toMatchObject({ on: true, openSlots: 2, note: 'Cần một bạn kiểm thử phân quyền', crewVersion: 0 })
    expect(await screen.findByText('Đang nhận đơn · cần thêm 2 người')).toBeInTheDocument()
  })

  it('refuses more slots than the free places under the cap', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(
      signedInUser,
      jobNine({
        'GET /api/v1/mentor/jobs/j1/recruitment': () => json(recruitment({ maxSlots: 1, signedMember: true })),
        'PUT /api/v1/mentor/jobs/j1/recruitment': () =>
          json({ status: 400, code: 'VALIDATION', reason: 'SLOTS_INVALID', max: 1 }, 400),
      }),
    )
    renderAt('/cong-viec/j1/tuyen-thanh-vien')

    await user.click(await screen.findByRole('switch', { name: 'Nhận đơn xin vào nhóm' }))
    const slots = screen.getByLabelText('Cần thêm (người)')
    await user.clear(slots)
    await user.type(slots, '2')
    await user.click(screen.getByRole('button', { name: 'Lưu' }))

    expect(await screen.findByText('Số người cần thêm phải từ 1 đến 1.')).toBeInTheDocument()
    expect(screen.queryByText(/Công việc chỉ bắt đầu khi có ít nhất một thành viên đã ký/)).toBeNull()
  })

  it('shows the Customer and everyone else the not-found screen', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/mentor/jobs/j1': () => json({ status: 404, code: 'NOT_FOUND' }, 404),
    })
    renderAt('/cong-viec/j1/tuyen-thanh-vien')

    expect(await screen.findByRole('heading', { name: 'Không tìm thấy nội dung này.' })).toBeInTheDocument()
    expect(screen.queryByRole('switch')).toBeNull()
  })

  it('asks before turning recruitment off, since the open applications lapse', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let sent: Record<string, unknown> | undefined
    fakeBackend(
      signedInUser,
      jobNine({
        'GET /api/v1/mentor/jobs/j1/recruitment': () => json(recruitment({ recruiting: true, openSlots: 2, crewVersion: 4 })),
        'PUT /api/v1/mentor/jobs/j1/recruitment': async (request) => {
          sent = (await request.json()) as Record<string, unknown>
          return json(recruitment({ crewVersion: 5 }))
        },
      }),
    )
    renderAt('/cong-viec/j1/tuyen-thanh-vien')

    await user.click(await screen.findByRole('switch', { name: 'Nhận đơn xin vào nhóm' }))
    await user.click(screen.getByRole('button', { name: 'Lưu' }))
    const dialog = await screen.findByRole('dialog', { name: 'Ngừng nhận đơn xin vào nhóm?' })
    expect(within(dialog).getByText(/Các đơn đang chờ sẽ hết hạn/)).toBeInTheDocument()
    expect(sent).toBeUndefined()
    await user.click(within(dialog).getByRole('button', { name: 'Ngừng nhận đơn' }))

    await waitFor(() => expect(sent).toMatchObject({ on: false, crewVersion: 4 }))
  })

  it('lists the Applicant cards in order and declines one without a reason', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let declined = false
    fakeBackend(
      signedInUser,
      jobNine({
        'GET /api/v1/mentor/jobs/j1/recruitment': () =>
          json(
            recruitment({
              recruiting: true,
              openSlots: 1,
              crew: [
                { rowId: 'r1', name: 'Phạm Thu Hà', status: 'ACTIVE', joinedAt: '2026-10-19T03:00:00Z' },
                { rowId: 'r2', name: 'Lê Văn Tài', status: 'RELEASED', joinedAt: '2026-10-10T03:00:00Z', leftAt: '2026-10-15T03:00:00Z' },
              ],
            }),
          ),
        'GET /api/v1/mentor/jobs/j1/crew-applications': () =>
          json(declined ? [card({ status: 'DECLINED', endReason: 'DECLINED', proof: [], fitMetrics: undefined })] : [card()]),
        'POST /api/v1/mentor/jobs/j1/crew-applications/a1/decline': () => {
          declined = true
          return new Response(null, { status: 204 })
        },
      }),
    )
    renderAt('/cong-viec/j1/tuyen-thanh-vien')

    const item = await screen.findByRole('listitem', { name: 'Trần Quang Minh' })
    expect(within(item).getByText('Mình đã làm kiểm thử phân quyền cho đồ án.')).toBeInTheDocument()
    expect(within(item).getByText('Dự án môn học của tôi')).toBeInTheDocument()
    expect(within(item).getByRole('button', { name: 'Xem hồ sơ' })).toBeInTheDocument()
    expect(within(item).queryByRole('button', { name: 'Chọn' })).toBeNull()
    expect(screen.getByText('Đang tham gia · từ 19/10')).toBeInTheDocument()
    expect(screen.getByText('Đã rời 15/10')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Bỏ|Xóa/ })).toBeNull()

    await user.click(within(item).getByRole('button', { name: 'Không chọn' }))
    const dialog = await screen.findByRole('dialog', { name: 'Không chọn Trần Quang Minh?' })
    expect(within(dialog).getByText(/chỉ được báo là chưa được chọn, không kèm lý do/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Không chọn' }))

    expect(await screen.findByRole('heading', { name: 'Đơn đã đóng' })).toBeInTheDocument()
    expect(declined).toBe(true)
  })
})

describe('MEN-3 and FND-2, applying to a Crew (UX-DR98, UX-DR103, Story 21.4)', () => {
  it('applies from FND-2 with a note, the profile Skills, a link and N-5', { timeout: 25_000 }, async () => {
    const user = userEvent.setup()
    let sent: Record<string, unknown> | undefined
    fakeBackend(signedInUser, {
      ...menThree({
        'GET /api/v1/profile': () =>
          json(profileView({ displayName: 'Minh', skills: [{ id: 'react', name: 'React', active: true }] })),
        'POST /api/v1/mentor/job-posts/j1/crew-applications': async (request) => {
          sent = (await request.json()) as Record<string, unknown>
          return json(crewApplication(), 201)
        },
      }),
    })
    const router = renderAt('/tim-viec/j1')

    const area = await screen.findByRole('region', { name: 'Xin vào nhóm' })
    const link = await within(area).findByRole('link', { name: 'Xin vào nhóm' })
    expect(link).toHaveAttribute('href', '/tim-viec/j1/xin-vao-nhom')
    await router.navigate('/tim-viec/j1/xin-vao-nhom')

    expect(await screen.findByText('Người hướng dẫn cần: React, Supabase')).toBeInTheDocument()
    expect(screen.getByText(/chỉ người hướng dẫn của công việc này xem được/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/Giá/)).toBeNull()
    expect(screen.getByRole('button', { name: 'Bỏ React' })).toBeInTheDocument()
    await user.type(screen.getByLabelText('Lời nhắn cho người hướng dẫn'), 'Mình đã làm kiểm thử phân quyền.')
    await user.type(screen.getByLabelText('Liên kết'), 'https://docs.example.vn/du-an')
    await user.type(screen.getByLabelText('Mô tả liên kết'), 'Dự án môn học')
    await user.click(screen.getByRole('button', { name: 'Thêm liên kết' }))
    await user.click(screen.getByRole('button', { name: 'Gửi đơn' }))
    expect(await screen.findByText('Hãy đánh dấu ô xác nhận trước khi gửi đơn.')).toBeInTheDocument()
    expect(sent).toBeUndefined()
    await user.click(screen.getByRole('checkbox', { name: /Tôi hiểu mã tôi và nhóm làm/ }))
    await user.click(screen.getByRole('button', { name: 'Gửi đơn' }))

    await waitFor(() => expect(sent).toBeDefined())
    expect(sent).toMatchObject({
      note: 'Mình đã làm kiểm thử phân quyền.',
      skillIds: ['react'],
      publicCodeAcknowledged: true,
      proof: [{ kind: 'LINK', url: 'https://docs.example.vn/du-an', caption: 'Dự án môn học' }],
    })
    await waitFor(() => expect(router.state.location.pathname).toBe('/tim-viec/j1'))
  })

  it('gives the Customer and the Mentor their role in place of the form', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, { ...menThree(), ...inEveryJob('CUSTOMER') })
    renderAt('/tim-viec/j1/xin-vao-nhom')
    expect(await screen.findByText('Đây là công việc bạn đăng.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Gửi đơn' })).toBeNull()
  })

  it('tells a member who left the Job that they cannot rejoin', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...menThree(),
      ...inEveryJob('CREW', 'FORMER'),
      ...heldJobs({ jobId: 'j1', title: 'Sửa lỗi đặt bàn', role: 'CREW', crewKind: 'FORMER', state: 'ACTIVE' }),
    })
    renderAt('/tim-viec/j1')

    expect(await screen.findByText('Bạn đã rời công việc này nên không xin vào lại được.')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Xin vào nhóm' })).toBeNull()
  })

  it('tells a member of another running Crew where they are, on MEN-3 and FND-2', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...menThree(),
      ...heldJobs({ jobId: 'j7', title: 'Sửa lỗi thanh toán', role: 'CREW', crewKind: 'ACTIVE' }),
    })
    renderAt('/tim-viec/j1/xin-vao-nhom')

    expect(
      await screen.findByText(
        'Bạn đang tham gia Sửa lỗi thanh toán. Với vai trò thành viên nhóm, mỗi lúc bạn chỉ tham gia một công việc đang chạy.',
      ),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Gửi đơn' })).toBeNull()
  })

  it('says a closed or full recruitment and the daily limit inline', { timeout: 25_000 }, async () => {
    const user = userEvent.setup()
    const answers = [
      () => json({ status: 422, code: 'GUARD_FAILED', reason: 'RECRUITMENT_CLOSED' }, 422),
      () => json({ status: 422, code: 'GUARD_FAILED', reason: 'CREW_FULL', n: 1 }, 422),
      () =>
        new Response(JSON.stringify({ status: 429, code: 'RATE_LIMITED' }), {
          status: 429,
          headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '3600' },
        }),
    ]
    let calls = 0
    fakeBackend(signedInUser, {
      ...menThree({ 'POST /api/v1/mentor/job-posts/j1/crew-applications': () => answers[calls++]() }),
    })
    renderAt('/tim-viec/j1/xin-vao-nhom')

    await user.type(await screen.findByLabelText('Lời nhắn cho người hướng dẫn'), 'Mình muốn tham gia.')
    await user.click(screen.getByRole('checkbox', { name: /Tôi hiểu mã tôi và nhóm làm/ }))
    await user.click(screen.getByRole('button', { name: 'Gửi đơn' }))
    expect(await screen.findByText('Người hướng dẫn đang không nhận đơn xin vào nhóm.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Gửi đơn' }))
    expect(await screen.findByText('Nhóm thực hiện đã đủ 1 người.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Gửi đơn' }))
    expect(await screen.findByText(/Hôm nay bạn đã gửi đủ 10 đơn xin vào nhóm/)).toBeInTheDocument()
  })

  it('shows the sent application with its lapse date and withdraws it after a confirmation', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let withdrawn = false
    fakeBackend(signedInUser, {
      ...menThree({
        'GET /api/v1/mentor/crew-applications': () =>
          json([withdrawn ? crewApplication({ status: 'WITHDRAWN', endReason: 'WITHDRAWN' }) : crewApplication()]),
        'POST /api/v1/mentor/crew-applications/a1/withdraw': () => {
          withdrawn = true
          return json(crewApplication({ status: 'WITHDRAWN', endReason: 'WITHDRAWN' }))
        },
      }),
    })
    renderAt('/tim-viec/j1')

    expect(await screen.findByText('Đã gửi · chờ người hướng dẫn xem · hết hạn ngày 03/11')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Xem' })).toHaveAttribute('href', '/tim-viec/j1/xin-vao-nhom')
    await user.click(screen.getByRole('button', { name: 'Rút' }))
    const dialog = await screen.findByRole('dialog', { name: 'Rút đơn xin vào nhóm này?' })
    await user.click(within(dialog).getByRole('button', { name: 'Rút' }))

    expect(await screen.findByText('Bạn đã rút đơn này')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Gửi đơn mới' })).toBeInTheDocument()
  })

  it('tells the applicant why an application lapsed once the Job is no longer listed', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/mentor/job-posts/j1': () => json({ status: 404, code: 'NOT_FOUND' }, 404),
      'GET /api/v1/mentor/crew-applications': () =>
        json([crewApplication({ status: 'LAPSED', endReason: 'RECRUITMENT_CLOSED', endedAt: '2026-10-25T03:00:00Z' })]),
    })
    renderAt('/tim-viec/j1')

    expect(
      await screen.findByText('Đơn đã hết hạn: người hướng dẫn đã ngừng nhận đơn xin vào nhóm'),
    ).toBeInTheDocument()
  })

  it('shows "not picked" with the way to apply again while the Job still recruits', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...menThree({
        'GET /api/v1/mentor/crew-applications': () => json([crewApplication({ status: 'DECLINED', endReason: 'DECLINED' })]),
      }),
    })
    renderAt('/tim-viec/j1')

    expect(await screen.findByText('Chưa được chọn')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Gửi đơn mới' })).toHaveAttribute('href', '/tim-viec/j1/xin-vao-nhom')
  })
})

describe('FND-2 proof files and the read-only application (UX-DR103, Story 21.4)', () => {
  it('uploads a file, captions it, lists a kept upload, removes one with "Bỏ", and sends the file', { timeout: 25_000 }, async () => {
    const user = userEvent.setup()
    let sent: Record<string, unknown> | undefined
    let removed = false
    const kept = { fileId: 'f0', fileName: 'cu.pdf', sizeBytes: 1024, addedAt: '2026-10-19T03:00:00Z', fileUrl: '/api/v1/files/f0' }
    fakeBackend(signedInUser, {
      ...menThree({
        'GET /api/v1/mentor/job-posts/j1/crew-application-files': () => json(removed ? [] : [kept]),
        'POST /api/v1/mentor/job-posts/j1/crew-application-files': () =>
          json(
            { fileId: 'f1', fileName: 'bao-cao.pdf', sizeBytes: 2048, addedAt: '2026-10-20T03:00:00Z', fileUrl: '/api/v1/files/f1' },
            201,
          ),
        'DELETE /api/v1/mentor/job-posts/j1/crew-application-files/f0': () => {
          removed = true
          return new Response(null, { status: 204 })
        },
        'POST /api/v1/mentor/job-posts/j1/crew-applications': async (request) => {
          sent = (await request.json()) as Record<string, unknown>
          return json(crewApplication(), 201)
        },
      }),
    })
    renderAt('/tim-viec/j1/xin-vao-nhom')

    expect(await screen.findByText(/cu\.pdf/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Bỏ cu.pdf' }))
    await waitFor(() => expect(screen.queryByText(/cu\.pdf/)).toBeNull())
    const input = document.querySelector('input[type="file"]') as HTMLInputElement
    await user.upload(input, new File(['%PDF-1.4'], 'bao-cao.pdf', { type: 'application/pdf' }))
    await user.type(await screen.findByLabelText('Mô tả cho bao-cao.pdf'), 'Báo cáo kiểm thử')
    await user.type(screen.getByLabelText('Lời nhắn cho người hướng dẫn'), 'Mình muốn tham gia.')
    await user.click(screen.getByRole('checkbox', { name: /Tôi hiểu mã tôi và nhóm làm/ }))
    await user.click(screen.getByRole('button', { name: 'Gửi đơn' }))

    await waitFor(() => expect(sent).toBeDefined())
    expect(removed).toBe(true)
    expect(sent).toMatchObject({ proof: [{ kind: 'FILE', fileId: 'f1', caption: 'Báo cáo kiểm thử' }] })
  })

  it('words an upload refusal as the application does', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...menThree({
        'POST /api/v1/mentor/job-posts/j1/crew-application-files': () =>
          json({ status: 422, code: 'GUARD_FAILED', reason: 'CREW_FULL', n: 1 }, 422),
      }),
    })
    renderAt('/tim-viec/j1/xin-vao-nhom')

    await screen.findByLabelText('Lời nhắn cho người hướng dẫn')
    const input = document.querySelector('input[type="file"]') as HTMLInputElement
    await user.upload(input, new File(['%PDF-1.4'], 'bao-cao.pdf', { type: 'application/pdf' }))

    expect(await screen.findByText('Nhóm thực hiện đã đủ 1 người.')).toBeInTheDocument()
  })

  it('shows a pending application read-only with its state and "Rút"', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...menThree({ 'GET /api/v1/mentor/crew-applications': () => json([crewApplication()]) }),
    })
    renderAt('/tim-viec/j1/xin-vao-nhom')

    expect(await screen.findByRole('heading', { name: 'Đơn xin vào nhóm của bạn' })).toBeInTheDocument()
    expect(screen.getByText('Đã gửi · chờ người hướng dẫn xem · hết hạn ngày 03/11')).toBeInTheDocument()
    expect(screen.getByText('Mình đã làm kiểm thử phân quyền cho đồ án.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Rút' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Gửi đơn' })).toBeNull()
  })

  it('offers "Rút" on MEN-3 for a pending application whose Job left the listing', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/mentor/job-posts/j1': () => json({ status: 404, code: 'NOT_FOUND' }, 404),
      'GET /api/v1/mentor/crew-applications': () => json([crewApplication()]),
    })
    renderAt('/tim-viec/j1')

    expect(await screen.findByText('Đã gửi · chờ người hướng dẫn xem · hết hạn ngày 03/11')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Rút' })).toBeInTheDocument()
  })
})

describe('the Mentor tab "Tuyển thành viên" (UX-DR102, Story 21.4)', () => {
  it('shows for an Active Job and not for one in Closing', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, jobNine({ 'GET /api/v1/mentor/jobs/j1': () => json(mentorJob({ state: 'ACTIVE' })) }))
    renderAt('/cong-viec/j1/tuyen-thanh-vien')
    expect(await screen.findByRole('link', { name: 'Tuyển thành viên' })).toHaveAttribute(
      'href',
      '/cong-viec/j1/tuyen-thanh-vien',
    )
  })

  it('has no tab once the Job is Closing', { timeout: 20_000 }, async () => {
    fakeBackend(
      signedInUser,
      jobNine({
        'GET /api/v1/mentor/jobs/j1': () => json(mentorJob({ state: 'CLOSING' })),
        'GET /api/v1/mentor/jobs/j1/recruitment': () => json(recruitment({ state: 'CLOSING', available: false })),
      }),
    )
    renderAt('/cong-viec/j1/tuyen-thanh-vien')
    expect(await screen.findByText('Công việc đã qua giai đoạn nhận thành viên nên không tuyển thêm được.')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Tuyển thành viên' })).toBeNull()
  })
})

describe('CUS-7 "Việc tôi tham gia" counts the pending Crew Applications (UX-DR86, Story 21.4)', () => {
  it('lists the application with its state and counts it in its segment', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/mentor/crew-applications': () => json([crewApplication()]),
      'GET /api/v1/crew/assignments': () => json([]),
      'GET /api/v1/team-issues': () => json([]),
    })
    renderAt('/cong-viec')

    expect(await screen.findByRole('heading', { name: /Đơn xin vào nhóm/ })).toBeInTheDocument()
    expect(screen.getByText('Đã gửi · chờ người hướng dẫn xem · hết hạn ngày 03/11')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Sửa lỗi đặt bàn/ })).toHaveAttribute('href', '/tim-viec/j1')
  })

  it('counts a pending application beside the Jobs the user mentors', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...heldJobs({ jobId: 'j5', title: 'Việc hướng dẫn', role: 'MENTOR' }),
      'GET /api/v1/mentor/crew-applications': () => json([crewApplication()]),
      'GET /api/v1/crew/assignments': () => json([]),
      'GET /api/v1/team-issues': () => json([]),
    })
    renderAt('/cong-viec')

    const segment = await screen.findByRole('button', { name: /Việc tôi tham gia/ })
    expect(within(segment).getByText('1 việc cần làm')).toBeInTheDocument()
  })
})

describe('the lapsed notice says why (R6, Story 21.4)', () => {
  it('words each cause the server names', () => {
    const title = (cause: string) =>
      notificationTitle({ type: 'matching.crew_application_lapsed', params: { job: 'Sửa lỗi đặt bàn', cause } })

    expect(title('EXPIRED')).toBe('Đơn xin vào nhóm Sửa lỗi đặt bàn đã hết hạn: người hướng dẫn chưa chọn trong thời hạn')
    expect(title('RECRUITMENT_CLOSED')).toBe(
      'Đơn xin vào nhóm Sửa lỗi đặt bàn đã hết hạn: người hướng dẫn đã ngừng nhận đơn xin vào nhóm',
    )
    expect(title('JOB_CLOSING')).toBe('Đơn xin vào nhóm Sửa lỗi đặt bàn đã hết hạn: công việc đã chuyển sang giai đoạn kết thúc')
    expect(title('JOB_CANCELLED')).toBe('Đơn xin vào nhóm Sửa lỗi đặt bàn đã hết hạn: công việc đã bị hủy')
  })
})
