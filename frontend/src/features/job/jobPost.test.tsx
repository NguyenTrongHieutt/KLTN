import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { capabilities, fakeBackend, inEveryJob, json, signedInUser, signedInWithGoogle } from '@/test/fakeBackend'
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

const catalog = () =>
  json({
    categories: [
      {
        id: 'c1',
        name: 'Giao diện web',
        skills: [
          { id: 'react', name: 'React', hint: 'Thư viện dựng giao diện web.' },
          { id: 'supabase', name: 'Supabase', hint: 'Dịch vụ lưu dữ liệu và đăng nhập.' },
        ],
      },
    ],
  })

const summary = {
  id: 'j1',
  title: 'Sửa lỗi mất đơn hàng',
  productName: 'Shop Gốm Bát Tràng',
  jobTypes: ['BUG_FIX'],
  skills: ['React', 'Supabase'],
  budget: 'FROM_2M_TO_5M',
  deadline: '2026-11-10',
  openedAt: '2026-10-15T03:00:00Z',
  phase: 'FINDING_MENTOR',
  need: 'MENTOR',
  customerKind: 'INDIVIDUAL',
  customerJoinedAt: '2026-07-14T03:00:00Z',
}

/** A Job in Agreement whose Mentor recruits two more members, one of them signed already (Story 21.1). */
const recruitingSummary = {
  id: 'j2',
  title: 'Hoàn thiện trang bán hàng',
  productName: 'Shop Gốm Bát Tràng',
  jobTypes: ['NEW_FEATURE'],
  skills: ['React'],
  openedAt: '2026-10-10T03:00:00Z',
  phase: 'RECRUITING',
  need: 'CREW',
  customerKind: 'BUSINESS',
  customerJoinedAt: '2026-07-14T03:00:00Z',
  mentor: { displayName: 'Phạm Thu Hà', githubLogin: 'thuha' },
  recruitment: { openSlots: 2, skills: ['Supabase'], note: 'Cần người làm trang thanh toán.' },
}

const page = (items: unknown[], total = items.length) => json({ items, page: 0, size: 20, total })

const capacity = (reasons: string[] = [], teamSize = 2, freeStudents = 1) =>
  json({ teamSize, freeStudents, activeJobs: 0, jobLimit: 3, reasons })

