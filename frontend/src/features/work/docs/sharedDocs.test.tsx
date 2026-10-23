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

const job = {
  id: 'j2',
  productId: 'p1',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'AGREEMENT',
  title: 'Thêm đặt cọc',
  jobTypes: ['BUG_FIX'],
  skills: [],
  screenshots: [],
  createdAt: '2026-10-12T03:00:00Z',
  updatedAt: '2026-10-18T03:00:00Z',
  version: 3,
  mentor: { displayName: 'Phạm Thu Mai', githubLogin: 'maipt' },
  canCancelSelection: false,
  agreementShared: true,
}

const agreement = {
  versionNo: 1,
  sharedAt: '2026-10-20T09:20:00Z',
  versions: [{ versionNo: 1, sharedAt: '2026-10-20T09:20:00Z' }],
  quote: { priceVnd: 800000, timelineDays: 7 },
  crew: [],
  crewConfirmed: false,
  latestVersionNo: 1,
  comments: [],
  changeRequested: false,
  content: {
    scope: 'Thêm đặt cọc khi đặt bàn.',
    priceVnd: 800000,
    codeJob: true,
    assessment: false,
    skills: [],
    milestones: [
      {
        id: 'm1',
        title: 'Đặt cọc',
        priceVnd: 800000,
        dueOn: '2026-11-20',
        revisionRounds: 2,
        criteria: [{ id: 'c1', statement: 'Khách đặt cọc được', checkSteps: '1. Mở. 2. Đặt.' }],
      },
    ],
    externalAccess: [],
  },
}

const options = (shared: string[] = [], editable = true) => ({
  editable,
  jobs: [
    { jobId: 'j0', title: 'Đánh giá ứng dụng đặt bàn', state: 'CLOSED', assessmentReport: true, pages: 1, shared: shared.includes('j0') },
    { jobId: 'j1', title: 'Sửa lỗi trùng bàn', state: 'CLOSED', assessmentReport: false, pages: 3, shared: shared.includes('j1') },
  ],
})

describe('"Chia sẻ tài liệu cũ" on the Customer’s JOB-6 (FR-70, UX-DR59)', () => {
  it('offers the report first, checks nothing, and saves exactly the chosen Jobs', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const saved: unknown[] = []
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/jobs/j2': () => json(job),
      'GET /api/v1/jobs/j2/chat': () => json({ chatId: 'c1', unread: false }),
      'GET /api/v1/jobs/j2/agreement': () => json(agreement),
      'GET /api/v1/jobs/j2/page-shares': () => json(options()),
      'PUT /api/v1/jobs/j2/page-shares': async (request) => {
        const body = (await request.json()) as { jobIds: string[] }
        saved.push(body)
        return json(options(body.jobIds))
      },
    })
    renderAt('/cong-viec/j2/thoa-thuan')

    const section = (await screen.findByRole('heading', { name: 'Chia sẻ tài liệu cũ' })).closest('section') as HTMLElement
    const boxes = within(section).getAllByRole('checkbox')
    expect(boxes).toHaveLength(2)
    expect(boxes[0]).toHaveAccessibleName(/Đánh giá ứng dụng đặt bàn/)
    expect(within(section).getByText('Báo cáo đánh giá')).toBeInTheDocument()
    expect(boxes.every((box) => !(box as HTMLInputElement).checked)).toBe(true)
    expect(within(section).getByRole('button', { name: 'Lưu chia sẻ' })).toBeDisabled()

    await user.click(boxes[0])
    await user.click(within(section).getByRole('button', { name: 'Lưu chia sẻ' }))
    await waitFor(() => expect(saved).toEqual([{ jobIds: ['j0'] }]))
    await waitFor(() => expect(within(section).getAllByRole('checkbox')[0]).toBeChecked())
  })

  it('shows only what is shared, read-only, once the Job can no longer change it', async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/jobs/j2': () => json({ ...job, state: 'CLOSED' }),
      'GET /api/v1/jobs/j2/chat': () => json({ chatId: 'c1', unread: false }),
      'GET /api/v1/jobs/j2/agreement': () => json(agreement),
      'GET /api/v1/jobs/j2/page-shares': () => json(options(['j1'], false)),
    })
    renderAt('/cong-viec/j2/thoa-thuan')

    const section = (await screen.findByRole('heading', { name: 'Chia sẻ tài liệu cũ' })).closest('section') as HTMLElement
    const boxes = within(section).getAllByRole('checkbox')
    expect(boxes).toHaveLength(1)
    expect(boxes[0]).toBeDisabled()
    expect(within(section).queryByRole('button', { name: 'Lưu chia sẻ' })).toBeNull()
  })
})

describe('"Tài liệu từ công việc trước" for the Mentor and the active Crew (FR-70)', () => {
  const shared = [
    {
      jobId: 'j0',
      title: 'Đánh giá ứng dụng đặt bàn',
      pages: [{ id: 'pg1', title: 'Báo cáo đánh giá', versionNo: 2, updatedAt: '2026-10-01T03:00:00Z', template: 'ASSESSMENT_REPORT', visibility: 'CUSTOMER' }],
    },
  ]
  const page = {
    id: 'pg1',
    boardId: 'j0',
    title: 'Báo cáo đánh giá',
    body: '## Hiện trạng\nỨng dụng chạy tốt.',
    versionNo: 2,
    author: 'Đỗ Mạnh Hùng',
    at: '2026-10-01T03:00:00Z',
    readOnly: true,
    template: 'ASSESSMENT_REPORT',
    visibility: 'CUSTOMER',
    mentor: false,
    shared: true,
  }

  it('lists the shared Jobs and shows the first Page read-only, without history', async () => {
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/jobs/j2/shared-pages': () => json(shared),
      'GET /api/v1/pages/pg1': () => json(page),
    })
    renderAt('/cong-viec/j2/tai-lieu-cu')

    expect(await screen.findByRole('heading', { level: 1, name: 'Tài liệu từ công việc trước' })).toBeInTheDocument()
    const nav = await screen.findByRole('navigation', { name: 'Tài liệu từ công việc trước' })
    expect(within(nav).getByText('Đánh giá ứng dụng đặt bàn')).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: 'Báo cáo đánh giá' })).toHaveAttribute('aria-current', 'page')
    expect(await screen.findByText('Ứng dụng chạy tốt.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Lịch sử phiên bản/ })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Sửa' })).toBeNull()
  })

  it('shows the not-found screen to anyone the Job does not share with', async () => {
    fakeBackend(signedIn('STUDENT'), {})
    renderAt('/cong-viec/j2/tai-lieu-cu')

    expect(await screen.findByRole('heading', { name: /Không tìm thấy/ })).toBeInTheDocument()
  })

  it('links from the Mentor’s JOB-6 when something is shared', { timeout: 15_000 }, async () => {
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/mentor/jobs/j2': () => json({ id: 'j2', title: 'Thêm đặt cọc', productName: 'Sổ đặt bàn Quán Mây', state: 'AGREEMENT', version: 3, jobTypes: ['BUG_FIX'], selectedAt: '2026-10-18T03:00:00Z', codeJob: true, commitEmailNoticeAcknowledged: true }),
      'GET /api/v1/jobs/j2/shared-pages': () => json(shared),
    })
    renderAt('/cong-viec/j2/thoa-thuan')

    expect(await screen.findByRole('link', { name: 'Tài liệu từ công việc trước (1)' })).toHaveAttribute(
      'href',
      '/cong-viec/j2/tai-lieu-cu',
    )
  })
})
