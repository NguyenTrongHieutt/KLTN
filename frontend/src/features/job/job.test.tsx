import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedInUser, signedInWithGoogle, inEveryJob } from '@/test/fakeBackend'
import { PAYLOADS } from '@/test/payloads'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

const product = (openFindings = 0) => ({
  id: 'p1',
  name: 'Sổ đặt bàn Quán Mây',
  description: 'Đặt bàn cho quán.',
  tools: ['LOVABLE'],
  repositoryFullName: 'lan/so-dat-ban',
  repositoryUrl: 'https://github.com/lan/so-dat-ban',
  repositoryOwnerType: 'User',
  createdAt: '2026-10-16T03:00:00Z',
  scanState: openFindings > 0 ? 'FINDINGS' : 'CLEAN',
  scan: { id: 's1', purpose: 'INTAKE', status: 'SUCCEEDED', requestedAt: '2026-10-16T03:00:00Z', unscannedCount: 0, unscanned: [] },
  openFindings,
  jobs: 0,
})

const catalog = () =>
  json({
    categories: [
      {
        id: 'c1',
        name: 'Giao diện web',
        skills: [
          { id: 'react', name: 'React', hint: 'Thư viện dựng giao diện web.' },
          { id: 'supabase', name: 'Supabase', hint: 'Dịch vụ lưu dữ liệu và đăng nhập.' },
          { id: 'vnpay', name: 'VNPay', hint: 'Cổng thanh toán online.' },
        ],
      },
    ],
  })

const draft = {
  id: 'j1',
  productId: 'p1',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'DRAFT',
  title: 'Thêm thanh toán online khi đặt bàn',
  problem: 'Khách đặt rồi không đến.',
  jobTypes: ['NEW_FEATURE'],
  skills: [{ id: 'react', name: 'React', active: true }],
  budget: 'FROM_2M_TO_5M',
  deadline: '2026-11-15',
  screenshots: [],
  createdAt: '2026-10-16T03:00:00Z',
  updatedAt: '2026-10-16T03:00:00Z',
}

afterEach(() => {
  vi.unstubAllGlobals()
})

/** One Job of "Công việc" (`/my-jobs`) where the user holds `role`. */
function held(jobId: string, title: string, role: 'CUSTOMER' | 'MENTOR' | 'CREW', extra: Record<string, unknown> = {}) {
  return { jobId, title, productName: 'Sổ đặt bàn Quán Mây', state: 'ACTIVE', role, updatedAt: '2026-10-20T03:00:00Z', ...extra }
}

