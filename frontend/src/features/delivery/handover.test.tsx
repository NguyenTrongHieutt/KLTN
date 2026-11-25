import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { MAX_UPLOAD_BYTES } from '@/features/files/uploadRules'
import { fakeBackend, json, signedInUser, inEveryJob } from '@/test/fakeBackend'
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

/** The pending submission a Code Job's "Gửi bàn giao" answers with (Stories 23.8a and 23.8b). */
const submission = (overrides: object = {}) => ({
  id: 's1',
  jobId: 'j1',
  milestoneId: 'm1',
  number: 1,
  state: 'PENDING',
  reason: null,
  reasonModule: null,
  handoverId: null,
  runStatus: null,
  runConclusion: null,
  ...overrides,
})

/** N-33's Mentor checkbox, which a Code Job's "Gửi bàn giao" waits for (FR-25, R28, Story 23.8b). */
const teamCode = () => screen.getByRole('checkbox', { name: /vibefix không quét mã của nhóm/ })

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
  version: 13,
  roundsLeft: 2,
  checks: [],
  preview: {},
  screenshots: [],
  links: [],
  ...overrides,
})

const preflight = (overrides: object = {}) => ({
  milestone: { milestoneId: 'm1', number: 1, title: 'Báo cáo' },
  state: 'FUNDED',
  version: 12,
  assessment: true,
  criteria: [{ criterionId: 'c1', number: 1, statement: 'Báo cáo nêu đủ chín mục', checkSteps: 'Mở báo cáo, đếm các mục' }],
  problems: [],
  sensitiveChanges: [],
  commitCount: 0,
  previewRequired: false,
  screenshots: [],
  ...overrides,
})

