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

afterEach(() => {
  vi.unstubAllGlobals()
})

const customerJob = {
  id: 'j1',
  productId: 'pr1',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'ACTIVE',
  title: 'Đánh giá sổ đặt bàn',
  jobTypes: ['ASSESSMENT'],
  skills: [],
  screenshots: [],
  createdAt: '2026-10-12T03:00:00Z',
  updatedAt: '2026-10-18T03:00:00Z',
  version: 12,
  agreementShared: true,
  agreementConfirmed: true,
  canCancelSelection: false,
}

const mentorJob = {
  id: 'j1',
  title: 'Đánh giá sổ đặt bàn',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'ACTIVE',
  version: 12,
  jobTypes: ['ASSESSMENT'],
  selectedAt: '2026-10-18T03:00:00Z',
  codeJob: false,
  commitEmailNoticeAcknowledged: true,
  agreementConfirmed: true,
}

const list = (overrides: object = {}) => ({
  jobId: 'j1',
  title: 'Đánh giá sổ đặt bàn',
  audience: 'MENTOR',
  assessment: true,
  handovers: [
    {
      id: 'h1',
      number: 1,
      milestoneTitle: 'Báo cáo',
      round: 1,
      status: 'SUBMITTED',
      createdAt: '2026-10-20T03:00:00Z',
      checkBy: '2026-10-27T03:00:00Z',
    },
  ],
  ...overrides,
})

const report = (overrides: object = {}) => ({
  id: 'h1',
  jobId: 'j1',
  jobTitle: 'Đánh giá sổ đặt bàn',
  number: 1,
  milestoneTitle: 'Báo cáo',
  round: 1,
  status: 'SUBMITTED',
  mentorName: 'Đỗ Mạnh Hùng',
  createdAt: '2026-10-20T03:00:00Z',
  checkBy: '2026-10-27T03:00:00Z',
  assessment: true,
  audience: 'CUSTOMER',
  criteria: [
    {
      criterionId: 'c1',
      number: 1,
      statement: 'Báo cáo nêu đủ chín mục',
      checkSteps: 'Mở báo cáo, đếm các mục',
      met: true,
      note: 'Đã rà đủ chín mục.',
    },
  ],
  itemNotes: [],
  report: { pageId: 'p1', versionNo: 3, title: 'Báo cáo đánh giá', body: '## Hiện trạng\n\nỨng dụng chạy trên điện thoại.' },
  version: 13,
  roundsLeft: 2,
  checks: [],
  ...overrides,
})

const preflight = (overrides: object = {}) => ({
  milestone: { milestoneId: 'm1', number: 1, title: 'Báo cáo' },
  state: 'FUNDED',
  version: 12,
  assessment: true,
  criteria: [{ criterionId: 'c1', number: 1, statement: 'Báo cáo nêu đủ chín mục', checkSteps: 'Mở báo cáo, đếm các mục' }],
  workItems: [
    { id: 'w1', key: 'BCD-1', title: 'Báo cáo nêu đủ chín mục', status: 'DONE', approved: true },
    { id: 'w2', key: 'BCD-2', title: 'Phụ lục thuật ngữ', status: 'IN_PROGRESS', approved: false },
  ],
  report: { pageId: 'p1', versionNo: 3, title: 'Báo cáo đánh giá' },
  problems: [],
  ...overrides,
})

