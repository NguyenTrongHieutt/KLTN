import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedIn } from '@/test/fakeBackend'
// Loaded up front: the route's lazy chunk with react-markdown is slow to load the first time.
import './DocsPage'

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

const templates = [
  { template: 'ASSESSMENT_REPORT', title: 'Báo cáo đánh giá' },
  { template: 'MEETING_NOTES', title: 'Biên bản họp' },
]

const tree = (overrides: object = {}) => ({
  boardId: 'j1',
  title: 'Đánh giá ứng dụng đặt bàn',
  mentor: false,
  readOnly: false,
  customer: false,
  templates,
  pages: [
    { id: 'r1', title: 'Báo cáo đánh giá', versionNo: 1, updatedAt: '2026-10-20T03:00:00Z', template: 'ASSESSMENT_REPORT', visibility: 'CUSTOMER' },
    { id: 'p1', title: 'Ghi chú kỹ thuật', versionNo: 1, updatedAt: '2026-10-20T03:00:00Z', visibility: 'TEAM' },
  ],
  ...overrides,
})

const page = (overrides: object = {}) => ({
  id: 'p1',
  boardId: 'j1',
  title: 'Ghi chú kỹ thuật',
  body: 'Ghi chú nội bộ',
  versionNo: 1,
  author: 'Trần Thảo Vy',
  at: '2026-10-20T03:00:00Z',
  readOnly: false,
  visibility: 'TEAM',
  mentor: false,
  ...overrides,
})

const report = (overrides: object = {}) =>
  page({ id: 'r1', title: 'Báo cáo đánh giá', body: '## Hiện trạng', template: 'ASSESSMENT_REPORT', visibility: 'CUSTOMER', ...overrides })

const mentorJob = {
  id: 'j1',
  title: 'Đánh giá ứng dụng đặt bàn',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'ACTIVE',
  version: 12,
  jobTypes: ['ASSESSMENT'],
  selectedAt: '2026-10-18T03:00:00Z',
  codeJob: false,
  commitEmailNoticeAcknowledged: true,
  agreementConfirmed: true,
}

describe('Page templates and visibility on JOB-3 (FR-10, FR-30, FR-31)', () => {
  it('starts a Page from a template and shows its badge', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const created: unknown[] = []
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/boards/j1/pages': () => json(tree({ pages: [] })),
      'POST /api/v1/boards/j1/pages': async (request) => {
        created.push(await request.json())
        return json(page({ id: 'm1', title: 'Biên bản họp', body: '## Nội dung đã bàn', template: 'MEETING_NOTES' }), 201)
      },
      'GET /api/v1/pages/m1': () => json(page({ id: 'm1', title: 'Biên bản họp', body: '## Nội dung đã bàn', template: 'MEETING_NOTES' })),
    })
    renderAt('/cong-viec/j1/tai-lieu')

    await user.click(await screen.findByRole('button', { name: 'Trang mới' }))
    expect(await screen.findByRole('menuitem', { name: 'Trang trống' })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'Báo cáo đánh giá' })).toBeInTheDocument()
    await user.click(screen.getByRole('menuitem', { name: 'Biên bản họp' }))

    await waitFor(() => expect(created).toEqual([{ title: 'Biên bản họp', body: '', template: 'MEETING_NOTES', sensitive: 'CHECK' }]))
    expect(await screen.findByRole('heading', { level: 2, name: 'Biên bản họp' })).toBeInTheDocument()
    expect(screen.getAllByText('Biên bản họp').length).toBeGreaterThan(1)
    expect(screen.getByText('Chỉ nhóm')).toBeInTheDocument()
  })

  it('lets the Mentor switch visibility at once and puts it back when refused', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/boards/j1/pages': () => json(tree({ mentor: true })),
      'GET /api/v1/pages/p1': () => json(page({ mentor: true })),
      'PUT /api/v1/pages/p1/visibility': async (request) => {
        sent.push(await request.json())
        return json({ status: 422, code: 'GUARD_FAILED', reason: 'BOARD_READ_ONLY' }, 422)
      },
    })
    renderAt('/cong-viec/j1/tai-lieu/p1')

    const toggle = await screen.findByRole('switch', { name: 'Khách hàng xem được' })
    expect(toggle).not.toBeChecked()
    await user.click(toggle)
    await waitFor(() => expect(sent).toEqual([{ visibility: 'CUSTOMER' }]))
    expect((await screen.findAllByText('Công việc không còn đang làm nên bảng chỉ để xem.')).length).toBeGreaterThan(0)
    await waitFor(() => expect(screen.getByRole('switch', { name: 'Khách hàng xem được' })).not.toBeChecked())
  })

  it('shows the Mentor N-12 above the Assessment Report, whose switch stays on', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/boards/j1/pages': () => json(tree({ mentor: true })),
      'GET /api/v1/pages/r1': () => json(report({ mentor: true })),
    })
    renderAt('/cong-viec/j1/tai-lieu/r1')

    expect(await screen.findByText(/có thể chứa phần mềm độc hại/)).toBeInTheDocument()
    const toggle = screen.getByRole('switch', { name: 'Khách hàng xem được' })
    expect(toggle).toBeChecked()
    expect(toggle).toBeDisabled()
    expect(screen.getByText('Báo cáo đánh giá luôn để khách hàng xem.')).toBeInTheDocument()
  })

  it('shows a Crew member the badge without the switch', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/boards/j1/pages': () => json(tree()),
      'GET /api/v1/pages/p1': () => json(page()),
    })
    renderAt('/cong-viec/j1/tai-lieu/p1')

    expect(await screen.findByText('Ghi chú nội bộ')).toBeInTheDocument()
    expect(screen.getByText('Chỉ nhóm')).toBeInTheDocument()
    expect(screen.queryByRole('switch')).toBeNull()
  })

  it('gives the Customer a read-only Tài liệu tab with no history and no team notices', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/jobs/j1': () =>
        json({
          id: 'j1',
          productId: 'pr1',
          productName: 'Sổ đặt bàn Quán Mây',
          state: 'ACTIVE',
          title: 'Đánh giá ứng dụng đặt bàn',
          jobTypes: ['ASSESSMENT'],
          skills: [],
          screenshots: [],
          createdAt: '2026-10-12T03:00:00Z',
          updatedAt: '2026-10-18T03:00:00Z',
          version: 12,
          agreementShared: true,
          agreementConfirmed: true,
          canCancelSelection: false,
        }),
      'GET /api/v1/jobs/j1/chat': () => json({ chatId: 'c1', unread: false, readOnly: false }),
      'GET /api/v1/boards/j1/pages': () =>
        json(tree({ readOnly: true, customer: true, templates: [], pages: [tree().pages[0]] })),
      'GET /api/v1/pages/r1': () => json(report({ readOnly: true })),
    })
    renderAt('/cong-viec/j1/tai-lieu')

    expect(await screen.findByRole('link', { name: 'Tài liệu' })).toHaveAttribute('aria-current', 'page')
    expect(await screen.findByRole('heading', { level: 2, name: 'Hiện trạng' })).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Các trang tài liệu' })
    expect(within(nav).getAllByRole('link')).toHaveLength(1)
    expect(screen.queryByRole('button', { name: 'Lịch sử phiên bản' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Trang mới' })).toBeNull()
    expect(screen.queryByText(/phần mềm độc hại/)).toBeNull()
    expect(screen.queryByText('Khách hàng xem được')).toBeNull()
  })
})