describe('JOB-5 handovers (FR-10, FR-32, UX-DR53, UX-DR54)', () => {
  it('lets the Mentor hand over only once every criterion is marked and the report is linked', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/boards/j1/handovers': () =>
        json(list({ handovers: [], waiting: { milestoneId: 'm1', number: 1, title: 'Báo cáo' } })),
      'GET /api/v1/boards/j1/handovers/preflight': () => json(preflight()),
      // Story 23.8a: the answer is the submission, done at once for an Assessment Job.
      'POST /api/v1/boards/j1/handovers': async (request) => {
        sent.push(await request.json())
        return json(
          { id: 'h1', jobId: 'j1', milestoneId: 'm1', number: 1, state: 'DONE', reason: null, handoverId: 'h1' },
          202,
        )
      },
      'GET /api/v1/handovers/h1': () => json(report({ audience: 'MENTOR' })),
    })
    renderAt('/cong-viec/j1/ban-giao')

    // The first test of this file loads the lazy route, which takes more than the default second under a full build.
    expect(await screen.findByText('Giai đoạn 1 · Báo cáo đang chờ bàn giao.', {}, { timeout: 15_000 })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Bàn giao' })).toHaveAttribute('aria-current', 'page')
    await user.click(screen.getByRole('link', { name: 'Tạo bàn giao' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Bàn giao Giai đoạn 1 · Báo cáo' })).toBeInTheDocument()
    // Story 24.2: the form holds the criteria alone, and names no Work Item at all.
    expect(screen.queryByRole('heading', { level: 2, name: 'Hạng mục của giai đoạn' })).toBeNull()
    const send = screen.getByRole('button', { name: 'Gửi bàn giao' })
    expect(send).toBeDisabled()
    await user.click(screen.getByRole('radio', { name: 'Đạt' }))
    expect(send).toBeDisabled()
    await user.type(screen.getByRole('textbox', { name: 'Ghi chú cho tiêu chí 1' }), 'Đã rà đủ chín mục.')
    // Story 24.3 (FR-10): an Assessment Job changes no running Product, so it has no preview part, and its report
    // goes in as a "Tài liệu" link: the form starts with that row, and waits for it.
    expect(screen.queryByRole('textbox', { name: 'Đường dẫn preview (https)' })).toBeNull()
    expect(screen.getByText(/Gửi báo cáo đánh giá dưới dạng một liên kết loại "Tài liệu"/)).toBeInTheDocument()
    const row = screen.getByRole('group', { name: 'Liên kết 1' })
    expect(within(row).getByRole('combobox', { name: 'Loại' })).toHaveValue('DOCUMENT')
    expect(send).toBeDisabled()
    await user.type(within(row).getByRole('textbox', { name: 'Tên liên kết' }), 'Báo cáo đánh giá')
    await user.type(within(row).getByRole('textbox', { name: 'Đường dẫn (https)' }), 'https://docs.google.com/document/d/bao-cao')
    expect(within(row).getByText('Tên miền: docs.google.com')).toBeInTheDocument()
    expect(send).toBeEnabled()
    // A recording is not the report: with no "Tài liệu" link the handover cannot go.
    await user.selectOptions(within(row).getByRole('combobox', { name: 'Loại' }), 'VIDEO')
    expect(send).toBeDisabled()
    await user.selectOptions(within(row).getByRole('combobox', { name: 'Loại' }), 'DOCUMENT')
    expect(send).toBeEnabled()
    await user.click(send)

    await waitFor(() =>
      expect(sent).toEqual([
        {
          milestoneId: 'm1',
          version: 12,
          criteria: [{ criterionId: 'c1', met: true, note: 'Đã rà đủ chín mục.' }],
          links: [{ kind: 'DOCUMENT', label: 'Báo cáo đánh giá', url: 'https://docs.google.com/document/d/bao-cao' }],
          screenshotIds: [],
        },
      ]),
    )
    expect(await screen.findByRole('heading', { level: 2, name: 'Báo cáo bàn giao · Giai đoạn 1 · Báo cáo' })).toBeInTheDocument()
  })

  /** D5 (Story 24.4b): nothing checks or masks a note, so one holding a key-like value goes once, as typed. */
  it('sends a note holding a key-like value once, as typed, with no warning', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: { criteria: { note: string }[] }[] = []
    // Built at run time, so no credential shape sits in this source.
    const note = `Khóa thử ${'sk' + '_test_' + 'ab'.repeat(12)} vẫn nằm trong ghi chú.`
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/boards/j1/handovers': () => json(list()),
      'GET /api/v1/boards/j1/handovers/preflight': () => json(preflight()),
      'POST /api/v1/boards/j1/handovers': async (request) => {
        sent.push(await request.json())
        return json(
          { id: 'h1', jobId: 'j1', milestoneId: 'm1', number: 1, state: 'DONE', reason: null, handoverId: 'h1' },
          202,
        )
      },
      'GET /api/v1/handovers/h1': () =>
        json(report({ audience: 'MENTOR', criteria: [{ ...report().criteria[0], note }] })),
    })
    renderAt('/cong-viec/j1/ban-giao/tao')

    await user.click(await screen.findByRole('radio', { name: 'Đạt' }))
    await user.type(screen.getByRole('textbox', { name: 'Ghi chú cho tiêu chí 1' }), note)
    const row = screen.getByRole('group', { name: 'Liên kết 1' })
    await user.type(within(row).getByRole('textbox', { name: 'Tên liên kết' }), 'Báo cáo đánh giá')
    await user.type(within(row).getByRole('textbox', { name: 'Đường dẫn (https)' }), 'https://docs.google.com/document/d/bao-cao')
    await user.click(screen.getByRole('button', { name: 'Gửi bàn giao' }))

    expect(await screen.findByRole('heading', { level: 2, name: 'Báo cáo bàn giao · Giai đoạn 1 · Báo cáo' })).toBeInTheDocument()
    expect(sent).toHaveLength(1)
    expect(sent[0].criteria[0].note).toBe(note)
    expect(sent[0]).not.toHaveProperty('sensitive')
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.getByText(note)).toBeInTheDocument()
  })

  it('shows a refusal the preflight finds and keeps "Gửi bàn giao" off', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/boards/j1/handovers/preflight': () =>
        json(preflight({ state: 'PLANNED', problems: ['MILESTONE_NOT_HELD'] })),
    })
    renderAt('/cong-viec/j1/ban-giao/tao')

    expect(await screen.findByRole('alert')).toHaveTextContent('Giai đoạn này chưa được thanh toán hoặc đã bàn giao.')
    expect(screen.getByRole('button', { name: 'Gửi bàn giao' })).toBeDisabled()
  })

  it('gives the Customer the criteria with the Mentor’s own marks and the check steps', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
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
    expect(within(article).queryByRole('heading', { name: 'Hạng mục chưa Xong khi bàn giao' })).toBeNull()
    expect(screen.queryByRole('link', { name: 'Tạo bàn giao' })).toBeNull()
  })

  it('labels a Milestone accepted automatically (FR-35)', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
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

  /** Story 24.2: every audience reads the same Report -- the criteria with the Mentor's marks and notes. */
  it('lets a Crew member read the report, with the criteria, and nothing more', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CREW'),
      'GET /api/v1/boards/j1/handovers': () => json(list({ audience: 'CREW' })),
      'GET /api/v1/handovers/h1': () => json(report({ audience: 'CREW' })),
    })
    renderAt('/cong-viec/j1/ban-giao/h1')

    expect(await screen.findByText('Bạn xem được các lần bàn giao; chỉ người hướng dẫn tạo bàn giao.')).toBeInTheDocument()
    expect(await screen.findByText('Đã rà đủ chín mục.')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Hạng mục chưa Xong khi bàn giao' })).toBeNull()
    expect(screen.getByText('Chờ khách nghiệm thu', { selector: 'header span' })).toBeInTheDocument()
  })

  it('keeps planted payloads in notes and the Report as inert text (SG-30)', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
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

  it('gives a Code Job report its Sensitive Changes and inert commit list (FR-32, NFR-3)', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json({ ...customerJob, title: 'Sửa lỗi đặt bàn', jobTypes: ['BUG_FIX'] }),
      'GET /api/v1/boards/j1/handovers': () => json(list({ audience: 'CUSTOMER', assessment: false })),
      'GET /api/v1/handovers/h1': () =>
        json(
          report({
            assessment: false,
            // The handover still waits for the Customer: N-33 stands above the decision it is about.
            decision: { releaseVnd: 1_000_000, revisionAllowed: true },
            code: {
              handoverCommit: '9c41a02' + '0'.repeat(33),
              pullNumber: 101,
              commits: PAYLOADS.map((payload, index) => ({
                sha: String(index).repeat(40),
                message: payload,
              })),
              unattributed: 1,
              truncated: false,
              sensitiveChanges: [{ path: 'package.json', kind: 'DEPENDENCIES' }],
              diff: {
                url: 'https://github.com/vibefix-dev/job-1-intermediary/pull/101/files',
                domain: 'github.com',
                checkedAt: null,
              },
              branch: {
                url: 'https://github.com/vibefix-dev/job-1-intermediary/tree/handover/h1',
                domain: 'github.com',
                checkedAt: null,
              },
              teamCodeConfirmedAt: '2026-10-20T02:55:00Z',
            },
          }),
        ),
    })
    renderAt('/cong-viec/j1/ban-giao/h1')

    const sensitive = (await screen.findByText('package.json')).closest('section')!
    expect(within(sensitive).getByText(/Danh sách dependency mà ứng dụng dùng/)).toBeInTheDocument()
    // N-33 (FR-33, R28, Story 23.8b): the Customer reads it above their decision, and never ticks it.
    expect(
      screen.getByText(/vibefix không quét mã của nhóm\. Người hướng dẫn Đỗ Mạnh Hùng đã xác nhận/),
    ).toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
    await user.click(screen.getByText('Chi tiết kỹ thuật'))
    expect(screen.getByText('Danh sách các commit, dành cho người hỗ trợ kỹ thuật của bạn.')).toBeInTheDocument()
    expect(screen.getByText('Yêu cầu merge #101 trong repo trung gian')).toBeInTheDocument()
    expect(screen.getByText('1 commit không gắn với tài khoản GitHub nào.')).toBeInTheDocument()
    // FR-32 (Story 23.8b): the two GitHub links, each with its domain, and the Customer's invitation line.
    expect(screen.getByRole('link', { name: /Xem thay đổi trên GitHub/ })).toHaveAttribute(
      'href',
      'https://github.com/vibefix-dev/job-1-intermediary/pull/101/files',
    )
    expect(screen.getByRole('link', { name: /Xem nhánh bàn giao/ })).toHaveAttribute(
      'href',
      'https://github.com/vibefix-dev/job-1-intermediary/tree/handover/h1',
    )
    expect(screen.getAllByText('(github.com)')).toHaveLength(2)
    expect(
      screen.getByText('Hai liên kết trên chỉ mở được sau khi bạn chấp nhận lời mời đọc repo trung gian trên GitHub.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Mở lời mời đọc repo trung gian' })).toHaveAttribute(
      'href',
      '/cong-viec/j1/kho-trung-gian',
    )
    for (const payload of PAYLOADS) {
      expect(screen.getAllByText(payload, { exact: false }).length).toBeGreaterThan(0)
    }
    expect(document.querySelector('img[src="x"], script, svg[onload]')).toBeNull()
  })

  it('shows the preview and every link with its kind, domain and N-11, and the screenshots (AD-35, UX-DR30)', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
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
            links: [
              { kind: 'VIDEO', label: 'Video thao tác đặt bàn', url: 'https://youtu.be/dat-ban', domain: 'youtu.be' },
              {
                kind: 'DOCUMENT',
                label: 'Ghi chú phát hành',
                url: 'https://docs.google.com/document/d/ghi-chu',
                domain: 'docs.google.com',
              },
            ],
          }),
        ),
    })
    renderAt('/cong-viec/j1/ban-giao/h1')

    // Story 24.3: every link in the Mentor's order, with its kind, its label as the link's text, its domain and N-11.
    const video = await screen.findByRole('link', { name: /Video thao tác đặt bàn/ })
    expect(video).toHaveAttribute('href', 'https://youtu.be/dat-ban')
    expect(video).toHaveAttribute('target', '_blank')
    expect(video).toHaveAttribute('rel', 'noopener noreferrer')
    const notes = screen.getByRole('link', { name: /Ghi chú phát hành/ })
    expect(notes).toHaveAttribute('href', 'https://docs.google.com/document/d/ghi-chu')
    expect(notes).toHaveAttribute('rel', 'noopener noreferrer')
    expect(video.compareDocumentPosition(notes) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.getByText('Bản ghi / video')).toBeInTheDocument()
    expect(screen.getByText('Tài liệu')).toBeInTheDocument()
    expect(screen.getByText('(youtu.be)')).toBeInTheDocument()
    expect(screen.getByText('(docs.google.com)')).toBeInTheDocument()
    expect(
      screen.getByText('Trang này nằm ngoài vibefix (docs.google.com). Đừng nhập mật khẩu GitHub hoặc vibefix ở đó.'),
    ).toBeInTheDocument()

    const link = screen.getByRole('link', { name: /Mở preview/ })
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

  it('needs a preview for a Bug fix, or a reason with a video link, and checks the preview first (FR-32)', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let sent: Record<string, unknown> | undefined
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/boards/j1/handovers/preflight': () =>
        json(
          preflight({
            assessment: false,
            previewRequired: true,
          }),
        ),
      'POST /api/v1/boards/j1/handovers/preview-check': () =>
        json({ reachable: false, domain: 'dead.vercel.app', reason: 'NO_RESPONSE' }),
      // Story 23.8a: a Code Job's handover waits for the runner, so the answer is its pending submission.
      'POST /api/v1/boards/j1/handovers': async (request) => {
        sent = (await request.json()) as Record<string, unknown>
        return json(submission(), 202)
      },
      'GET /api/v1/boards/j1/handovers/submission': () => json(submission()),
      'GET /api/v1/handovers/h1': () => json(report({ audience: 'MENTOR', assessment: false })),
      'GET /api/v1/boards/j1/handovers': () => json(list({ assessment: false })),
    })
    renderAt('/cong-viec/j1/ban-giao/tao')

    await user.click(await screen.findByRole('radio', { name: 'Đạt' }))
    await user.type(screen.getByRole('textbox', { name: /Ghi chú cho tiêu chí 1/ }), 'Đã thử.')
    const send = screen.getByRole('button', { name: 'Gửi bàn giao' })
    expect(send).toBeDisabled()
    await user.type(screen.getByRole('textbox', { name: 'Đường dẫn preview (https)' }), 'https://dead.vercel.app')
    await user.click(screen.getByRole('button', { name: 'Kiểm tra đường dẫn' }))
    expect(await screen.findByText('Preview không phản hồi. Hãy kiểm tra đường dẫn.')).toBeInTheDocument()

    await user.click(screen.getByRole('checkbox', { name: 'Không thể làm preview' }))
    expect(send).toBeDisabled()
    await user.type(screen.getByRole('textbox', { name: 'Vì sao không làm được preview' }), 'Chỉ chạy trên máy của quán')
    expect(screen.getByText(/Thêm video quay màn hình vào phần Liên kết kèm bàn giao bên dưới/)).toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: 'Đường dẫn video quay màn hình (https)' })).toBeNull()
    await user.click(teamCode())
    expect(send).toBeDisabled()
    // Story 24.3: the recording is one of the links, of kind "Bản ghi / video"; a document is not a recording.
    await user.click(screen.getByRole('button', { name: 'Thêm liên kết' }))
    const row = screen.getByRole('group', { name: 'Liên kết 1' })
    await user.selectOptions(within(row).getByRole('combobox', { name: 'Loại' }), 'DOCUMENT')
    await user.type(within(row).getByRole('textbox', { name: 'Tên liên kết' }), 'Quay lại thao tác')
    await user.type(within(row).getByRole('textbox', { name: 'Đường dẫn (https)' }), 'https://www.loom.com/share/abc')
    expect(send).toBeDisabled()
    await user.selectOptions(within(row).getByRole('combobox', { name: 'Loại' }), 'VIDEO')
    expect(send).toBeEnabled()
    await user.click(send)

    await waitFor(() => expect(sent).toBeDefined())
    expect(sent).toMatchObject({
      noPreviewReason: 'Chỉ chạy trên máy của quán',
      links: [{ kind: 'VIDEO', label: 'Quay lại thao tác', url: 'https://www.loom.com/share/abc' }],
    })
    // No preview, and no field of the recording's own: the request carries exactly these.
    expect(Object.keys(sent ?? {}).sort()).toEqual([
      'criteria',
      'links',
      'milestoneId',
      'noPreviewReason',
      'screenshotIds',
      'teamCodeNoticeVersion',
      'version',
    ])
  })

  it('shows each link row’s domain once it is https, stops at ten links, and waits for every row (Story 24.3)', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/boards/j1/handovers/preflight': () => json(preflight({ assessment: false })),
    })
    renderAt('/cong-viec/j1/ban-giao/tao')

    await user.click(await screen.findByRole('radio', { name: 'Đạt' }))
    await user.type(screen.getByRole('textbox', { name: /Ghi chú cho tiêu chí 1/ }), 'Đã thử.')
    await user.click(teamCode())
    const send = screen.getByRole('button', { name: 'Gửi bàn giao' })
    expect(send).toBeEnabled()
    expect(screen.getByText('Chưa có liên kết nào.')).toBeInTheDocument()

    const add = screen.getByRole('button', { name: 'Thêm liên kết' })
    await user.click(add)
    const row = screen.getByRole('group', { name: 'Liên kết 1' })
    // The new row's first field takes focus, so a keyboard user carries on in it.
    expect(within(row).getByRole('combobox', { name: 'Loại' })).toHaveFocus()
    expect(within(row).getByRole('combobox', { name: 'Loại' })).toHaveValue('VIDEO')
    expect(send).toBeDisabled()
    await user.type(within(row).getByRole('textbox', { name: 'Tên liên kết' }), 'Video thao tác')
    await user.type(within(row).getByRole('textbox', { name: 'Đường dẫn (https)' }), 'http://youtu.be/dat-ban')
    expect(within(row).queryByText(/^Tên miền:/)).toBeNull()
    // A URL the server would refuse says why, in place of the domain.
    expect(within(row).getByText('Hãy dán đường dẫn đầy đủ bắt đầu bằng https://.')).toBeInTheDocument()
    expect(within(row).getByRole('textbox', { name: 'Đường dẫn (https)' })).toHaveAttribute('aria-invalid', 'true')
    expect(send).toBeDisabled()
    const url = within(row).getByRole('textbox', { name: 'Đường dẫn (https)' })
    await user.clear(url)
    await user.type(url, 'https://YouTu.be:443/dat-ban?t=3')
    expect(within(row).getByText('Tên miền: youtu.be')).toBeInTheDocument()
    expect(within(row).queryByText('Hãy dán đường dẫn đầy đủ bắt đầu bằng https://.')).toBeNull()
    expect(send).toBeEnabled()

    for (let n = 2; n <= 10; n++) {
      await user.click(add)
    }
    expect(screen.getAllByRole('group', { name: /^Liên kết \d+$/ })).toHaveLength(10)
    expect(within(screen.getByRole('group', { name: 'Liên kết 10' })).getByRole('combobox', { name: 'Loại' })).toHaveFocus()
    expect(add).toBeDisabled()
    expect(screen.getByText('Đã đủ 10 liên kết.')).toBeInTheDocument()
    expect(send).toBeDisabled()
    for (let n = 10; n >= 2; n--) {
      await user.click(screen.getByRole('button', { name: `Xóa liên kết ${n}` }))
      // The removed row's button is gone, so focus goes to "Thêm liên kết", never to the page itself.
      expect(add).toHaveFocus()
    }
    expect(add).toBeEnabled()
    expect(screen.queryByText('Đã đủ 10 liên kết.')).toBeNull()
    expect(send).toBeEnabled()
    await user.click(screen.getByRole('button', { name: 'Xóa liên kết 1' }))
    expect(screen.getByText('Chưa có liên kết nào.')).toBeInTheDocument()
    expect(add).toHaveFocus()
  })

  it('keeps a link typed while an image uploads when the upload answers (Story 24.3)', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let uploaded = false
    let answer: (response: Response) => void = () => undefined
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/boards/j1/handovers/preflight': () =>
        json(
          preflight({
            assessment: false,
            screenshots: uploaded ? [{ fileId: 'f9', fileName: 'dat-ban.png', size: 1200 }] : [],
          }),
        ),
      // The upload's answer is held back until the Mentor has typed.
      'POST /api/v1/boards/j1/handovers/screenshots': () =>
        new Promise<Response>((resolve) => {
          answer = resolve
        }),
    })
    renderAt('/cong-viec/j1/ban-giao/tao')

    await user.click(await screen.findByRole('button', { name: 'Thêm liên kết' }))
    const row = screen.getByRole('group', { name: 'Liên kết 1' })
    await user.type(within(row).getByRole('textbox', { name: 'Tên liên kết' }), 'Video')
    const file = document.querySelector<HTMLInputElement>('input[type="file"]')!
    await user.upload(file, new File(['png'], 'dat-ban.png', { type: 'image/png' }))
    await user.type(within(row).getByRole('textbox', { name: 'Tên liên kết' }), ' thao tác')
    await user.type(within(row).getByRole('textbox', { name: 'Đường dẫn (https)' }), 'https://youtu.be/dat-ban')

    uploaded = true
    answer(json({ fileId: 'f9', fileName: 'dat-ban.png', size: 1200 }, 201))

    expect(await screen.findByRole('checkbox', { name: 'Gửi kèm ảnh dat-ban.png' })).toBeChecked()
    expect(within(row).getByRole('textbox', { name: 'Tên liên kết' })).toHaveValue('Video thao tác')
    expect(within(row).getByRole('textbox', { name: 'Đường dẫn (https)' })).toHaveValue('https://youtu.be/dat-ban')
  })

  it('shows an uploading image with its name, size and progress, then lists it with its size (Story 24.3c, UX-DR45)', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const size = 2 * 1024 * 1024
    const listed = json(preflight({ screenshots: [{ fileId: 'f9', fileName: 'dat-ban.png', size }] }))
    let list: () => Response | Promise<Response> = () => json(preflight())
    let release: () => void = () => undefined
    let answer: (response: Response) => void = () => undefined
    const seen = fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/boards/j1/handovers/preflight': () => list(),
      // The upload's answer is held back, so the bar can be read mid-upload.
      'POST /api/v1/boards/j1/handovers/screenshots': () =>
        new Promise<Response>((resolve) => {
          answer = resolve
        }),
    })
    renderAt('/cong-viec/j1/ban-giao/tao')

    const add = await screen.findByRole('button', { name: 'Thêm hình ảnh' })
    expect(screen.getByRole('heading', { name: 'Hình ảnh' })).toBeInTheDocument()
    expect(screen.getByText('Tối đa 5 hình ảnh, mỗi hình tối đa 10 MB.')).toBeInTheDocument()
    // N-4, word for word, under the image heading.
    expect(
      screen.getByText('Hãy che tên, số điện thoại và thông tin cá nhân của khách hàng bạn trước khi tải ảnh lên.'),
    ).toBeInTheDocument()
    const file = document.querySelector<HTMLInputElement>('input[type="file"]')!
    await user.upload(file, new File([new Uint8Array(size)], 'dat-ban.png', { type: 'image/png' }))

    const bar = await screen.findByRole('progressbar', { name: 'Đang tải dat-ban.png' })
    await waitFor(() => expect(bar).toHaveAttribute('aria-valuenow', '50'))
    expect(screen.getByText('50%')).toBeInTheDocument()
    expect(screen.getByText('dat-ban.png')).toBeInTheDocument()
    expect(screen.getByText('2 MB')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Hủy tải dat-ban.png' })).toBeInTheDocument()
    expect(add).toBeDisabled()
    expect(seen.filter((request) => request.method === 'POST' && request.url.endsWith('/handovers/screenshots'))).toHaveLength(1)

    // The list read after the upload is held too, so the full bar shows before the upload is listed.
    list = () =>
      new Promise<Response>((resolve) => {
        release = () => resolve(listed)
      })
    answer(json({ fileId: 'f9', fileName: 'dat-ban.png', size }, 201))
    await waitFor(() => expect(bar).toHaveAttribute('aria-valuenow', '100'))
    expect(screen.getByText('100%')).toBeInTheDocument()
    // The whole image is sent, so the server keeps it: there is nothing left to cancel.
    expect(screen.queryByRole('button', { name: 'Hủy tải dat-ban.png' })).toBeNull()
    expect(add).toBeDisabled()

    release()
    expect(await screen.findByRole('checkbox', { name: 'Gửi kèm ảnh dat-ban.png' })).toBeChecked()
    expect(screen.queryByRole('progressbar', { name: 'Đang tải dat-ban.png' })).toBeNull()
    expect(screen.getByText('2 MB')).toBeInTheDocument()
    expect(add).toBeEnabled()
  })

  it('refuses an image over 10 MB or an SVG before sending anything (Story 24.3c)', { timeout: 20_000 }, async () => {
    const user = userEvent.setup({ applyAccept: false })
    const seen = fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/boards/j1/handovers/preflight': () => json(preflight()),
      'POST /api/v1/boards/j1/handovers/screenshots': () => json({ fileId: 'f9', fileName: 'x.png', size: 1 }, 201),
    })
    renderAt('/cong-viec/j1/ban-giao/tao')
    await screen.findByRole('button', { name: 'Thêm hình ảnh' })
    const file = document.querySelector<HTMLInputElement>('input[type="file"]')!

    const big = new File(['png'], 'lon.png', { type: 'image/png' })
    Object.defineProperty(big, 'size', { value: 11 * 1024 * 1024 })
    await user.upload(file, big)
    expect((await screen.findAllByText('Tệp lớn hơn 10 MB.')).length).toBeGreaterThan(0)

    const refused = 'Chỉ nhận ảnh, PDF, tệp văn bản và tệp nén. Không nhận SVG hoặc HTML.'
    await user.upload(file, new File(['<svg/>'], 'logo.svg', { type: 'image/svg+xml' }))
    expect((await screen.findAllByText(refused)).length).toBeGreaterThan(0)
    // An SVG the browser gives no type is still told by its name.
    await user.upload(file, new File(['<svg/>'], 'LOGO.SVG', { type: '' }))
    await waitFor(() => expect(screen.getAllByText(refused).length).toBeGreaterThan(1))
    // And one typed as SVG is told by its type, whatever its name.
    const shown = screen.getAllByText(refused).length
    await user.upload(file, new File(['<svg/>'], 'logo.svgz', { type: 'image/svg+xml' }))
    await waitFor(() => expect(screen.getAllByText(refused).length).toBeGreaterThan(shown))

    const posts = () => seen.filter((request) => request.method === 'POST')
    expect(screen.queryByRole('progressbar')).toBeNull()
    expect(posts()).toHaveLength(0)

    // Exactly 10 MB is still allowed.
    await user.upload(file, new File([new Uint8Array(MAX_UPLOAD_BYTES)], 'vua-du.png', { type: 'image/png' }))
    await waitFor(() => expect(posts()).toHaveLength(1))
  })

  it('cancels an upload with "Hủy tải", saying nothing and adding nothing (Story 24.3c)', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const seen = fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/boards/j1/handovers/preflight': () => json(preflight()),
      // Never answered: only the cancel ends it.
      'POST /api/v1/boards/j1/handovers/screenshots': () => new Promise<Response>(() => undefined),
    })
    renderAt('/cong-viec/j1/ban-giao/tao')
    const add = await screen.findByRole('button', { name: 'Thêm hình ảnh' })
    const file = document.querySelector<HTMLInputElement>('input[type="file"]')!
    await user.upload(file, new File(['png'], 'dat-ban.png', { type: 'image/png' }))
    await screen.findByRole('progressbar', { name: 'Đang tải dat-ban.png' })
    expect(add).toBeDisabled()
    const reads = () => seen.filter((request) => request.method === 'GET' && request.url.endsWith('/handovers/preflight')).length
    const before = reads()

    await user.click(screen.getByRole('button', { name: 'Hủy tải dat-ban.png' }))

    await waitFor(() => expect(screen.queryByRole('progressbar', { name: 'Đang tải dat-ban.png' })).toBeNull())
    // A cancel may come after the server took the image, so the list is read again.
    expect(reads()).toBeGreaterThan(before)
    expect(add).toBeEnabled()
    expect(screen.queryByText('dat-ban.png')).toBeNull()
    expect(screen.queryByRole('checkbox', { name: 'Gửi kèm ảnh dat-ban.png' })).toBeNull()
    expect(screen.queryByText('Đã có lỗi. Hãy thử lại sau ít phút.')).toBeNull()
    expect(seen.filter((request) => request.method === 'POST')).toHaveLength(1)
  })

  it('says why an upload was refused, files reason first, then the handover’s, or that it got no answer (Story 24.3c)', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const answers: (() => Response)[] = [
      () => json({ status: 400, code: 'VALIDATION', reason: 'TOO_MANY_SCREENSHOTS' }, 400),
      () => json({ status: 400, code: 'VALIDATION', reason: 'JOB_FILE_QUOTA' }, 400),
      () => json({ status: 400, code: 'VALIDATION', reason: 'IMAGE_TOO_LARGE' }, 400),
      () => json({ status: 403, code: 'FORBIDDEN', reason: 'MENTOR_ONLY' }, 403),
      () => {
        throw new TypeError('Failed to fetch')
      },
    ]
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/boards/j1/handovers/preflight': () => json(preflight()),
      'POST /api/v1/boards/j1/handovers/screenshots': () => answers.shift()!(),
    })
    renderAt('/cong-viec/j1/ban-giao/tao')
    const add = await screen.findByRole('button', { name: 'Thêm hình ảnh' })
    const file = document.querySelector<HTMLInputElement>('input[type="file"]')!

    for (const text of [
      'Mỗi lần bàn giao có tối đa 5 hình ảnh.',
      'Tệp của công việc này đã đạt giới hạn 200 MB.',
      'Ảnh quá lớn: hơn 40 triệu điểm ảnh (40 megapixel). Hãy chụp hoặc lưu ảnh nhỏ hơn.',
      'Bạn không thể làm việc này. Chỉ người hướng dẫn bàn giao được.',
      'Đã có lỗi. Hãy thử lại sau ít phút.',
    ]) {
      await user.upload(file, new File(['png'], 'dat-ban.png', { type: 'image/png' }))
      expect((await screen.findAllByText(text)).length).toBeGreaterThan(0)
      await waitFor(() => expect(screen.queryByRole('progressbar')).toBeNull())
      expect(add).toBeEnabled()
    }
    expect(screen.queryByRole('checkbox', { name: 'Gửi kèm ảnh dat-ban.png' })).toBeNull()
  })

  it('sends a Code Job’s preview with its links in the order added (Story 24.3)', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let sent: Record<string, unknown> | undefined
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/boards/j1/handovers/preflight': () => json(preflight({ assessment: false, previewRequired: true })),
      'POST /api/v1/boards/j1/handovers/preview-check': () =>
        json({ reachable: true, domain: 'so-dat-ban.vercel.app', reason: null }),
      'POST /api/v1/boards/j1/handovers': async (request) => {
        sent = (await request.json()) as Record<string, unknown>
        return json(submission(), 202)
      },
      'GET /api/v1/boards/j1/handovers/submission': () => json(submission()),
    })
    renderAt('/cong-viec/j1/ban-giao/tao')

    await user.click(await screen.findByRole('radio', { name: 'Đạt' }))
    await user.type(screen.getByRole('textbox', { name: /Ghi chú cho tiêu chí 1/ }), 'Đã thử.')
    await user.type(screen.getByRole('textbox', { name: 'Đường dẫn preview (https)' }), 'https://so-dat-ban.vercel.app')
    await user.click(screen.getByRole('button', { name: 'Kiểm tra đường dẫn' }))
    expect(await screen.findByText('Preview phản hồi (so-dat-ban.vercel.app).')).toBeInTheDocument()

    const add = screen.getByRole('button', { name: 'Thêm liên kết' })
    await user.click(add)
    const video = screen.getByRole('group', { name: 'Liên kết 1' })
    await user.type(within(video).getByRole('textbox', { name: 'Tên liên kết' }), 'Video thao tác')
    await user.type(within(video).getByRole('textbox', { name: 'Đường dẫn (https)' }), 'https://youtu.be/dat-ban')
    await user.click(add)
    const notes = screen.getByRole('group', { name: 'Liên kết 2' })
    await user.selectOptions(within(notes).getByRole('combobox', { name: 'Loại' }), 'DOCUMENT')
    await user.type(within(notes).getByRole('textbox', { name: 'Tên liên kết' }), 'Ghi chú phát hành')
    await user.type(within(notes).getByRole('textbox', { name: 'Đường dẫn (https)' }), 'https://docs.google.com/document/d/ghi-chu')
    await user.click(teamCode())
    await user.click(screen.getByRole('button', { name: 'Gửi bàn giao' }))

    await waitFor(() => expect(sent).toBeDefined())
    expect(sent).toMatchObject({
      previewUrl: 'https://so-dat-ban.vercel.app',
      links: [
        { kind: 'VIDEO', label: 'Video thao tác', url: 'https://youtu.be/dat-ban' },
        { kind: 'DOCUMENT', label: 'Ghi chú phát hành', url: 'https://docs.google.com/document/d/ghi-chu' },
      ],
    })
    expect(sent).not.toHaveProperty('noPreviewReason')
  })

  it('gives the Customer the reason, the recording link and the report link of a handover with no preview (Story 24.3)', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json({ ...customerJob, title: 'Sửa lỗi đặt bàn', jobTypes: ['BUG_FIX'] }),
      'GET /api/v1/boards/j1/handovers': () => json(list({ audience: 'CUSTOMER', assessment: false })),
      'GET /api/v1/handovers/h1': () =>
        json(
          report({
            assessment: false,
            preview: { noPreviewReason: 'Ứng dụng chỉ chạy trên máy của quán' },
            links: [
              { kind: 'VIDEO', label: PAYLOADS[0], url: 'https://www.loom.com/share/abc', domain: 'www.loom.com' },
              { kind: 'DOCUMENT', label: 'Hướng dẫn thử', url: 'javascript:alert(1)', domain: 'javascript:alert(1)' },
            ],
          }),
        ),
    })
    renderAt('/cong-viec/j1/ban-giao/h1')

    expect(await screen.findByText('Ứng dụng chỉ chạy trên máy của quán')).toBeInTheDocument()
    expect(screen.getByText('Lý do không có preview')).toBeInTheDocument()
    // SG-30: the label is the Mentor's text, shown as text; the link is found by where it goes.
    const recording = document.querySelector('a[href="https://www.loom.com/share/abc"]')
    expect(recording).toHaveAttribute('href', 'https://www.loom.com/share/abc')
    expect(recording).toHaveAttribute('target', '_blank')
    expect(recording).toHaveAttribute('rel', 'noopener noreferrer')
    expect(screen.getAllByText(PAYLOADS[0], { exact: false }).length).toBeGreaterThan(0)
    expect(screen.getByText('(www.loom.com)')).toBeInTheDocument()
    // AD-35: a URL that is not https is never a link, whatever the server sent.
    expect(screen.queryByRole('link', { name: /Hướng dẫn thử/ })).toBeNull()
    for (const anchor of Array.from(document.querySelectorAll('a'))) {
      expect(anchor.getAttribute('href') ?? '').not.toMatch(/^javascript:/i)
    }
    expect(document.querySelector('img[src="x"], script, svg[onload]')).toBeNull()
  })

  it('gives the Customer the handover’s images under "Hình ảnh" (Story 24.3c)', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/handovers': () => json(list({ audience: 'CUSTOMER' })),
      'GET /api/v1/handovers/h1': () => json(report({ screenshots: [{ fileId: 'f1', fileName: 'dat-ban.png', size: 1200 }] })),
    })
    renderAt('/cong-viec/j1/ban-giao/h1')

    expect(await screen.findByRole('heading', { name: 'Hình ảnh' })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'dat-ban.png' })).toHaveAttribute('src', '/api/v1/files/f1')
    expect(screen.queryByText('Ảnh chụp màn hình')).toBeNull()
  })

  it('waits on the form while the runner copies a Code Job handover, then opens its report (Story 23.8a)', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let reads = 0
    let sent: Record<string, unknown> | undefined
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/boards/j1/handovers/preflight': () =>
        json(
          preflight({
            assessment: false,
          }),
        ),
      'POST /api/v1/boards/j1/handovers': async (request) => {
        sent = (await request.json()) as Record<string, unknown>
        return json(submission(), 202)
      },
      'GET /api/v1/boards/j1/handovers/submission': () =>
        json(
          reads++ === 0
            ? submission({ runStatus: 'IN_PROGRESS' })
            : submission({ state: 'DONE', handoverId: 'h1', runStatus: 'COMPLETED', runConclusion: 'success' }),
        ),
      'GET /api/v1/handovers/h1': () => json(report({ audience: 'MENTOR', assessment: false })),
      'GET /api/v1/boards/j1/handovers': () => json(list({ assessment: false })),
    })
    renderAt('/cong-viec/j1/ban-giao/tao')

    await user.click(await screen.findByRole('radio', { name: 'Đạt' }))
    await user.type(screen.getByRole('textbox', { name: /Ghi chú cho tiêu chí 1/ }), 'Đã thử.')
    const send = screen.getByRole('button', { name: 'Gửi bàn giao' })
    // FR-25, R28 (Story 23.8b): N-33's duty confirmation is the last thing the form waits for.
    expect(send).toBeDisabled()
    await user.click(teamCode())
    await user.click(send)

    expect(await screen.findByText('Đang sao chép bàn giao Giai đoạn 1…')).toBeInTheDocument()
    expect(send).toBeDisabled()
    expect(sent).toMatchObject({ teamCodeNoticeVersion: '1.1' })
    // Story 23.8b: the Actions run status row says where the runner's run stands, and links nothing.
    const row = await screen.findByRole('group', { name: 'Sao chép bàn giao Giai đoạn 1' })
    expect(within(row).getByText('Đang chạy')).toBeInTheDocument()
    expect(within(row).queryByRole('link')).not.toBeInTheDocument()
    // The form reads the submission again every 3 s until the copy is over, then opens the Delivery Report.
    expect(await screen.findByText('Đã bàn giao Giai đoạn 1.', {}, { timeout: 15_000 })).toBeInTheDocument()
    expect(screen.queryByText('Đang sao chép bàn giao Giai đoạn 1…')).not.toBeInTheDocument()
  })

  it('names why a copy was refused, in the module its reason belongs to (Story 23.8a, AD-23)', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let reads = 0
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/boards/j1/handovers/preflight': () =>
        json(
          preflight({
            assessment: false,
          }),
        ),
      'POST /api/v1/boards/j1/handovers': () => json(submission(), 202),
      'GET /api/v1/boards/j1/handovers/submission': () =>
        json(
          reads++ === 0
            ? submission()
            : submission({ state: 'REFUSED', reason: 'STATE_CHANGED', reasonModule: 'job' }),
        ),
    })
    renderAt('/cong-viec/j1/ban-giao/tao')

    await user.click(await screen.findByRole('radio', { name: 'Đạt' }))
    await user.type(screen.getByRole('textbox', { name: /Ghi chú cho tiêu chí 1/ }), 'Đã thử.')
    await user.click(teamCode())
    await user.click(screen.getByRole('button', { name: 'Gửi bàn giao' }))

    expect(await screen.findByRole('alert', {}, { timeout: 15_000 })).toHaveTextContent(
      'Chưa sao chép được bàn giao: Công việc vừa đổi trạng thái. Hãy thử gửi lại.',
    )
    expect(screen.getByRole('button', { name: 'Gửi bàn giao' })).toBeEnabled()
  })

  it('says so when the copy state cannot be read (Story 23.8a)', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/boards/j1/handovers/preflight': () =>
        json(
          preflight({
            assessment: false,
          }),
        ),
      'POST /api/v1/boards/j1/handovers': () => json(submission(), 202),
      'GET /api/v1/boards/j1/handovers/submission': () => new Response('', { status: 503 }),
    })
    renderAt('/cong-viec/j1/ban-giao/tao')

    await user.click(await screen.findByRole('radio', { name: 'Đạt' }))
    await user.type(screen.getByRole('textbox', { name: /Ghi chú cho tiêu chí 1/ }), 'Đã thử.')
    await user.click(teamCode())
    await user.click(screen.getByRole('button', { name: 'Gửi bàn giao' }))

    expect(await screen.findByRole('alert', {}, { timeout: 15_000 })).toHaveTextContent(
      'Chưa đọc được trạng thái lần sao chép. Hãy tải lại trang sau ít phút.',
    )
    expect(screen.getByRole('button', { name: 'Gửi bàn giao' })).toBeEnabled()
  })

  /** FR-25, R28 (Story 23.8b): a reworded N-33 is read again, so the form offers a reload and sends nothing more. */
  it('offers a reload when N-33 was reworded while the form was open (Story 23.8b)', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/boards/j1/handovers/preflight': () =>
        json(
          preflight({
            assessment: false,
          }),
        ),
      'POST /api/v1/boards/j1/handovers': () =>
        json({ status: 400, code: 'VALIDATION', reason: 'NOTICE_REQUIRED' }, 400),
    })
    renderAt('/cong-viec/j1/ban-giao/tao')

    await user.click(await screen.findByRole('radio', { name: 'Đạt' }))
    await user.type(screen.getByRole('textbox', { name: /Ghi chú cho tiêu chí 1/ }), 'Đã thử.')
    expect(await screen.findByText(/Lưu ý về mã của nhóm · phiên bản 1\.0/)).toBeInTheDocument()
    await user.click(teamCode())
    await user.click(screen.getByRole('button', { name: 'Gửi bàn giao' }))

    expect(await screen.findByRole('alert', {}, { timeout: 15_000 })).toHaveTextContent(
      'Lưu ý về mã của nhóm vừa được cập nhật. Hãy tải lại trang để đọc bản mới rồi gửi lại.',
    )
    expect(screen.getByRole('button', { name: 'Tải lại trang' })).toBeEnabled()
  })

  /** UX-DR115 (Story 23.8b): JOB-5 shows the team the copy's run row while a submission is pending. */
  it('shows the team the copy run on JOB-5 while a handover is being copied (Story 23.8b)', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/jobs/j1': () => json({ ...mentorJob, jobTypes: ['BUG_FIX'], codeJob: true }),
      'GET /api/v1/boards/j1/handovers': () => json(list({ assessment: false })),
      'GET /api/v1/boards/j1/handovers/submission': () => json(submission({ number: 2, runStatus: 'QUEUED' })),
    })
    renderAt('/cong-viec/j1/ban-giao')

    const row = await screen.findByRole('group', { name: 'Sao chép bàn giao Giai đoạn 2' }, { timeout: 15_000 })
    expect(within(row).getByText('Đang chờ GitHub')).toBeInTheDocument()
    expect(within(row).queryByRole('link')).not.toBeInTheDocument()
    expect(screen.getByText('Đang sao chép bàn giao Giai đoạn 2…')).toBeInTheDocument()
  })

  /**
   * AD-46 (Story 23.8b): a run GitHub concluded as anything but a success, while the copy is still pending, is
   * what the team reads through the retry window; it is "Không chạy được", and still never a link.
   */
  it('says a copy run that GitHub failed is not running, with no link (Story 23.8b)', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/jobs/j1': () => json({ ...mentorJob, jobTypes: ['BUG_FIX'], codeJob: true }),
      'GET /api/v1/boards/j1/handovers': () => json(list({ assessment: false })),
      'GET /api/v1/boards/j1/handovers/submission': () =>
        json(submission({ number: 2, runStatus: 'COMPLETED', runConclusion: 'failure' })),
    })
    renderAt('/cong-viec/j1/ban-giao')

    const row = await screen.findByRole('group', { name: 'Sao chép bàn giao Giai đoạn 2' }, { timeout: 15_000 })
    expect(within(row).getByText('Không chạy được')).toBeInTheDocument()
    expect(within(row).queryByRole('link')).not.toBeInTheDocument()
  })

  /**
   * R28 (Story 23.8b): a handover recorded before N-33 existed holds no confirmation, so the Customer is told of
   * none.
   */
  it('quotes N-33 only where the handover holds the confirmation (Story 23.8b)', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json({ ...customerJob, title: 'Sửa lỗi đặt bàn', jobTypes: ['BUG_FIX'] }),
      'GET /api/v1/boards/j1/handovers': () => json(list({ audience: 'CUSTOMER', assessment: false })),
      'GET /api/v1/handovers/h1': () =>
        json(
          report({
            assessment: false,
            code: {
              handoverCommit: '9c41a02' + '0'.repeat(33),
              pullNumber: 101,
              commits: [],
              unattributed: 0,
              truncated: false,
              sensitiveChanges: [],
              sensitiveChangeCount: 0,
              teamCodeConfirmedAt: null,
            },
          }),
        ),
    })
    renderAt('/cong-viec/j1/ban-giao/h1')

    expect(await screen.findByText('Chi tiết kỹ thuật')).toBeInTheDocument()
    expect(screen.queryByText(/vibefix không quét mã của nhóm/)).not.toBeInTheDocument()
  })

  it('tells the Customer a handover voided at acceptance changed on GitHub (FR-33, M3)', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/handovers': () => json(list({ audience: 'CUSTOMER' })),
      'GET /api/v1/handovers/h1': () => json(report({ status: 'VOIDED' })),
    })
    renderAt('/cong-viec/j1/ban-giao/h1')

    expect(
      await screen.findByText(
        'Bàn giao này đã bị hủy vì nội dung bàn giao trên GitHub đã thay đổi trước khi được nghiệm thu. Người hướng dẫn sẽ bàn giao lại.',
      ),
    ).toBeInTheDocument()
    expect(screen.getByText('Tiền của giai đoạn vẫn được giữ.')).toBeInTheDocument()
    expect(screen.getAllByText('Đã hủy vì nội dung bàn giao thay đổi').length).toBeGreaterThan(0)
  })

  it('refuses a Code Job handover that changes a workflow file with N-16', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/boards/j1/handovers/preflight': () =>
        json(
          preflight({
            assessment: false,
            problems: ['WORKFLOW_FILES_CHANGED'],
            commitCount: 2,
            sensitiveChanges: [{ path: 'Dockerfile', kind: 'BUILD_SCRIPT' }],
          }),
        ),
    })
    renderAt('/cong-viec/j1/ban-giao/tao')

    expect(await screen.findByText(/Không thể bàn giao: có thay đổi trong thư mục \.github\/workflows/)).toBeInTheDocument()
    expect(screen.getByText('Sẽ bàn giao 2 commit mới.')).toBeInTheDocument()
    expect(screen.getByText('Dockerfile')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Gửi bàn giao' })).toBeDisabled()
  })
})
