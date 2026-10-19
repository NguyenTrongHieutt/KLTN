import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedIn } from '@/test/fakeBackend'
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
}

const page = (items: unknown[], total = items.length) => json({ items, page: 0, size: 20, total })

const capacity = (reasons: string[] = [], teamSize = 2, freeStudents = 1) =>
  json({ teamSize, freeStudents, activeJobs: 0, jobLimit: 3, reasons })

const post = {
  id: 'j1',
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
  it('lists the Open Job Posts and links each to MEN-3', { timeout: 15_000 }, async () => {
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/mentor/job-posts': () => page([summary]),
      'GET /api/v1/mentor/capacity': () => capacity(),
      'GET /api/v1/catalog': catalog,
    })
    renderAt('/tim-viec')

    const link = await screen.findByRole('link', { name: /Sửa lỗi mất đơn hàng/ })
    expect(link).toHaveAttribute('href', '/tim-viec/j1')
    expect(within(link).getByText('Shop Gốm Bát Tràng · Sửa lỗi · 2–5 triệu ₫ · đăng 15/10')).toBeInTheDocument()
    expect(within(link).getByText('Supabase')).toBeInTheDocument()
    expect(within(link).getByText('Hạn 10/11')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: /Tất cả tin công việc/ })).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'Tìm việc' }).length).toBeGreaterThan(0)
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('applies the filters at once and keeps them in the URL', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const seen = fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/mentor/job-posts': () => page([summary]),
      'GET /api/v1/mentor/capacity': () => capacity(),
      'GET /api/v1/catalog': catalog,
    })
    const router = renderAt('/tim-viec')

    await user.selectOptions(await screen.findByLabelText('Loại công việc'), 'Sửa lỗi')
    await user.selectOptions(screen.getByLabelText('Hạn'), 'Trong 2 tuần')
    await user.type(screen.getByRole('combobox', { name: 'Kỹ năng' }), 'giao')
    await user.click(within(await screen.findByRole('listbox')).getByRole('option', { name: 'Cả nhóm Giao diện web' }))

    await waitFor(() => {
      const query = new URLSearchParams(router.state.location.search)
      expect(query.get('loai')).toBe('BUG_FIX')
      expect(query.get('han')).toBe('14')
      expect(query.get('nhom-ky-nang')).toBe('c1')
    })
    await waitFor(() => {
      const last = seen.filter((request) => request.url.includes('/api/v1/mentor/job-posts')).at(-1)
      const query = new URL(last?.url ?? '', 'http://localhost').searchParams
      expect(query.get('type')).toBe('BUG_FIX')
      expect(query.get('within')).toBe('14')
      expect(query.get('category')).toBe('c1')
    })
  })

  it('says there is nothing yet, and offers to clear filters that match nothing', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/mentor/job-posts': () => page([]),
      'GET /api/v1/mentor/capacity': () => capacity(),
      'GET /api/v1/catalog': catalog,
    })
    const router = renderAt('/tim-viec?loai=BUG_FIX')

    expect(await screen.findByText('Không tìm thấy công việc nào khớp với bộ lọc.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Bỏ bộ lọc' }))

    expect(await screen.findByText('Hiện chưa có công việc nào đang nhận ứng tuyển. Hãy quay lại sau.')).toBeInTheDocument()
    expect(router.state.location.search).toBe('')
    expect(screen.queryByRole('button', { name: 'Bỏ bộ lọc' })).toBeNull()
  })

  it('states why the Mentor cannot apply now (FR-13)', { timeout: 15_000 }, async () => {
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/mentor/job-posts': () => page([summary]),
      'GET /api/v1/mentor/capacity': () => capacity(['NO_FREE_STUDENT', 'MENTOR_JOB_LIMIT'], 0, 0),
      'GET /api/v1/catalog': catalog,
    })
    renderAt('/tim-viec')

    expect(await screen.findByText('Nhóm của bạn chưa có sinh viên rảnh, nên chưa ứng tuyển được.')).toBeInTheDocument()
    expect(screen.getByText(/Nhóm của bạn chưa có sinh viên nào/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Mời sinh viên' })).toHaveAttribute('href', '/nhom')
    expect(screen.getByText('Bạn đang nhận đủ 3 công việc. Hãy hoàn thành bớt rồi ứng tuyển tiếp.')).toBeInTheDocument()
  })

  it.each([
    ['a Customer', signedIn('CUSTOMER')],
    ['a Student', signedIn('STUDENT')],
  ])('finds nothing for %s', async (_, session) => {
    fakeBackend(session)
    renderAt('/tim-viec')

    expect(await screen.findByText('Không tìm thấy nội dung này.')).toBeInTheDocument()
  })

  it('keeps a Mentor who is not approved on MEN-5 (FR-74)', async () => {
    const seen = fakeBackend(signedIn('MENTOR', 'PENDING'))
    const router = renderAt('/tim-viec')

    await waitFor(() => expect(router.state.location.pathname).toBe('/ho-so'))
    expect(seen.some((request) => request.url.includes('/api/v1/mentor/job-posts'))).toBe(false)
  })
})

describe('MEN-3, one Job Post (FR-12)', () => {
  it('shows the answers, Skills with hints, the Product, the Rating summary, and the Team', { timeout: 15_000 }, async () => {
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/mentor/job-posts/j1': () => json(post),
      'GET /api/v1/mentor/job-posts/j1/customer-rating': () => json({ count: 0 }),
      'GET /api/v1/mentor/capacity': () => capacity(),
    })
    renderAt('/tim-viec/j1')

    expect(await screen.findByRole('heading', { level: 1, name: post.title })).toBeInTheDocument()
    expect(screen.getByText('Đang nhận ứng tuyển')).toBeInTheDocument()
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
    expect(screen.getByText('2 sinh viên · 1 đang rảnh')).toBeInTheDocument()
  })

  it('shows the Customer average when there are Ratings', { timeout: 15_000 }, async () => {
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/mentor/job-posts/j1': () => json(post),
      'GET /api/v1/mentor/job-posts/j1/customer-rating': () => json({ average: 4.8, count: 5 }),
      'GET /api/v1/mentor/capacity': () => capacity(),
    })
    renderAt('/tim-viec/j1')

    expect(await screen.findByText('4,8 sao')).toBeInTheDocument()
    expect(screen.getByText('(5 lượt từ người hướng dẫn)')).toBeInTheDocument()
  })

  it('shows the not-found screen for a Job Post that is not open', async () => {
    fakeBackend(signedIn('MENTOR'), { 'GET /api/v1/mentor/capacity': () => capacity() })
    renderAt('/tim-viec/j9')

    expect(await screen.findByText('Không tìm thấy nội dung này.')).toBeInTheDocument()
  })

  it('keeps planted payloads in Job Posts as text (SG-30)', { timeout: 15_000 }, async () => {
    const payload = PAYLOADS[0]
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/mentor/job-posts': () => page([{ ...summary, title: payload, productName: payload, skills: [payload] }]),
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
    const seen = fakeBackend(signedIn('MENTOR'), {
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
    fakeBackend(signedIn('MENTOR'), {
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
    fakeBackend(signedIn('MENTOR'), {
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