const post = {
  id: 'j1',
  productId: 'p1',
  phase: 'FINDING_MENTOR',
  need: 'MENTOR',
  title: 'Sửa lỗi mất đơn hàng',
  productName: 'Shop Gốm Bát Tràng',
  productDescription: 'Cửa hàng gốm bán online.',
  productTools: ['LOVABLE'],
  productOpenFindings: 0,
  problem: 'Khách đặt nhưng đơn không hiện.',
  expected: 'Mỗi đơn hiện ngay trong danh sách.',
  appUsers: 'Khách mua trên điện thoại.',
  doneCheck: 'Tôi đặt thử 5 đơn và thấy đủ.',
  jobTypes: ['BUG_FIX'],
  skills: [{ id: 'react', name: 'React', hint: 'Thư viện dựng giao diện web.' }],
  budget: 'FROM_2M_TO_5M',
  deadline: '2026-11-10',
  openedAt: '2026-10-15T03:00:00Z',
  lapsesAt: '2026-10-29T03:00:00Z',
  screenshots: [{ fileId: 'f1', fileName: 'danh-sach-don.png', sizeBytes: 430_000, addedAt: '2026-10-15T03:00:00Z', url: '/api/v1/files/f1' }],
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('MEN-2, browsing Job Posts (FR-12)', () => {
  it('lists every Job that needs people with its phase, what it needs, and the Customer (UX-DR97)', { timeout: 15_000 }, async () => {
    const seen = fakeBackend(signedInUser, {
      'GET /api/v1/mentor/job-posts': () => page([summary, recruitingSummary]),
      'GET /api/v1/mentor/customer-ratings': () =>
        json([
          { postId: 'j1', average: 4.8, count: 5 },
          { postId: 'j2', count: 0 },
        ]),
      'GET /api/v1/catalog': catalog,
    })
    renderAt('/tim-viec')

    const link = await screen.findByRole('link', { name: 'Sửa lỗi mất đơn hàng' })
    expect(link).toHaveAttribute('href', '/tim-viec/j1')
    const row = link.closest('li') as HTMLElement
    expect(within(row).getByText('Shop Gốm Bát Tràng · Sửa lỗi · 2–5 triệu ₫ · đăng 15/10')).toBeInTheDocument()
    expect(within(row).getByText('Supabase')).toBeInTheDocument()
    expect(within(row).getByText('Hạn 10/11')).toBeInTheDocument()
    expect(within(row).getByText('Đang tìm người hướng dẫn')).toBeInTheDocument()
    expect(within(row).getByText('Cần người hướng dẫn')).toBeInTheDocument()
    expect(await within(row).findByText('Khách hàng: Cá nhân · từ 07/2026 · 4,8 sao (5 lượt)')).toBeInTheDocument()
    expect(within(row).queryByRole('button')).toBeNull()

    const recruiting = screen.getByRole('link', { name: 'Hoàn thiện trang bán hàng' }).closest('li') as HTMLElement
    expect(within(recruiting).getByText('Đang tuyển thêm thành viên')).toBeInTheDocument()
    expect(within(recruiting).getByText('Cần thêm 2 thành viên nhóm')).toBeInTheDocument()
    expect(within(recruiting).getByRole('link', { name: 'Phạm Thu Hà' })).toHaveAttribute('href', '/mentor/thuha')
    expect(within(recruiting).getByText('Kỹ năng cần tìm: Supabase')).toBeInTheDocument()
    expect(within(recruiting).getByText('Cần người làm trang thanh toán.')).toBeInTheDocument()
    expect(
      within(recruiting).getByText('Khách hàng: Doanh nghiệp · từ 07/2026 · chưa có lượt chấm sao'),
    ).toBeInTheDocument()

    expect(screen.getByRole('heading', { level: 2, name: /Tất cả tin công việc/ })).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'Tìm việc' }).length).toBeGreaterThan(0)
    // One call for the page's Ratings (SG-43); the capacity banners are retired from MEN-2 (Story 21.1).
    const ratingCalls = seen.filter((request) => request.url.includes('/api/v1/mentor/customer-ratings'))
    expect(ratingCalls).toHaveLength(1)
    expect(new URL(ratingCalls[0].url, 'http://localhost').searchParams.getAll('post')).toEqual(['j1', 'j2'])
    expect(seen.some((request) => request.url.includes('/api/v1/mentor/capacity'))).toBe(false)
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('applies the filters at once and keeps them in the URL', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const seen = fakeBackend(signedInUser, {
      'GET /api/v1/mentor/job-posts': () => page([summary]),
      'GET /api/v1/mentor/capacity': () => capacity(),
      'GET /api/v1/catalog': catalog,
    })
    const router = renderAt('/tim-viec')

    await user.selectOptions(await screen.findByLabelText('Tình trạng'), 'Đang tuyển thêm thành viên')
    await user.selectOptions(screen.getByLabelText('Loại công việc'), 'Sửa lỗi')
    await user.selectOptions(screen.getByLabelText('Hạn'), 'Trong 2 tuần')
    await user.type(screen.getByRole('combobox', { name: 'Kỹ năng' }), 'giao')
    await user.click(within(await screen.findByRole('listbox')).getByRole('option', { name: 'Cả nhóm Giao diện web' }))

    await waitFor(() => {
      const query = new URLSearchParams(router.state.location.search)
      expect(query.get('tinh-trang')).toBe('tuyen-them')
      expect(query.get('loai')).toBe('BUG_FIX')
      expect(query.get('han')).toBe('14')
      expect(query.get('nhom-ky-nang')).toBe('c1')
    })
    await waitFor(() => {
      const last = seen.filter((request) => request.url.includes('/api/v1/mentor/job-posts')).at(-1)
      const query = new URL(last?.url ?? '', 'http://localhost').searchParams
      expect(query.get('phase')).toBe('RECRUITING')
      expect(query.get('type')).toBe('BUG_FIX')
      expect(query.get('within')).toBe('14')
      expect(query.get('category')).toBe('c1')
    })
  })

  it('says there is nothing yet, and offers to clear filters that match nothing', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      'GET /api/v1/mentor/job-posts': () => page([]),
      'GET /api/v1/mentor/capacity': () => capacity(),
      'GET /api/v1/catalog': catalog,
    })
    const router = renderAt('/tim-viec?loai=BUG_FIX')

    expect(await screen.findByText('Không tìm thấy công việc nào khớp với bộ lọc.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Bỏ bộ lọc' }))

    expect(await screen.findByText('Hiện chưa có công việc nào cần người. Hãy quay lại sau.')).toBeInTheDocument()
    expect(router.state.location.search).toBe('')
    expect(screen.queryByRole('button', { name: 'Bỏ bộ lọc' })).toBeNull()
  })

  it('reads the phase filter from the URL and keeps it while paging', { timeout: 15_000 }, async () => {
    const seen = fakeBackend(signedInUser, {
      'GET /api/v1/mentor/job-posts': () => page([recruitingSummary]),
      'GET /api/v1/catalog': catalog,
    })
    renderAt('/tim-viec?tinh-trang=tim-nhom')

    expect(await screen.findByRole('link', { name: 'Hoàn thiện trang bán hàng' })).toBeInTheDocument()
    expect(screen.getByLabelText('Tình trạng')).toHaveValue('FINDING_CREW')
    const last = seen.filter((request) => request.url.includes('/api/v1/mentor/job-posts')).at(-1)
    expect(new URL(last?.url ?? '', 'http://localhost').searchParams.get('phase')).toBe('FINDING_CREW')
  })

  it('shows no capacity banner any more, the free-Student one included (Story 21.1)', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/mentor/job-posts': () => page([summary]),
      'GET /api/v1/mentor/capacity': () => capacity(['NO_FREE_STUDENT', 'MENTOR_JOB_LIMIT'], 0, 0),
      'GET /api/v1/catalog': catalog,
    })
    renderAt('/tim-viec')

    expect(await screen.findByRole('link', { name: 'Sửa lỗi mất đơn hàng' })).toBeInTheDocument()
    expect(screen.queryByText('Nhóm của bạn chưa có thành viên rảnh, nên chưa ứng tuyển được.')).toBeNull()
    expect(screen.queryByText(/Bạn đang nhận đủ 3 công việc/)).toBeNull()
    expect(screen.queryByRole('link', { name: 'Mời thành viên' })).toBeNull()
  })

  it('shows the Job Posts to any user with GitHub linked, with no role and no approval step (Stories 19.7, 20.6)', async () => {
    const seen = fakeBackend(signedInUser, {
      'GET /api/v1/mentor/job-posts': () => page([]),
      'GET /api/v1/mentor/capacity': () => capacity(),
    })
    const router = renderAt('/tim-viec')

    await waitFor(() => expect(seen.some((request) => request.url.includes('/api/v1/mentor/job-posts'))).toBe(true))
    expect(router.state.location.pathname).toBe('/tim-viec')
  })
})