describe('JOB-5 handovers (FR-10, FR-32, UX-DR53, UX-DR54)', () => {
  it('lets the Mentor hand over only once every item and criterion is accounted for', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/boards/j1/handovers': () =>
        json(list({ handovers: [], waiting: { milestoneId: 'm1', number: 1, title: 'Báo cáo' } })),
      'GET /api/v1/boards/j1/handovers/preflight': () => json(preflight()),
      'POST /api/v1/boards/j1/handovers': async (request) => {
        sent.push(await request.json())
        return json(report({ audience: 'MENTOR' }), 201)
      },
      'GET /api/v1/handovers/h1': () => json(report({ audience: 'MENTOR' })),
    })
    renderAt('/cong-viec/j1/ban-giao')

    expect(await screen.findByText('Giai đoạn 1 · Báo cáo đang chờ bàn giao.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Bàn giao' })).toHaveAttribute('aria-current', 'page')
    await user.click(screen.getByRole('link', { name: 'Tạo bàn giao' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Bàn giao Giai đoạn 1 · Báo cáo' })).toBeInTheDocument()
    expect(screen.getByText('Đã duyệt')).toBeInTheDocument()
    expect(screen.getByText(/Bàn giao Báo cáo đánh giá ở phiên bản 3/)).toBeInTheDocument()
    const send = screen.getByRole('button', { name: 'Gửi bàn giao' })
    expect(send).toBeDisabled()
    await user.type(screen.getByRole('textbox', { name: 'Vì sao BCD-2 chưa Xong' }), 'Làm ở giai đoạn sau.')
    await user.click(screen.getByRole('radio', { name: 'Đạt' }))
    expect(send).toBeDisabled()
    await user.type(screen.getByRole('textbox', { name: 'Ghi chú cho tiêu chí 1' }), 'Đã rà đủ chín mục.')
    expect(send).toBeEnabled()
    await user.click(send)

    await waitFor(() =>
      expect(sent).toEqual([
        {
          milestoneId: 'm1',
          version: 12,
          criteria: [{ criterionId: 'c1', met: true, note: 'Đã rà đủ chín mục.' }],
          itemNotes: [{ workItemId: 'w2', note: 'Làm ở giai đoạn sau.' }],
          sensitive: 'CHECK',
        },
      ]),
    )
    expect(await screen.findByRole('heading', { level: 2, name: 'Báo cáo bàn giao · Giai đoạn 1 · Báo cáo' })).toBeInTheDocument()
  })

  it('shows a refusal the preflight finds and keeps "Gửi bàn giao" off', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/boards/j1/handovers/preflight': () => json(preflight({ report: null, problems: ['REPORT_MISSING'] })),
    })
    renderAt('/cong-viec/j1/ban-giao/tao')

    expect(await screen.findByRole('alert')).toHaveTextContent('Công việc đánh giá cần có báo cáo đánh giá trước khi bàn giao.')
    expect(screen.getByRole('button', { name: 'Gửi bàn giao' })).toBeDisabled()
  })

  it('gives the Customer the criteria with the Mentor’s own marks, the check steps, and the Report', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/handovers': () => json(list({ audience: 'CUSTOMER' })),
      'GET /api/v1/handovers/h1': () => json(report()),
    })
    renderAt('/cong-viec/j1/ban-giao/h1')

    const article = (await screen.findByRole('heading', { level: 2, name: 'Báo cáo bàn giao · Giai đoạn 1 · Báo cáo' })).closest(
      'article',
    )!
    expect(within(article).getByText('Người hướng dẫn tự nhận xét: Đạt')).toBeInTheDocument()
    expect(within(article).getByText('Đã rà đủ chín mục.')).toBeInTheDocument()
    expect(within(article).getByText('Mở báo cáo, đếm các mục')).toBeInTheDocument()
    expect(within(article).getByText('Chờ bạn nghiệm thu · trước 27/10')).toBeInTheDocument()
    expect(within(article).getByRole('heading', { name: 'Báo cáo đánh giá · phiên bản 3' })).toBeInTheDocument()
    expect(within(article).getByText('Ứng dụng chạy trên điện thoại.')).toBeInTheDocument()
    expect(within(article).queryByRole('heading', { name: 'Hạng mục chưa Xong khi bàn giao' })).toBeNull()
    expect(screen.queryByRole('link', { name: 'Tạo bàn giao' })).toBeNull()
  })

  it('lets a Crew member read the report, with the item notes, and nothing more', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/boards/j1/handovers': () => json(list({ audience: 'CREW' })),
      'GET /api/v1/handovers/h1': () =>
        json(
          report({
            audience: 'CREW',
            itemNotes: [{ workItemId: 'w2', key: 'BCD-2', title: 'Phụ lục thuật ngữ', status: 'IN_PROGRESS', note: 'Làm sau.' }],
          }),
        ),
    })
    renderAt('/cong-viec/j1/ban-giao/h1')

    expect(await screen.findByText('Bạn xem được các lần bàn giao; chỉ người hướng dẫn tạo bàn giao.')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Hạng mục chưa Xong khi bàn giao' })).toBeInTheDocument()
    expect(screen.getByText('Làm sau.')).toBeInTheDocument()
    expect(screen.getByText('Chờ khách nghiệm thu', { selector: 'p' })).toBeInTheDocument()
  })

  it('keeps planted payloads in notes and the Report as inert text (SG-30)', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/handovers': () => json(list({ audience: 'CUSTOMER' })),
      'GET /api/v1/handovers/h1': () =>
        json(
          report({
            criteria: PAYLOADS.map((payload, index) => ({
              criterionId: `c${index}`,
              number: index + 1,
              statement: payload,
              checkSteps: payload,
              met: index % 2 === 0,
              note: payload,
            })),
            report: { pageId: 'p1', versionNo: 1, title: 'Báo cáo đánh giá', body: PAYLOADS.join('\n\n') },
          }),
        ),
    })
    renderAt('/cong-viec/j1/ban-giao/h1')

    await screen.findByRole('heading', { level: 2, name: 'Báo cáo bàn giao · Giai đoạn 1 · Báo cáo' })
    for (const payload of PAYLOADS) {
      expect(screen.getAllByText(payload, { exact: false }).length).toBeGreaterThan(0)
    }
    expect(document.querySelector('img[src="x"], script, svg[onload]')).toBeNull()
    for (const anchor of Array.from(document.querySelectorAll('a'))) {
      expect(anchor.getAttribute('href') ?? '').not.toMatch(/^javascript:/i)
    }
  })
})