describe('CUS-7, the Jobs of every role (UX-DR66, UX-DR86)', () => {
  it('shows the Empty state leading to "Tìm việc" to a user with no Job', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser)
    renderAt('/cong-viec')

    expect(await screen.findByText('Bạn chưa có công việc nào.')).toBeInTheDocument()
    expect(within(screen.getByRole('main')).getByRole('link', { name: 'Tìm việc' })).toHaveAttribute('href', '/tim-viec')
    expect(screen.queryByRole('group', { name: 'Vai trò của bạn trong công việc' })).not.toBeInTheDocument()
  })

  it('groups the Jobs and links a Draft to its form', async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/my-jobs': () => json([held('j1', draft.title, 'CUSTOMER', { state: 'DRAFT' })]),
      'GET /api/v1/jobs': () =>
        json([{ id: 'j1', productId: 'p1', productName: 'Sổ đặt bàn Quán Mây', state: 'DRAFT', title: draft.title, jobTypes: ['NEW_FEATURE'], updatedAt: '2026-10-16T03:00:00Z' }]),
    })
    renderAt('/cong-viec')

    const drafts = await screen.findByRole('region', { name: /Bản nháp/ })
    expect(within(drafts).getByRole('link', { name: /Thêm thanh toán online khi đặt bàn/ })).toHaveAttribute('href', '/cong-viec/j1/sua')
    expect(within(drafts).getByText(/Sổ đặt bàn Quán Mây · Tính năng mới · sửa lần cuối 16\/10/)).toBeInTheDocument()
    for (const heading of ['Cần bạn quyết định', 'Đang thực hiện', 'Đang tìm người hướng dẫn', 'Đã kết thúc']) {
      expect(screen.getByRole('heading', { level: 2, name: new RegExp(heading) })).toBeInTheDocument()
    }
    // One segment left: no Toggle Group.
    expect(screen.queryByRole('group', { name: 'Vai trò của bạn trong công việc' })).not.toBeInTheDocument()
  })

  it('shows two segments to the Customer of one Job and the Mentor of another, with their to-do counts', async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      'GET /api/v1/my-jobs': () =>
        json([held('a', 'Việc A của tôi', 'CUSTOMER'), held('b', 'Việc B tôi hướng dẫn', 'MENTOR')]),
      'GET /api/v1/jobs': () =>
        json([{ id: 'a', productId: 'p1', productName: 'Sổ đặt bàn Quán Mây', state: 'ACTIVE', title: 'Việc A của tôi', jobTypes: [], updatedAt: '2026-10-20T03:00:00Z' }]),
      'GET /api/v1/todos': () =>
        json({
          items: [
            { id: 't1', key: 'job.draft_agreement', tier: 2, params: { job: 'Việc B tôi hướng dẫn' }, link: '/cong-viec/b/thoa-thuan', deadlineNear: false, jobId: 'b', role: 'MENTOR', openedAt: '2026-10-25T00:00:00Z' },
            { id: 't2', key: 'job.draft_agreement', tier: 3, params: { job: 'Việc B tôi hướng dẫn' }, link: '/cong-viec/b/thoa-thuan', deadlineNear: false, jobId: 'b', role: 'MENTOR', openedAt: '2026-10-25T00:00:00Z' },
          ],
          total: 2,
        }),
    })
    const router = renderAt('/cong-viec')

    const segments = await screen.findByRole('group', { name: 'Vai trò của bạn trong công việc' })
    expect(within(segments).getAllByRole('button').map((button) => button.textContent)).toEqual([
      'Việc tôi đăng',
      'Việc tôi hướng dẫn2 việc cần làm2',
    ])
    // The most urgent to-do is the Mentor's, so "Việc tôi hướng dẫn" opens.
    expect(within(segments).getByRole('button', { name: /Việc tôi hướng dẫn/ })).toHaveAttribute('aria-pressed', 'true')
    expect(await screen.findByRole('link', { name: /Việc B tôi hướng dẫn/ })).toHaveAttribute('href', '/cong-viec/b')
    expect(screen.queryByRole('link', { name: /Việc A của tôi/ })).not.toBeInTheDocument()

    await user.click(within(segments).getByRole('button', { name: 'Việc tôi đăng' }))
    expect(await screen.findByRole('link', { name: /Việc A của tôi/ })).toHaveAttribute('href', '/cong-viec/a')
    expect(router.state.location.search).toBe('?vai-tro=dang')
  })

  it('opens the segment the URL keeps', async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/my-jobs': () => json([held('a', 'Việc A của tôi', 'CUSTOMER'), held('c', 'Việc C tôi tham gia', 'CREW', { crewKind: 'ACTIVE' })]),
      'GET /api/v1/crew/assignments': () => json([]),
    })
    renderAt('/cong-viec?vai-tro=tham-gia')

    const segments = await screen.findByRole('group', { name: 'Vai trò của bạn trong công việc' })
    expect(within(segments).getByRole('button', { name: 'Việc tôi tham gia' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('counts and lists pending bids in "Việc tôi hướng dẫn" before any Job is mentored', async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/mentor/applications': () =>
        json([{ id: 'ap1', jobId: 'j9', jobTitle: 'Sửa lỗi giỏ hàng', status: 'SUBMITTED', priceVnd: 1500000, timelineDays: 7, submittedAt: '2026-10-20T03:00:00Z' }]),
    })
    renderAt('/cong-viec')

    expect(await screen.findByRole('link', { name: /Sửa lỗi giỏ hàng/ })).toHaveAttribute('href', '/tim-viec/j9')
    expect(screen.queryByRole('group', { name: 'Vai trò của bạn trong công việc' })).not.toBeInTheDocument()
  })

  it.each([
    ['a closed bid shows the segment with the bid and no count', 'CLOSED', null],
    ['a bid on hold counts in the segment', 'ON_HOLD', '1 việc cần làm'],
  ])('%s', async (_, status, count) => {
    fakeBackend(signedInUser, {
      'GET /api/v1/my-jobs': () => json([held('a', 'Việc A của tôi', 'CUSTOMER')]),
      'GET /api/v1/mentor/applications': () =>
        json([{ id: 'ap1', jobId: 'j9', jobTitle: 'Sửa lỗi giỏ hàng', status, priceVnd: 1500000, timelineDays: 7, submittedAt: '2026-10-20T03:00:00Z' }]),
    })
    renderAt('/cong-viec?vai-tro=huong-dan')

    const segments = await screen.findByRole('group', { name: 'Vai trò của bạn trong công việc' })
    const mentoring = within(segments).getByRole('button', { name: /Việc tôi hướng dẫn/ })
    expect(mentoring).toHaveAttribute('aria-pressed', 'true')
    if (count === null) {
      expect(mentoring).toHaveAccessibleName('Việc tôi hướng dẫn')
    } else {
      expect(mentoring).toHaveAccessibleName(`Việc tôi hướng dẫn${count}`)
    }
    expect(await screen.findByRole('link', { name: /Sửa lỗi giỏ hàng/ })).toHaveAttribute('href', '/tim-viec/j9')
  })

  it('shows only the Crew list, with no Toggle Group, and marks a Job the user left', async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/my-jobs': () => json([held('c', 'Việc C đã rời', 'CREW', { crewKind: 'FORMER', state: 'ACTIVE' })]),
      'GET /api/v1/crew/assignments': () => json([]),
    })
    renderAt('/cong-viec')

    const past = await screen.findByRole('region', { name: 'Công việc đã qua' })
    expect(within(past).getByRole('link', { name: /Việc C đã rời · đã rời/ })).toHaveAttribute('href', '/cong-viec/c')
    expect(screen.queryByRole('group', { name: 'Vai trò của bạn trong công việc' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Đăng công việc/ })).toBeNull()
  })

  it('lists a member of a Job that ended among the past Jobs, without "· đã rời"', async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/my-jobs': () => json([held('d', 'Việc D đã xong', 'CREW', { crewKind: 'FORMER', state: 'CLOSED' })]),
      'GET /api/v1/crew/assignments': () => json([]),
    })
    renderAt('/cong-viec')

    const past = await screen.findByRole('region', { name: 'Công việc đã qua' })
    const row = within(past).getByRole('link', { name: /Việc D đã xong/ })
    expect(row).toHaveAttribute('href', '/cong-viec/d')
    expect(row).not.toHaveTextContent('đã rời')
  })
})