describe('"Tìm việc" behind the GitHub gate (X1, FR-80, Story 20.1)', () => {
  const unlinked = () => {
    const mentor = signedInUser
    return {
      ...mentor,
      githubLogin: undefined,
      account: { ...mentor.account!, capabilities: capabilities({ githubVerified: false }) },
    }
  }

  it.each([
    ['MEN-2', '/tim-viec'],
    ['MEN-3', '/tim-viec/j1'],
  ])('shows a Mentor without GitHub linked the gate on %s and reads no Job Post', async (_, path) => {
    const seen = fakeBackend(unlinked())
    renderAt(path)

    const gate = await screen.findByRole('heading', { level: 2, name: 'Liên kết GitHub để tìm việc' })
    expect(gate).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: 'Tìm việc' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Liên kết GitHub/ })).toBeInTheDocument()
    expect(screen.getByText('Sau khi liên kết, bạn quay về đúng trang này.')).toBeInTheDocument()
    const paths = seen.map((request) => new URL(request.url, 'http://localhost').pathname)
    expect(paths.filter((pathname) => pathname.startsWith('/api/v1/mentor/'))).toEqual([])
  })

  it.each([
    ['MEN-2', '/tim-viec'],
    ['MEN-3', '/tim-viec/j1'],
  ])('shows a user with no role and no GitHub the same gate on %s (Story 20.3)', async (_, path) => {
    const seen = fakeBackend(signedInWithGoogle)
    renderAt(path)

    expect(await screen.findByRole('heading', { level: 2, name: 'Liên kết GitHub để tìm việc' })).toBeInTheDocument()
    expect(screen.queryByText('Không tìm thấy nội dung này.')).not.toBeInTheDocument()
    const paths = seen.map((request) => new URL(request.url, 'http://localhost').pathname)
    expect(paths.filter((pathname) => pathname.startsWith('/api/v1/mentor/'))).toEqual([])
  })

  it('lists the Job Posts for a user with no role once GitHub is linked (Story 20.3)', async () => {
    const linked = {
      ...signedInWithGoogle,
      githubLogin: 'lan-nguyen',
      account: { ...signedInWithGoogle.account!, capabilities: capabilities({ githubVerified: true }) },
    }
    const seen = fakeBackend(linked, {
      'GET /api/v1/mentor/job-posts': () => page([]),
      'GET /api/v1/mentor/capacity': () => capacity(),
    })
    renderAt('/tim-viec')

    await waitFor(() => expect(seen.some((request) => request.url.includes('/api/v1/mentor/job-posts'))).toBe(true))
    expect(screen.queryByText('Không tìm thấy nội dung này.')).not.toBeInTheDocument()
  })

  it('reads the session again when the link read finds GitHub linked', { timeout: 15_000 }, async () => {
    const mentor = signedInUser
    const stale = { ...mentor, account: { ...mentor.account!, capabilities: capabilities({ githubVerified: false }) } }
    let sessionReads = 0
    fakeBackend(mentor, {
      'GET /api/v1/session': () => {
        sessionReads += 1
        return json(sessionReads === 1 ? stale : mentor)
      },
      'GET /api/v1/mentor/job-posts': () => page([summary]),
      'GET /api/v1/mentor/capacity': () => capacity(),
      'GET /api/v1/catalog': catalog,
    })
    renderAt('/tim-viec')

    expect(await screen.findByRole('link', { name: 'Sửa lỗi mất đơn hàng' })).toHaveAttribute('href', '/tim-viec/j1')
    expect(sessionReads).toBeGreaterThanOrEqual(2)
    expect(screen.queryByRole('heading', { name: 'Liên kết GitHub để tìm việc' })).not.toBeInTheDocument()
  })
})

