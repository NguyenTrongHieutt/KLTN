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
  preview: {},
  screenshots: [],
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
  sensitiveChanges: [],
  commitCount: 0,
  previewRequired: false,
  screenshots: [],
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
          screenshotIds: [],
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
    expect(within(article).getByText('Chờ bạn nghiệm thu · tự nghiệm thu 27/10')).toBeInTheDocument()
    expect(within(article).getByRole('heading', { name: 'Báo cáo đánh giá · phiên bản 3' })).toBeInTheDocument()
    expect(within(article).getByText('Ứng dụng chạy trên điện thoại.')).toBeInTheDocument()
    expect(within(article).queryByRole('heading', { name: 'Hạng mục chưa Xong khi bàn giao' })).toBeNull()
    expect(screen.queryByRole('link', { name: 'Tạo bàn giao' })).toBeNull()
  })

  it('labels a Milestone accepted automatically (FR-35)', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/handovers': () =>
        json(list({ audience: 'CUSTOMER', handovers: [{ ...list().handovers[0], status: 'ACCEPTED', auto: true }] })),
      'GET /api/v1/handovers/h1': () => json(report({ status: 'ACCEPTED', auto: true })),
    })
    renderAt('/cong-viec/j1/ban-giao/h1')

    const article = (await screen.findByRole('heading', { level: 2, name: 'Báo cáo bàn giao · Giai đoạn 1 · Báo cáo' })).closest(
      'article',
    )!
    expect(within(article).getByText('Đã tự nghiệm thu')).toBeInTheDocument()
    expect(within(screen.getByRole('navigation', { name: 'Các lần bàn giao' })).getByText('Đã tự nghiệm thu')).toBeInTheDocument()
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

  it('gives a Code Job report its Sensitive Changes, updated Pages, and inert commit list (FR-32, NFR-3)', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/jobs/j1': () => json({ ...customerJob, title: 'Sửa lỗi đặt bàn', jobTypes: ['BUG_FIX'] }),
      'GET /api/v1/boards/j1/handovers': () => json(list({ audience: 'CUSTOMER', assessment: false })),
      'GET /api/v1/handovers/h1': () =>
        json(
          report({
            assessment: false,
            report: null,
            code: {
              handoverCommit: '9c41a02' + '0'.repeat(33),
              pullNumber: 101,
              commits: PAYLOADS.map((payload, index) => ({
                sha: String(index).repeat(40),
                message: payload,
                workItemKeys: index === 0 ? ['SDB-1'] : [],
              })),
              unattributed: 1,
              truncated: false,
              sensitiveChanges: [{ path: 'package.json', kind: 'DEPENDENCIES' }],
              pages: [{ pageId: 'p2', title: 'Hướng dẫn dùng', versionNo: 2 }],
            },
          }),
        ),
    })
    renderAt('/cong-viec/j1/ban-giao/h1')

    const sensitive = (await screen.findByText('package.json')).closest('section')!
    expect(within(sensitive).getByText(/Danh sách thư viện mà ứng dụng dùng/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Hướng dẫn dùng · phiên bản 2' })).toHaveAttribute('href', '/cong-viec/j1/tai-lieu/p2')
    await user.click(screen.getByText('Chi tiết kỹ thuật'))
    expect(screen.getByText('Danh sách các lần lưu thay đổi, dành cho người hỗ trợ kỹ thuật của bạn.')).toBeInTheDocument()
    expect(screen.getByText('SDB-1')).toBeInTheDocument()
    expect(screen.getByText('Yêu cầu hợp nhất #101 trong kho trung gian')).toBeInTheDocument()
    expect(screen.getByText('1 lần lưu không gắn với tài khoản GitHub nào.')).toBeInTheDocument()
    for (const payload of PAYLOADS) {
      expect(screen.getAllByText(payload, { exact: false }).length).toBeGreaterThan(0)
    }
    expect(document.querySelector('img[src="x"], script, svg[onload]')).toBeNull()
  })

  it('shows the preview with its domain and N-11, and the screenshots (AD-35, UX-DR30)', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/boards/j1/handovers': () => json(list()),
      'GET /api/v1/handovers/h1': () =>
        json(
          report({
            audience: 'MENTOR',
            preview: {
              preview: { url: 'https://so-dat-ban-gd1.vercel.app', domain: 'so-dat-ban-gd1.vercel.app', checkedAt: '2026-10-20T01:29:00Z' },
            },
            screenshots: [{ fileId: 'f1', fileName: 'dat-ban.png', size: 1200 }],
          }),
        ),
    })
    renderAt('/cong-viec/j1/ban-giao/h1')

    const link = await screen.findByRole('link', { name: /Mở bản xem thử/ })
    expect(link).toHaveAttribute('href', 'https://so-dat-ban-gd1.vercel.app')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    expect(screen.getByText('(so-dat-ban-gd1.vercel.app)')).toBeInTheDocument()
    expect(
      screen.getByText('Trang này nằm ngoài vibefix (so-dat-ban-gd1.vercel.app). Đừng nhập mật khẩu GitHub hoặc vibefix ở đó.'),
    ).toBeInTheDocument()
    expect(screen.getByText(/^phản hồi lúc /)).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'dat-ban.png' })).toHaveAttribute('src', '/api/v1/files/f1')
  })

  it('needs a preview for a Bug fix, or a reason with a recording, and checks the link first (FR-32)', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let sent: Record<string, unknown> | undefined
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/boards/j1/handovers/preflight': () =>
        json(
          preflight({
            assessment: false,
            report: null,
            previewRequired: true,
            workItems: [{ id: 'w1', key: 'SDB-1', title: 'Chặn đặt trùng', status: 'DONE', approved: true }],
          }),
        ),
      'POST /api/v1/boards/j1/handovers/preview-check': () =>
        json({ reachable: false, domain: 'dead.vercel.app', reason: 'NO_RESPONSE' }),
      'POST /api/v1/boards/j1/handovers': async (request) => {
        sent = (await request.json()) as Record<string, unknown>
        return json(report({ assessment: false, report: null }), 201)
      },
      'GET /api/v1/handovers/h1': () => json(report({ audience: 'MENTOR', assessment: false, report: null })),
      'GET /api/v1/boards/j1/handovers': () => json(list({ assessment: false })),
    })
    renderAt('/cong-viec/j1/ban-giao/tao')

    await user.click(await screen.findByRole('radio', { name: 'Đạt' }))
    await user.type(screen.getByRole('textbox', { name: /Ghi chú cho tiêu chí 1/ }), 'Đã thử.')
    const send = screen.getByRole('button', { name: 'Gửi bàn giao' })
    expect(send).toBeDisabled()
    await user.type(screen.getByRole('textbox', { name: 'Đường dẫn bản xem thử (https)' }), 'https://dead.vercel.app')
    await user.click(screen.getByRole('button', { name: 'Kiểm tra đường dẫn' }))
    expect(await screen.findByText('Bản xem thử không phản hồi. Hãy kiểm tra đường dẫn.')).toBeInTheDocument()

    await user.click(screen.getByRole('checkbox', { name: 'Không thể làm bản xem thử' }))
    expect(send).toBeDisabled()
    await user.type(screen.getByRole('textbox', { name: 'Vì sao không làm được bản xem thử' }), 'Chỉ chạy trên máy của quán')
    await user.type(screen.getByRole('textbox', { name: 'Đường dẫn video quay màn hình (https)' }), 'https://www.loom.com/share/abc')
    await user.click(send)

    await waitFor(() => expect(sent).toBeDefined())
    expect(sent).toMatchObject({ recordingUrl: 'https://www.loom.com/share/abc', noPreviewReason: 'Chỉ chạy trên máy của quán' })
    expect(sent).not.toHaveProperty('previewUrl')
  })

  it('tells the Customer a voided handover was cancelled by a new change (FR-32, M3)', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/handovers': () => json(list({ audience: 'CUSTOMER' })),
      'GET /api/v1/handovers/h1': () => json(report({ status: 'VOIDED' })),
    })
    renderAt('/cong-viec/j1/ban-giao/h1')

    expect(await screen.findByText('Bàn giao này đã bị hủy vì có thay đổi mới được lưu sau khi bàn giao.')).toBeInTheDocument()
    expect(screen.getAllByText('Đã hủy vì có thay đổi mới').length).toBeGreaterThan(0)
  })

  it('refuses a Code Job handover that changes a workflow file with N-16', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/boards/j1/handovers/preflight': () =>
        json(
          preflight({
            assessment: false,
            report: null,
            problems: ['WORKFLOW_FILES_CHANGED'],
            commitCount: 2,
            sensitiveChanges: [{ path: 'Dockerfile', kind: 'BUILD_SCRIPT' }],
          }),
        ),
    })
    renderAt('/cong-viec/j1/ban-giao/tao')

    expect(await screen.findByText(/Không thể bàn giao: có thay đổi trong thư mục \.github\/workflows/)).toBeInTheDocument()
    expect(screen.getByText('Sẽ bàn giao 2 lần lưu thay đổi mới, đã quét bảo mật.')).toBeInTheDocument()
    expect(screen.getByText('Dockerfile')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Gửi bàn giao' })).toBeDisabled()
  })
})