describe('CUS-6, drafting a Job (FR-9)', () => {
  it('asks the guided questions with examples, pre-selects the suggested Skills, and saves a Draft', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let saved: Record<string, unknown> | undefined
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/products/p1': () => json(product()),
      'GET /api/v1/products/p1/skill-suggestions': () =>
        json([
          { id: 'react', name: 'React', hint: 'Thư viện dựng giao diện web.' },
          { id: 'supabase', name: 'Supabase', hint: 'Dịch vụ lưu dữ liệu và đăng nhập.' },
        ]),
      'GET /api/v1/catalog': catalog,
      'POST /api/v1/jobs': async (request) => {
        saved = await request.json()
        return json({ id: 'j1' }, 201)
      },
      'GET /api/v1/jobs/j1': () => json(draft),
    })
    renderAt('/san-pham/p1/cong-viec-moi')

    expect(await screen.findByRole('heading', { level: 1, name: 'Đăng công việc' })).toBeInTheDocument()
    for (const label of ['Điều gì đang chưa đúng?', 'Bạn muốn nó hoạt động thế nào?', 'Ai dùng ứng dụng này?']) {
      expect(screen.getByLabelText(label)).toBeInTheDocument()
    }
    expect(screen.getByText('Ví dụ: "Khách đặt bánh nhưng đơn không hiện trong danh sách."')).toBeInTheDocument()
    expect(screen.getByText(/vibefix đoán từ mã nguồn và công cụ AI bạn dùng/)).toBeInTheDocument()
    expect(screen.getByText('2/10 kỹ năng. Mỗi kỹ năng có một câu giải thích ngắn.')).toBeInTheDocument()
    expect(screen.getByText(/Mọi người dùng vibefix đã liên kết GitHub đều đọc được tin này/)).toBeInTheDocument()
    expect(screen.getByText('Lưu nháp để thêm ảnh chụp màn hình.')).toBeInTheDocument()

    await user.type(screen.getByLabelText('Tên công việc'), 'Thêm thanh toán online khi đặt bàn')
    await user.type(screen.getByLabelText('Điều gì đang chưa đúng?'), 'Khách đặt rồi không đến.')
    await user.click(screen.getByRole('checkbox', { name: /Chưa rõ — đánh giá trước/ }))
    expect(screen.getByRole('checkbox', { name: /Sửa lỗi/ })).toBeDisabled()
    await user.selectOptions(screen.getByLabelText('Ngân sách dự kiến'), '2–5 triệu ₫')
    await user.click(screen.getByRole('button', { name: 'Lưu nháp' }))

    await waitFor(() =>
      expect(saved).toMatchObject({
        productId: 'p1',
        title: 'Thêm thanh toán online khi đặt bàn',
        problem: 'Khách đặt rồi không đến.',
        jobTypes: ['ASSESSMENT'],
        skillIds: ['react', 'supabase'],
        budget: 'FROM_2M_TO_5M',
      }),
    )
    expect(await screen.findByRole('heading', { level: 1, name: 'Sửa công việc' })).toBeInTheDocument()
  })

  it('needs a title before saving', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    const seen = fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/products/p1': () => json(product()),
      'GET /api/v1/products/p1/skill-suggestions': () => json([]),
      'GET /api/v1/catalog': catalog,
    })
    renderAt('/san-pham/p1/cong-viec-moi')

    await user.click(await screen.findByRole('button', { name: 'Lưu nháp' }))

    expect(await screen.findByText('Hãy đặt tên cho công việc.')).toBeInTheDocument()
    expect(screen.getByLabelText('Tên công việc')).toHaveAttribute('aria-invalid', 'true')
    expect(seen.some((request) => request.method === 'POST')).toBe(false)
  })

  it('pre-fills a Job that merges a ZIP Fallback’s code (FR-38, N-17)', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/products/p1': () => json(product()),
      'GET /api/v1/products/p1/skill-suggestions': () => json([]),
      'GET /api/v1/catalog': catalog,
    })
    renderAt('/san-pham/p1/cong-viec-moi?ghep-ma=j9')

    expect(await screen.findByLabelText('Tên công việc')).toHaveValue('Ghép mã đã hoàn thiện vào dự án')
    expect(screen.getByLabelText('Điều gì đang chưa đúng?')).toHaveValue(
      'Một công việc trước đã hoàn thiện mã nhưng không hợp nhất tự động được vào dự án gốc. Toàn bộ mã nằm trong tệp ZIP của công việc đó.',
    )
    expect(screen.getByRole('checkbox', { name: /Chỉnh sửa/ })).toBeChecked()
  })

  it('disables every Code Job Type while the Product has open Findings', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/products/p1': () => json(product(1)),
      'GET /api/v1/products/p1/skill-suggestions': () => json([]),
      'GET /api/v1/catalog': catalog,
    })
    renderAt('/san-pham/p1/cong-viec-moi')

    expect(await screen.findByText('Còn 1 vấn đề cần xử lý · bạn vẫn đặt được công việc đánh giá')).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /Tính năng mới/ })).toBeDisabled()
    expect(screen.getByRole('checkbox', { name: /^Đánh giá/ })).toBeEnabled()
    expect(screen.getByRole('link', { name: 'Xử lý vấn đề' })).toHaveAttribute('href', '/san-pham/p1')
  })

  it('uploads screenshots to a saved Draft and repeats the personal-data reminder', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    let uploaded = false
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () =>
        json(uploaded ? { ...draft, screenshots: [{ fileId: 'f1', fileName: 'trang-dat-ban.png', sizeBytes: 430_000, addedAt: draft.updatedAt, url: '/api/v1/files/f1' }] } : draft),
      'GET /api/v1/products/p1': () => json(product()),
      'GET /api/v1/catalog': catalog,
      'POST /api/v1/jobs/j1/screenshots': () => {
        uploaded = true
        return json({ fileId: 'f1', fileName: 'trang-dat-ban.png', sizeBytes: 430_000, addedAt: draft.updatedAt, url: '/api/v1/files/f1' }, 201)
      },
    })
    renderAt('/cong-viec/j1/sua')

    expect(await screen.findByText('Hãy che tên, số điện thoại và thông tin cá nhân của khách hàng bạn trước khi tải ảnh lên.')).toBeInTheDocument()
    expect(screen.getByLabelText('Tên công việc')).toHaveValue(draft.title)
    await user.upload(screen.getByLabelText('Ảnh chụp màn hình (không bắt buộc)'), new File(['png'], 'trang-dat-ban.png', { type: 'image/png' }))

    expect(await screen.findByRole('link', { name: /trang-dat-ban.png · 420 KB/ })).toHaveAttribute('href', '/api/v1/files/f1')
    expect(screen.getByRole('button', { name: 'Xóa trang-dat-ban.png' })).toBeInTheDocument()
  })

  it('keeps planted payloads in every Job text field as text (SG-30)', { timeout: 15_000 }, async () => {
    const payload = PAYLOADS[0]
    fakeBackend(signedInUser, {
      'GET /api/v1/my-jobs': () => json([held('j1', payload, 'CUSTOMER', { state: 'DRAFT', productName: payload })]),
      'GET /api/v1/jobs': () =>
        json([{ id: 'j1', productId: 'p1', productName: payload, state: 'DRAFT', title: payload, jobTypes: [], updatedAt: draft.updatedAt }]),
      'GET /api/v1/jobs/j1': () =>
        json({ ...draft, title: payload, problem: payload, expected: payload, appUsers: payload, doneCheck: payload }),
      'GET /api/v1/products/p1': () => json({ ...product(), name: payload }),
      'GET /api/v1/catalog': catalog,
    })
    renderAt('/cong-viec')

    expect(await screen.findByText(payload)).toBeInTheDocument()
    expect(document.querySelector('img[src="x"]')).toBeNull()
  })
})