describe('MEN-3, one Job Post (FR-12)', () => {
  it('shows the answers, Skills with hints, the Product, the Rating summary, and the bid', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/mentor/job-posts/j1': () => json(post),
      'GET /api/v1/mentor/job-posts/j1/customer-rating': () => json({ count: 0 }),
      'GET /api/v1/mentor/capacity': () => capacity(),
    })
    renderAt('/tim-viec/j1')

    expect(await screen.findByRole('heading', { level: 1, name: post.title })).toBeInTheDocument()
    expect(screen.getByText('Đang tìm người hướng dẫn')).toBeInTheDocument()
    expect(screen.getByText('Cần người hướng dẫn')).toBeInTheDocument()
    expect(
      screen.getByText('Shop Gốm Bát Tràng · Sửa lỗi · 2–5 triệu ₫ · mong xong trước 10/11 · đăng 15/10'),
    ).toBeInTheDocument()
    expect(screen.getByText('Khách hàng muốn nó hoạt động thế nào?')).toBeInTheDocument()
    expect(screen.getByText(post.expected)).toBeInTheDocument()
    expect(screen.getByText('Thư viện dựng giao diện web.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /danh-sach-don.png/ })).toHaveAttribute('href', '/api/v1/files/f1')
    expect(screen.getByText('Shop Gốm Bát Tràng, làm bằng Lovable.')).toBeInTheDocument()
    expect(screen.getByText('Kiểm tra khóa bí mật: không còn vấn đề nào mở.')).toBeInTheDocument()
    expect(await screen.findByText('Chưa có lượt chấm sao')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { level: 2, name: 'Chào giá làm người hướng dẫn' })).toBeInTheDocument()
    // Story 21.1 retires the Team card and the free-Student banner.
    expect(screen.queryByText('2 thành viên · 1 đang rảnh')).toBeNull()
    expect(screen.queryByRole('link', { name: 'Xem nhóm' })).toBeNull()
  })

  it('shows what a recruiting Mentor seeks in "Xin vào nhóm", with no bid (UX-DR98)', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/mentor/job-posts/j1': () =>
        json({
          ...post,
          lapsesAt: undefined,
          phase: 'RECRUITING',
          need: 'CREW',
          mentor: { displayName: 'Phạm Thu Hà', githubLogin: 'thuha' },
          recruitment: { openSlots: 2, skills: ['Supabase'], note: 'Cần người làm trang thanh toán.' },
        }),
      'GET /api/v1/mentor/job-posts/j1/customer-rating': () => json({ count: 0 }),
    })
    renderAt('/tim-viec/j1')

    expect(await screen.findByText('Đang tuyển thêm thành viên')).toBeInTheDocument()
    expect(screen.getByText('Cần thêm 2 thành viên nhóm')).toBeInTheDocument()
    const area = await screen.findByRole('region', { name: 'Xin vào nhóm' })
    expect(within(area).getByRole('link', { name: 'Phạm Thu Hà' })).toHaveAttribute('href', '/mentor/thuha')
    expect(within(area).getByText('Còn 2 chỗ')).toBeInTheDocument()
    expect(within(area).getByText('Supabase')).toBeInTheDocument()
    expect(within(area).getByText('Cần người làm trang thanh toán.')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Chào giá làm người hướng dẫn' })).toBeNull()
    expect(screen.queryByLabelText('Giá đề xuất')).toBeNull()
  })

  it("shows the Job's own people their role in place of an action (AD-43, UX-DR98)", { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/mentor/job-posts/j1': () => json(post),
      'GET /api/v1/mentor/job-posts/j1/customer-rating': () => json({ count: 0 }),
      'GET /api/v1/mentor/capacity': () => capacity(),
    })
    renderAt('/tim-viec/j1')

    expect(await screen.findByText('Bạn là Khách hàng của công việc này.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Mở công việc' })).toHaveAttribute('href', '/cong-viec/j1')
    expect(screen.queryByRole('heading', { name: 'Chào giá làm người hướng dẫn' })).toBeNull()
    expect(screen.queryByLabelText('Giá đề xuất')).toBeNull()
  })

  it('shows their role to the people of a Job no longer listed', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, { ...inEveryJob('MENTOR') })
    renderAt('/tim-viec/j9')

    expect(await screen.findByText('Bạn là Người hướng dẫn của công việc này.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Mở công việc' })).toHaveAttribute('href', '/cong-viec/j9')
  })

  it('shows the Customer average when there are Ratings', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/mentor/job-posts/j1': () => json(post),
      'GET /api/v1/mentor/job-posts/j1/customer-rating': () =>
        json({ average: 4.8, count: 5, customerKind: 'INDIVIDUAL', customerJoinedAt: '2026-07-14T03:00:00Z' }),
      'GET /api/v1/mentor/capacity': () => capacity(),
    })
    renderAt('/tim-viec/j1')

    expect(await screen.findByText('4,8 sao')).toBeInTheDocument()
    expect(screen.getByText('(5 lượt từ người hướng dẫn)')).toBeInTheDocument()
    // Author decision 2026-10-21: the Customer's kind and month joined, never their name before selection.
    expect(screen.getByText('Cá nhân · từ 07/2026')).toBeInTheDocument()
  })

  it('shows the plain "no longer open" gate for a Job no longer listed (UX-DR95)', async () => {
    fakeBackend(signedInUser, { 'GET /api/v1/mentor/capacity': () => capacity() })
    renderAt('/tim-viec/j9')

    expect(await screen.findByRole('heading', { level: 1, name: 'Công việc này không còn nhận người.' })).toBeInTheDocument()
    expect(screen.getByText('Công việc đã tìm đủ người hoặc tin đã đóng.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Tìm việc khác' })).toHaveAttribute('href', '/tim-viec')
  })

  it('keeps planted payloads in Job Posts as text (SG-30)', { timeout: 15_000 }, async () => {
    const payload = PAYLOADS[0]
    fakeBackend(signedInUser, {
      'GET /api/v1/mentor/job-posts': () =>
        page([
          { ...summary, title: payload, productName: payload, skills: [payload] },
          {
            ...recruitingSummary,
            mentor: { displayName: payload, githubLogin: 'thuha' },
            recruitment: { openSlots: 1, skills: [payload], note: payload },
          },
        ]),
      'GET /api/v1/mentor/job-posts/j1': () =>
        json({
          ...post,
          title: payload,
          productName: payload,
          productDescription: payload,
          problem: payload,
          expected: payload,
          appUsers: payload,
          doneCheck: payload,
          skills: [{ id: 'x', name: payload, hint: payload }],
          screenshots: [],
        }),
      'GET /api/v1/mentor/job-posts/j1/customer-rating': () => json({ count: 0 }),
      'GET /api/v1/mentor/capacity': () => capacity(),
      'GET /api/v1/catalog': catalog,
    })
    const router = renderAt('/tim-viec')
    expect((await screen.findAllByText(payload)).length).toBeGreaterThan(0)
    await router.navigate('/tim-viec/j1')
    expect(await screen.findByText('Chưa có lượt chấm sao')).toBeInTheDocument()
    expect(screen.getAllByText(payload).length).toBeGreaterThan(3)
    expect(document.querySelector('img[src="x"]')).toBeNull()
  })
})