const complete = {
  ...draft,
  expected: 'Khách trả tiền cọc khi đặt bàn.',
  appUsers: 'Khách đặt bàn trên điện thoại.',
  version: 0,
  openedAt: null,
  lapsesAt: null,
}

const openJob = {
  ...complete,
  state: 'OPEN',
  version: 1,
  openedAt: '2026-10-16T03:00:00Z',
  lapsesAt: '2026-10-30T03:00:00Z',
}

const call = (request: Request) => `${request.method} ${new URL(request.url, 'http://localhost').pathname}`

describe('Publishing a Job Post and JOB-1 (FR-9, J1, J2)', () => {
  it('saves the Draft, shows N-3, publishes it, and lands on JOB-1', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let published = false
    const seen = fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(published ? openJob : complete),
      'GET /api/v1/products/p1': () => json(product()),
      'GET /api/v1/catalog': catalog,
      'PUT /api/v1/jobs/j1': () => json(complete),
      'POST /api/v1/jobs/j1/publish': () => {
        published = true
        return json(openJob)
      },
    })
    renderAt('/cong-viec/j1/sua')

    await user.click(await screen.findByRole('button', { name: 'Đăng tin' }))
    const dialog = await screen.findByRole('dialog', { name: 'Đăng tin công việc?' })
    expect(
      within(dialog).getByText(/Mọi người dùng vibefix đã liên kết GitHub đều đọc được tin này, kể cả những người có thể chào giá/),
    ).toBeInTheDocument()
    expect(seen.some((request) => request.method === 'POST')).toBe(false)
    await user.click(within(dialog).getByRole('button', { name: 'Đăng tin' }))

    expect(await screen.findByRole('heading', { level: 1, name: complete.title })).toBeInTheDocument()
    expect(screen.getByText('Đang tìm người hướng dẫn')).toBeInTheDocument()
    const calls = seen.map(call)
    expect(calls.indexOf('PUT /api/v1/jobs/j1')).toBeGreaterThanOrEqual(0)
    expect(calls.indexOf('POST /api/v1/jobs/j1/publish')).toBeGreaterThan(calls.indexOf('PUT /api/v1/jobs/j1'))
  })

  it('names the unanswered questions before N-3', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    const seen = fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(draft),
      'GET /api/v1/products/p1': () => json(product()),
      'GET /api/v1/catalog': catalog,
    })
    renderAt('/cong-viec/j1/sua')

    await user.click(await screen.findByRole('button', { name: 'Đăng tin' }))

    expect(
      await screen.findByText('Để đăng tin, hãy trả lời: Bạn muốn nó hoạt động thế nào, Ai dùng ứng dụng này.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(seen.some((request) => request.method !== 'GET')).toBe(false)
  })

  it('says how many security problems block a Code Job', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(complete),
      'GET /api/v1/products/p1': () => json(product()),
      'GET /api/v1/catalog': catalog,
      'PUT /api/v1/jobs/j1': () => json(complete),
      'POST /api/v1/jobs/j1/publish': () =>
        json({ status: 422, code: 'GUARD_FAILED', reason: 'INTAKE_FINDING_OPEN', count: 2 }, 422),
    })
    renderAt('/cong-viec/j1/sua')

    await user.click(await screen.findByRole('button', { name: 'Đăng tin' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Đăng tin' }))

    expect((await screen.findAllByText('Chưa thể đăng tin: còn 2 vấn đề bảo mật chưa xử lý.')).length).toBeGreaterThan(0)
    expect(screen.getByRole('heading', { level: 1, name: 'Sửa công việc' })).toBeInTheDocument()
  })

  it('says GitHub must be linked first and links to ACC-2 (FR-80)', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(complete),
      'GET /api/v1/products/p1': () => json(product()),
      'GET /api/v1/catalog': catalog,
      'PUT /api/v1/jobs/j1': () => json(complete),
      'POST /api/v1/jobs/j1/publish': () => json({ status: 403, code: 'FORBIDDEN', reason: 'GITHUB_NOT_LINKED' }, 403),
    })
    renderAt('/cong-viec/j1/sua')

    await user.click(await screen.findByRole('button', { name: 'Đăng tin' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Đăng tin' }))

    expect(await screen.findAllByText('Bạn không thể làm việc này. Hãy liên kết GitHub trước.')).not.toHaveLength(0)
    // Base UI hides a toast from the accessibility tree until the toasts region is reached (F6).
    expect(screen.getAllByRole('link', { name: 'Liên kết GitHub', hidden: true })[0]).toHaveAttribute('href', '/ho-so/xac-minh')
  })

  it('shows the Open Job on JOB-1 with what happens next, and withdraws it', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let withdrawn = false
    const seen = fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(withdrawn ? { ...complete, version: 2 } : openJob),
      'GET /api/v1/products/p1': () => json(product()),
      'GET /api/v1/catalog': catalog,
      'POST /api/v1/jobs/j1/withdraw': () => {
        withdrawn = true
        return json({ ...complete, version: 2 })
      },
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByRole('heading', { level: 1, name: openJob.title })).toBeInTheDocument()
    expect(screen.getByText('Đang tìm người hướng dẫn')).toBeInTheDocument()
    expect(screen.getByText('Sổ đặt bàn Quán Mây · Tính năng mới')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Tổng quan' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('heading', { level: 2, name: 'Chưa có đơn ứng tuyển' })).toBeInTheDocument()
    expect(screen.getByText('Tin hết hạn 30/10')).toBeInTheDocument()
    expect(screen.getByText('Người hướng dẫn ứng tuyển')).toBeInTheDocument()
    expect(screen.getByText('Bạn chọn người hướng dẫn')).toBeInTheDocument()
    expect(screen.getByText('Họ ký cam kết bảo mật và chọn nhóm thực hiện')).toBeInTheDocument()
    expect(screen.getByText('Nếu bạn chưa chọn ai trước 30/10, tin tự hết hạn và trở về bản nháp.')).toBeInTheDocument()
    const post = screen.getByRole('region', { name: 'Tin công việc' })
    expect(within(post).getByText(openJob.expected)).toBeInTheDocument()
    expect(within(post).getByText('React')).toBeInTheDocument()
    expect(within(post).getByText(/Ngân sách: 2–5 triệu ₫/)).toBeInTheDocument()
    expect(within(post).getByText(/đăng 16\/10/)).toBeInTheDocument()
    expect(screen.getByText(/Mã của sản phẩm nằm trong kho mã công khai/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Sổ đặt bàn Quán Mây' })).toHaveAttribute('href', '/san-pham/p1')

    await user.click(screen.getByRole('button', { name: 'Rút tin' }))
    const dialog = await screen.findByRole('dialog', { name: 'Rút tin này?' })
    await user.click(within(dialog).getByRole('button', { name: 'Rút tin' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Sửa công việc' })).toBeInTheDocument()
    expect(seen.map(call)).toContain('POST /api/v1/jobs/j1/withdraw')
  })

  it("shows the Customer's JOB-1 by the role in the Job, never by the account (Stories 20.3, 20.6)", { timeout: 15_000 }, async () => {
    fakeBackend(signedInWithGoogle, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(openJob),
      'GET /api/v1/products/p1': () => json(product()),
      'GET /api/v1/catalog': catalog,
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByRole('heading', { level: 1, name: openJob.title })).toBeInTheDocument()
    expect(screen.getByText('Đang tìm người hướng dẫn')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Rút tin' })).toBeInTheDocument()
    expect(screen.getByText('Bạn là: Khách hàng')).toBeInTheDocument()
    expect(screen.queryByText('Không tìm thấy nội dung này.')).not.toBeInTheDocument()
  })

  it('shows the not-found screen on a Job where the user holds no role', async () => {
    fakeBackend(signedInUser, { 'GET /api/v1/jobs/j1': () => json(openJob) })
    renderAt('/cong-viec/j1')

    expect(await screen.findByText('Không tìm thấy nội dung này.')).toBeInTheDocument()
  })

  it('opens a Draft in CUS-6', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(complete),
      'GET /api/v1/products/p1': () => json(product()),
      'GET /api/v1/catalog': catalog,
    })
    const router = renderAt('/cong-viec/j1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Sửa công việc' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/cong-viec/j1/sua')
  })

  it('keeps planted payloads on JOB-1 as text (SG-30)', { timeout: 15_000 }, async () => {
    const payload = PAYLOADS[0]
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () =>
        json({ ...openJob, title: payload, productName: payload, problem: payload, expected: payload, appUsers: payload, doneCheck: payload }),
      'GET /api/v1/products/p1': () => json({ ...product(), name: payload }),
    })
    renderAt('/cong-viec/j1')

    expect((await screen.findAllByText(payload)).length).toBeGreaterThan(0)
    expect(document.querySelector('img[src="x"]')).toBeNull()
  })
})