const keywordOf = (request: Request) => new URL(request.url, 'http://localhost').searchParams.get('q')

describe('MEN-2, searching by keyword (FR-12, UX-DR47)', () => {
  it('searches only on Enter, keeps the keyword in the URL, and clears it', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const seen = fakeBackend(signedInUser, {
      'GET /api/v1/mentor/job-posts': (request) =>
        page(keywordOf(request) ? [{ ...summary, title: 'Sửa trang bán hàng' }] : [summary, { ...summary, id: 'j2' }]),
      'GET /api/v1/mentor/capacity': () => capacity(),
      'GET /api/v1/catalog': catalog,
    })
    const router = renderAt('/tim-viec')

    const input = await screen.findByLabelText('Tìm tin công việc')
    expect(input).toHaveAttribute('maxLength', '100')
    expect(screen.getByText('Gõ có dấu hoặc không dấu đều được, ví dụ: ban hang.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Tìm' })).toBeDisabled()
    await user.type(input, 'ban hang')
    expect(seen.some((request) => keywordOf(request) !== null)).toBe(false)
    await user.keyboard('{Enter}')

    expect(await screen.findByRole('heading', { level: 2, name: 'Kết quả cho “ban hang” (1)' })).toBeInTheDocument()
    expect(screen.getByText('Xếp theo mức độ khớp với từ khóa. Điểm chấm sao không làm thay đổi thứ tự.')).toBeInTheDocument()
    expect(new URLSearchParams(router.state.location.search).get('q')).toBe('ban hang')
    expect(screen.queryByRole('heading', { name: /Tất cả tin công việc/ })).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Xóa từ khóa' }))
    expect(await screen.findByRole('heading', { level: 2, name: /Tất cả tin công việc/ })).toBeInTheDocument()
    expect(router.state.location.search).toBe('')
    expect(screen.getByLabelText('Tìm tin công việc')).toHaveValue('')
  })

  it('says nothing matched the keyword and clears only the filters', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      'GET /api/v1/mentor/job-posts': () => page([]),
      'GET /api/v1/mentor/capacity': () => capacity(),
      'GET /api/v1/catalog': catalog,
    })
    const router = renderAt('/tim-viec?q=ban+hang+zalo&loai=BUG_FIX')

    expect(await screen.findByText('Không tìm thấy công việc nào khớp với “ban hang zalo”.')).toBeInTheDocument()
    expect(screen.getByText('Hãy thử từ khóa ngắn hơn hoặc bỏ bớt bộ lọc.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Bỏ bộ lọc' }))

    await waitFor(() => expect(router.state.location.search).toBe('?q=ban+hang+zalo'))
    expect(screen.queryByRole('button', { name: 'Bỏ bộ lọc' })).toBeNull()
  })

  it('keeps the earlier results and says how long to wait when searching too fast', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      'GET /api/v1/mentor/job-posts': (request) =>
        keywordOf(request) === 'zalo'
          ? new Response(JSON.stringify({ status: 429, code: 'RATE_LIMITED' }), {
              status: 429,
              headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '42' },
            })
          : page([summary]),
      'GET /api/v1/mentor/capacity': () => capacity(),
      'GET /api/v1/catalog': catalog,
    })
    renderAt('/tim-viec')

    expect(await screen.findByRole('link', { name: /Sửa lỗi mất đơn hàng/ })).toBeInTheDocument()
    await user.type(screen.getByLabelText('Tìm tin công việc'), 'zalo')
    await user.click(screen.getByRole('button', { name: 'Tìm' }))

    expect(await screen.findByText('Bạn đang tìm hơi nhanh. Hãy đợi 42 giây rồi tìm lại.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Sửa lỗi mất đơn hàng/ })).toBeInTheDocument()
    expect(screen.queryByText('Gõ có dấu hoặc không dấu đều được, ví dụ: ban hang.')).toBeNull()
  })
})
