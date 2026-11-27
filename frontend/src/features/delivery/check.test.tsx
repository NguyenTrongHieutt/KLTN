import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedInUser, inEveryJob } from '@/test/fakeBackend'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

beforeEach(() => {
  window.sessionStorage.clear()
})

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

const criterion = (id: string, number: number, statement: string) => ({
  criterionId: id,
  number,
  statement,
  checkSteps: '1. Mở báo cáo. 2. Đếm các mục.',
  met: true,
  note: `Ghi chú ${number} của người hướng dẫn.`,
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
  criteria: [criterion('c1', 1, 'Báo cáo nêu đủ chín mục'), criterion('c2', 2, 'Có giải thích thuật ngữ')],
  version: 13,
  roundsLeft: 2,
  decision: { releaseVnd: 1000001, revisionAllowed: true, reworkDueOn: '2026-11-27' },
  checks: [],
  preview: {},
  screenshots: [],
  links: [],
  ...overrides,
})

const list = { jobId: 'j1', title: 'Đánh giá sổ đặt bàn', audience: 'CUSTOMER', assessment: true, handovers: [] }

describe('JOB-5 guided check (FR-33, FR-34, UX-DR33)', () => {
  it('walks the criteria one by one and asks for a revision of the one not met', { timeout: 25_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/handovers': () => json(list),
      'GET /api/v1/handovers/h1': () => json(report()),
      'POST /api/v1/handovers/h1/revision': async (request) => {
        sent.push(await request.json())
        return json(report({ status: 'REVISION_REQUESTED', decision: null }))
      },
    })
    renderAt('/cong-viec/j1/ban-giao/h1')

    expect(await screen.findByText(/Giai đoạn này còn 2 lượt sửa/)).toBeInTheDocument()
    await user.click(screen.getByRole('link', { name: 'Kiểm tra từng tiêu chí' }))

    expect(await screen.findByText('Tiêu chí 1/2')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: 'Báo cáo nêu đủ chín mục' })).toBeInTheDocument()
    expect(screen.getByText('Mở báo cáo.')).toBeInTheDocument()
    expect(screen.getByText('Đếm các mục.')).toBeInTheDocument()
    expect(screen.getByText('Ghi chú 1 của người hướng dẫn.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Tiếp' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Hãy chọn Đạt hoặc Chưa đạt trước khi tiếp tục.')
    await user.click(screen.getByRole('radio', { name: 'Đạt' }))
    await user.click(screen.getByRole('button', { name: 'Tiếp' }))

    expect(await screen.findByText('Tiêu chí 2/2')).toBeInTheDocument()
    await user.click(screen.getByRole('radio', { name: 'Chưa đạt' }))
    await user.type(screen.getByRole('textbox', { name: 'Nhận xét của bạn (không bắt buộc)' }), 'Thiếu ba thuật ngữ.')
    await user.click(screen.getByRole('button', { name: 'Tiếp' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Tóm tắt kiểm tra' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Nghiệm thu giai đoạn' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Yêu cầu sửa (1 tiêu chí)' }))
    const dialog = await screen.findByRole('dialog', { name: 'Yêu cầu sửa 1 tiêu chí?' })
    expect(within(dialog).getByText(/còn 1 lượt sau đó/)).toBeInTheDocument()
    // FR-87: the request's effect on the due date, from the server's own figure.
    expect(within(dialog).getByText('Hạn mới của giai đoạn: 27/11')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận' }))

    await waitFor(() =>
      expect(sent).toEqual([
        {
          version: 13,
          checks: [
            { criterionId: 'c1', met: true },
            { criterionId: 'c2', met: false, comment: 'Thiếu ba thuật ngữ.' },
          ],
        },
      ]),
    )
    expect(await screen.findByRole('heading', { level: 2, name: 'Báo cáo bàn giao · Giai đoạn 1 · Báo cáo' })).toBeInTheDocument()
    expect(window.sessionStorage.getItem('vibefix.check.h1')).toBeNull()
  })

  /** D5 (Story 24.4b): nothing checks or masks a comment, so one holding a key-like value goes once, as typed. */
  it('sends a revision comment holding a key-like value once, as typed, with no warning', { timeout: 25_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    // Built at run time, so no credential shape sits in this source.
    const comment = `Báo cáo còn để lộ khóa ${'sk' + '_test_' + 'ab'.repeat(12)}, hãy bỏ đi.`
    window.sessionStorage.setItem('vibefix.check.h1', JSON.stringify({ step: 1, marks: { c1: { met: true, comment: '' } } }))
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/handovers': () => json(list),
      'GET /api/v1/handovers/h1': () => json(report()),
      'POST /api/v1/handovers/h1/revision': async (request) => {
        sent.push(await request.json())
        return json(report({ status: 'REVISION_REQUESTED', decision: null }))
      },
    })
    renderAt('/cong-viec/j1/ban-giao/h1/kiem-tra')

    expect(await screen.findByText('Tiêu chí 2/2')).toBeInTheDocument()
    await user.click(screen.getByRole('radio', { name: 'Chưa đạt' }))
    await user.type(screen.getByRole('textbox', { name: 'Nhận xét của bạn (không bắt buộc)' }), comment)
    await user.click(screen.getByRole('button', { name: 'Tiếp' }))
    await user.click(await screen.findByRole('button', { name: 'Yêu cầu sửa (1 tiêu chí)' }))
    const dialog = await screen.findByRole('dialog', { name: 'Yêu cầu sửa 1 tiêu chí?' })
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận' }))

    expect(await screen.findByRole('heading', { level: 2, name: 'Báo cáo bàn giao · Giai đoạn 1 · Báo cáo' })).toBeInTheDocument()
    expect(sent).toEqual([
      {
        version: 13,
        checks: [
          { criterionId: 'c1', met: true },
          { criterionId: 'c2', met: false, comment },
        ],
      },
    ])
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('resumes a draft after a reload and accepts with the amount and the test tag', { timeout: 25_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    window.sessionStorage.setItem('vibefix.check.h1', JSON.stringify({ step: 1, marks: { c1: { met: true, comment: '' } } }))
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/handovers/h1': () => json(report()),
      'POST /api/v1/handovers/h1/accept': async (request) => {
        sent.push(await request.json())
        return json(report({ status: 'ACCEPTED', decision: null }))
      },
    })
    renderAt('/cong-viec/j1/ban-giao/h1/kiem-tra')

    expect(await screen.findByText('Tiêu chí 2/2')).toBeInTheDocument()
    await user.click(screen.getByRole('radio', { name: 'Đạt' }))
    await user.click(screen.getByRole('button', { name: 'Tiếp' }))
    expect(screen.queryByRole('button', { name: /Yêu cầu sửa/ })).toBeNull()
    await user.click(await screen.findByRole('button', { name: 'Nghiệm thu giai đoạn' }))
    const dialog = await screen.findByRole('dialog', { name: 'Nghiệm thu Giai đoạn 1?' })
    expect(within(dialog).getByText(/1\.000\.001/)).toBeInTheDocument()
    expect(within(dialog).getByText('THỬ NGHIỆM')).toBeInTheDocument()
    expect(within(dialog).getByText(/không hoàn tác được/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận' }))
    await waitFor(() => expect(sent).toHaveLength(1))
    expect(sent[0]).toMatchObject({ version: 13, checks: [{ criterionId: 'c1', met: true }, { criterionId: 'c2', met: true }] })
  })

  it('says a Code Job acceptance merges first, and pays once the merge is done (FR-33)', { timeout: 25_000 }, async () => {
    const user = userEvent.setup()
    window.sessionStorage.setItem('vibefix.check.h1', JSON.stringify({ step: 1, marks: { c1: { met: true, comment: '' } } }))
    const code = {
      handoverCommit: '9c41a02' + '0'.repeat(33),
      pullNumber: 101,
      commits: [],
      unattributed: 0,
      truncated: false,
      sensitiveChanges: [],
      sensitiveChangeCount: 0,
      pages: [],
      teamCodeConfirmedAt: '2026-10-20T02:55:00Z',
    }
    const merging = report({ assessment: false, code, acceptance: 'MERGING', decision: null })
    let accepted = false
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/handovers/h1': () => json(accepted ? merging : report({ assessment: false, code })),
      'POST /api/v1/handovers/h1/accept': () => {
        accepted = true
        return json(merging)
      },
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/handovers': () => json(list),
    })
    renderAt('/cong-viec/j1/ban-giao/h1/kiem-tra')

    expect(await screen.findByText('Tiêu chí 2/2')).toBeInTheDocument()
    await user.click(screen.getByRole('radio', { name: 'Đạt' }))
    await user.click(screen.getByRole('button', { name: 'Tiếp' }))
    await user.click(await screen.findByRole('button', { name: 'Nghiệm thu giai đoạn' }))
    const dialog = await screen.findByRole('dialog', { name: 'Nghiệm thu Giai đoạn 1?' })
    // N-33 (FR-33, R28, Story 23.8b): the dialog states it with the Mentor's name, and asks nothing.
    expect(
      within(dialog).getByText(/vibefix không quét mã của nhóm\. Người hướng dẫn Đỗ Mạnh Hùng đã xác nhận/),
    ).toBeInTheDocument()
    expect(within(dialog).queryByRole('checkbox')).not.toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận' }))

    const lines = await screen.findAllByText('Đang merge bàn giao Giai đoạn 1. Tiền được chuyển ngay khi merge xong.')
    expect(lines.length).toBeGreaterThan(0)
    expect(screen.queryByRole('link', { name: 'Kiểm tra từng tiêu chí' })).toBeNull()
  })

  it('links the preview and every link with N-11 next to each step and its check steps (UX-DR33)', { timeout: 25_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/handovers/h1': () =>
        json(
          report({
            preview: { preview: { url: 'https://vht-preview.vercel.app', domain: 'vht-preview.vercel.app' } },
            links: [
              {
                kind: 'DOCUMENT',
                label: 'Báo cáo đánh giá',
                url: 'https://docs.google.com/document/d/bao-cao',
                domain: 'docs.google.com',
              },
              { kind: 'VIDEO', label: 'Video trình bày', url: 'https://www.loom.com/share/trinh-bay', domain: 'www.loom.com' },
            ],
          }),
        ),
    })
    renderAt('/cong-viec/j1/ban-giao/h1/kiem-tra')

    expect(await screen.findByRole('link', { name: /Mở preview/ })).toHaveAttribute('href', 'https://vht-preview.vercel.app')
    expect(screen.getByText('(vht-preview.vercel.app)')).toBeInTheDocument()
    expect(screen.getByText(/Trang này nằm ngoài vibefix \(vht-preview\.vercel\.app\)/)).toBeInTheDocument()
    // Story 24.3: beside "Cách kiểm tra", every link with its kind, label and domain -- the report among them.
    const reportLink = screen.getByRole('link', { name: /Báo cáo đánh giá/ })
    expect(reportLink).toHaveAttribute('href', 'https://docs.google.com/document/d/bao-cao')
    expect(reportLink).toHaveAttribute('target', '_blank')
    expect(reportLink).toHaveAttribute('rel', 'noopener noreferrer')
    expect(screen.getByText('Tài liệu')).toBeInTheDocument()
    expect(screen.getByText('(docs.google.com)')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Video trình bày/ })).toHaveAttribute('href', 'https://www.loom.com/share/trinh-bay')
    expect(screen.getByText('Bản ghi / video')).toBeInTheDocument()
    expect(screen.getByText('(www.loom.com)')).toBeInTheDocument()
  })

  it('says why there is no preview beside the recording that shows the change instead (Story 24.3)', { timeout: 25_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/handovers/h1': () =>
        json(
          report({
            assessment: false,
            preview: { noPreviewReason: 'Ứng dụng chỉ chạy trên máy của quán' },
            links: [
              { kind: 'VIDEO', label: 'Quay lại thao tác', url: 'https://www.loom.com/share/abc', domain: 'www.loom.com' },
            ],
          }),
        ),
    })
    renderAt('/cong-viec/j1/ban-giao/h1/kiem-tra')

    const recording = await screen.findByRole('link', { name: /Quay lại thao tác/ })
    const howTo = recording.closest('div')!
    expect(within(howTo).getByText('Cách kiểm tra')).toBeInTheDocument()
    expect(within(howTo).getByText('Lý do không có preview')).toBeInTheDocument()
    expect(within(howTo).getByText('Ứng dụng chỉ chạy trên máy của quán')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Mở preview/ })).toBeNull()
  })

  it('explains a Stale answer when an acceptance merge voided the handover meanwhile (UX-DR26)', { timeout: 25_000 }, async () => {
    const user = userEvent.setup()
    window.sessionStorage.setItem('vibefix.check.h1', JSON.stringify({ step: 1, marks: { c1: { met: true, comment: '' } } }))
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/handovers/h1': () => json(report()),
      'POST /api/v1/handovers/h1/accept': () => json({ code: 'STALE', reason: 'HANDOVER_VOIDED' }, 409),
    })
    renderAt('/cong-viec/j1/ban-giao/h1/kiem-tra')

    expect(await screen.findByText('Tiêu chí 2/2')).toBeInTheDocument()
    await user.click(screen.getByRole('radio', { name: 'Đạt' }))
    await user.click(screen.getByRole('button', { name: 'Tiếp' }))
    await user.click(await screen.findByRole('button', { name: 'Nghiệm thu giai đoạn' }))
    const dialog = await screen.findByRole('dialog', { name: 'Nghiệm thu Giai đoạn 1?' })
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận' }))

    expect(
      await screen.findByText(
        'Bàn giao này đã bị hủy vì nội dung bàn giao trên GitHub đã thay đổi trước khi được nghiệm thu. Giai đoạn cần được bàn giao lại.',
      ),
    ).toBeInTheDocument()
  })

  it('offers only acceptance when no Revision Round is left', { timeout: 25_000 }, async () => {
    const user = userEvent.setup()
    window.sessionStorage.setItem(
      'vibefix.check.h1',
      JSON.stringify({ step: 2, marks: { c1: { met: true, comment: '' }, c2: { met: false, comment: '' } } }),
    )
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/handovers/h1': () => json(report({ roundsLeft: 0, decision: { releaseVnd: 1000001, revisionAllowed: false } })),
    })
    renderAt('/cong-viec/j1/ban-giao/h1/kiem-tra')

    expect(await screen.findByText('Giai đoạn này đã hết lượt sửa, nên chỉ còn nghiệm thu.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Yêu cầu sửa/ })).toBeNull()
    expect(screen.getByRole('button', { name: 'Nghiệm thu giai đoạn' })).toBeInTheDocument()
    await user.click(screen.getAllByRole('button', { name: 'Sửa' })[1])
    expect(await screen.findByText('Tiêu chí 2/2')).toBeInTheDocument()
  })
})

/** A Self-mentored Job's handover: a Crew member made it, and its Customer, who mentors, may return it. */
const memberReport = (overrides: object = {}) =>
  report({
    mentorName: 'Trần Thảo Vy',
    byMember: true,
    decision: { releaseVnd: 1000001, revisionAllowed: true, reworkDueOn: '2026-11-27', returnLimit: 2, returnsLeft: 2 },
    ...overrides,
  })

const summaryDraft = JSON.stringify({ step: 2, marks: { c1: { met: true, comment: '' }, c2: { met: false, comment: '' } } })

describe('JOB-5 in a Self-mentored Job (FR-32, FR-86, UX-DR126, UX-DR127, Story 25.5)', () => {
  it('returns a handover with a reason, saying what it does and how many returns are left', { timeout: 25_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    const reason = 'Báo cáo còn thiếu mục 7.'
    const returned = memberReport({ status: 'VOIDED', decision: null, returned: { at: '2026-11-22T03:00:00Z', reason } })
    window.sessionStorage.setItem('vibefix.check.h1', summaryDraft)
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER_MENTOR'),
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/handovers': () => json({ ...list, selfMentored: true, canHandOver: false }),
      // The report as the server holds it: returned once the command went through, so a later read agrees.
      'GET /api/v1/handovers/h1': () => json(sent.length > 0 ? returned : memberReport()),
      'POST /api/v1/handovers/h1/return': async (request) => {
        sent.push(await request.json())
        return json(returned)
      },
    })
    renderAt('/cong-viec/j1/ban-giao/h1/kiem-tra')

    expect(await screen.findByRole('heading', { level: 1, name: 'Tóm tắt kiểm tra' })).toBeInTheDocument()
    expect(screen.getByText('còn 2 lần trả lại')).toBeInTheDocument()
    // The other two decisions stand beside it, whatever the marks say.
    expect(screen.getByRole('button', { name: 'Nghiệm thu giai đoạn' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Yêu cầu sửa (1 tiêu chí)' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Trả lại bàn giao' }))

    const dialog = await screen.findByRole('dialog', { name: 'Trả lại bàn giao Giai đoạn 1?' })
    expect(within(dialog).getByText('Việc này không dùng lượt yêu cầu sửa.')).toBeInTheDocument()
    expect(within(dialog).getByText('Bạn còn 2 lần trả lại cho giai đoạn này, còn 1 sau lần này.')).toBeInTheDocument()
    expect(within(dialog).getByText('Hạn mới của giai đoạn: 27/11')).toBeInTheDocument()
    // An Assessment Job hands over no code, so nothing speaks of a merge request.
    expect(within(dialog).queryByText(/Yêu cầu merge/)).toBeNull()
    const confirm = within(dialog).getByRole('button', { name: 'Trả lại bàn giao' })
    expect(confirm).toBeDisabled()
    await user.type(within(dialog).getByRole('textbox', { name: 'Lý do trả lại' }), `  ${reason} `)
    await user.click(confirm)

    await waitFor(() => expect(sent).toEqual([{ version: 13, reason }]))
    const article = (await screen.findByRole('heading', { level: 2, name: 'Báo cáo bàn giao · Giai đoạn 1 · Báo cáo' })).closest(
      'article',
    )!
    expect(within(article).getByText(/Khách hàng đã trả lại bàn giao này ngày 22\/11\/2026/)).toBeInTheDocument()
    expect(within(article).getByText(reason)).toBeInTheDocument()
    expect(within(article).getByText('Khách hàng đã trả lại', { selector: 'header span' })).toBeInTheDocument()
    expect(within(article).queryByText(/nội dung bàn giao trên GitHub đã thay đổi/)).toBeNull()
    expect(window.sessionStorage.getItem('vibefix.check.h1')).toBeNull()
  })

  it('keeps "Trả lại bàn giao" in place, disabled with its reason, once the returns are used up (R65)', { timeout: 25_000 }, async () => {
    window.sessionStorage.setItem('vibefix.check.h1', summaryDraft)
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER_MENTOR'),
      'GET /api/v1/handovers/h1': () =>
        json(memberReport({ decision: { releaseVnd: 1000001, revisionAllowed: true, returnLimit: 2, returnsLeft: 0 } })),
    })
    renderAt('/cong-viec/j1/ban-giao/h1/kiem-tra')

    expect(await screen.findByRole('heading', { level: 1, name: 'Tóm tắt kiểm tra' })).toBeInTheDocument()
    const action = screen.getByRole('button', { name: 'Trả lại bàn giao' })
    // Disabled in place and still focusable, so a keyboard user hears why.
    expect(action).toHaveAttribute('aria-disabled', 'true')
    expect(action).toHaveAccessibleDescription('Đã dùng hết lần trả lại cho giai đoạn này.')
    expect(screen.getByRole('button', { name: 'Yêu cầu sửa (1 tiêu chí)' })).toBeEnabled()
  })

  it('says why the return was refused and returns nothing', { timeout: 25_000 }, async () => {
    const user = userEvent.setup()
    window.sessionStorage.setItem('vibefix.check.h1', summaryDraft)
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER_MENTOR'),
      'GET /api/v1/handovers/h1': () => json(memberReport()),
      'POST /api/v1/handovers/h1/return': () =>
        json({ status: 422, code: 'GUARD_FAILED', reason: 'HANDOVER_RETURNS_USED' }, 422),
    })
    renderAt('/cong-viec/j1/ban-giao/h1/kiem-tra')

    await user.click(await screen.findByRole('button', { name: 'Trả lại bàn giao' }))
    const dialog = await screen.findByRole('dialog', { name: 'Trả lại bàn giao Giai đoạn 1?' })
    await user.type(within(dialog).getByRole('textbox', { name: 'Lý do trả lại' }), 'Bấm sớm')
    await user.click(within(dialog).getByRole('button', { name: 'Trả lại bàn giao' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Đã dùng hết lần trả lại cho giai đoạn này.')
    expect(window.sessionStorage.getItem('vibefix.check.h1')).not.toBeNull()
  })

  /** FR-34: with no return and no Revision Round left, what remains is a normal Job's: accept, or the two ways out. */
  it('leaves acceptance, a Change Request and a Dispute once neither a return nor a Revision Round is left', { timeout: 25_000 }, async () => {
    window.sessionStorage.setItem('vibefix.check.h1', summaryDraft)
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER_MENTOR'),
      'GET /api/v1/handovers/h1': () =>
        json(memberReport({ roundsLeft: 0, decision: { releaseVnd: 1000001, revisionAllowed: false, returnLimit: 2, returnsLeft: 0 } })),
    })
    renderAt('/cong-viec/j1/ban-giao/h1/kiem-tra')

    expect(await screen.findByText('Giai đoạn này đã hết lượt sửa, nên chỉ còn nghiệm thu.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Trả lại bàn giao' })).toHaveAttribute('aria-disabled', 'true')
    expect(screen.queryByRole('button', { name: /Yêu cầu sửa/ })).toBeNull()
    expect(screen.getByRole('button', { name: 'Nghiệm thu giai đoạn' })).toBeEnabled()
    expect(screen.getByRole('link', { name: 'Đề nghị thay đổi' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Mở tranh chấp' })).toBeInTheDocument()
  })

  /** A handover returned in another tab is void, but not because GitHub moved: the answer says which. */
  it('says the handover was returned when a decision meets one already returned', { timeout: 25_000 }, async () => {
    const user = userEvent.setup()
    window.sessionStorage.setItem('vibefix.check.h1', summaryDraft)
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER_MENTOR'),
      'GET /api/v1/handovers/h1': () => json(memberReport()),
      'POST /api/v1/handovers/h1/accept': () => json({ status: 409, code: 'STALE', reason: 'HANDOVER_RETURNED' }, 409),
      'POST /api/v1/handovers/h1/return': () => json({ status: 409, code: 'CONFLICT', reason: 'HANDOVER_DECIDED' }, 409),
    })
    renderAt('/cong-viec/j1/ban-giao/h1/kiem-tra')

    await user.click(await screen.findByRole('button', { name: 'Nghiệm thu giai đoạn' }))
    await user.click(within(await screen.findByRole('dialog', { name: 'Nghiệm thu Giai đoạn 1?' })).getByRole('button', { name: 'Xác nhận' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Bàn giao này đã được trả lại. Hãy mở báo cáo bàn giao để xem.')
    expect(screen.queryByText(/GitHub đã thay đổi/)).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Trả lại bàn giao' }))
    const dialog = await screen.findByRole('dialog', { name: 'Trả lại bàn giao Giai đoạn 1?' })
    await user.type(within(dialog).getByRole('textbox', { name: 'Lý do trả lại' }), 'Bấm sớm')
    await user.click(within(dialog).getByRole('button', { name: 'Trả lại bàn giao' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Lần bàn giao này đã được quyết định.')
  })

  /** A Dispute's freeze is `job`'s refusal, read from `job`'s sentences though the decision is `delivery`'s. */
  it('says the Job is in a Dispute when the freeze refuses a decision or a return', { timeout: 25_000 }, async () => {
    const user = userEvent.setup()
    const frozen = () => json({ status: 409, code: 'CONFLICT', reason: 'JOB_FROZEN' }, 409)
    window.sessionStorage.setItem('vibefix.check.h1', summaryDraft)
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER_MENTOR'),
      'GET /api/v1/handovers/h1': () => json(memberReport()),
      'POST /api/v1/handovers/h1/accept': frozen,
      'POST /api/v1/handovers/h1/return': frozen,
    })
    renderAt('/cong-viec/j1/ban-giao/h1/kiem-tra')

    await user.click(await screen.findByRole('button', { name: 'Nghiệm thu giai đoạn' }))
    await user.click(within(await screen.findByRole('dialog', { name: 'Nghiệm thu Giai đoạn 1?' })).getByRole('button', { name: 'Xác nhận' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/Công việc đang tạm dừng vì có tranh chấp/i)

    await user.click(screen.getByRole('button', { name: 'Trả lại bàn giao' }))
    const dialog = await screen.findByRole('dialog', { name: 'Trả lại bàn giao Giai đoạn 1?' })
    await user.type(within(dialog).getByRole('textbox', { name: 'Lý do trả lại' }), 'Bấm sớm')
    await user.click(within(dialog).getByRole('button', { name: 'Trả lại bàn giao' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/Công việc đang tạm dừng vì có tranh chấp/i)
  })

  /** The check route of a handover already returned says so, not that it was "decided". */
  it('tells a returned handover apart on the check route', { timeout: 25_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER_MENTOR'),
      'GET /api/v1/handovers/h1': () =>
        json(memberReport({ status: 'VOIDED', decision: null, returned: { at: '2026-11-22T03:00:00Z', reason: 'Bấm sớm' } })),
    })
    renderAt('/cong-viec/j1/ban-giao/h1/kiem-tra')

    expect(await screen.findByText(/Bàn giao này đã được trả lại\./)).toBeInTheDocument()
    expect(screen.queryByText(/đã được quyết định/)).toBeNull()
  })

  it('offers no return in a normal Job', { timeout: 25_000 }, async () => {
    window.sessionStorage.setItem('vibefix.check.h1', summaryDraft)
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/handovers/h1': () => json(report()),
    })
    renderAt('/cong-viec/j1/ban-giao/h1/kiem-tra')

    expect(await screen.findByRole('button', { name: 'Yêu cầu sửa (1 tiêu chí)' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Trả lại bàn giao' })).toBeNull()
    expect(screen.queryByText(/lần trả lại/)).toBeNull()
  })

  it('names the member on the report and in the check, and says a return is left', { timeout: 25_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER_MENTOR'),
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/handovers': () => json({ ...list, selfMentored: true, canHandOver: false }),
      'GET /api/v1/handovers/h1': () => json(memberReport()),
    })
    renderAt('/cong-viec/j1/ban-giao/h1')

    const article = (await screen.findByRole('heading', { level: 2, name: 'Báo cáo bàn giao · Giai đoạn 1 · Báo cáo' })).closest(
      'article',
    )!
    expect(within(article).getByText(/Bàn giao bởi Trần Thảo Vy · .* · lần thứ 1/)).toBeInTheDocument()
    expect(within(article).getAllByText('Thành viên tự nhận xét: Đạt')).toHaveLength(2)
    expect(within(article).queryByText(/Người hướng dẫn/)).toBeNull()
    expect(within(article).getByText(/còn 2 lần trả lại/)).toBeInTheDocument()
    await user.click(within(article).getByRole('link', { name: 'Kiểm tra từng tiêu chí' }))
    expect(await screen.findByText('Tiêu chí 1/2')).toBeInTheDocument()
    expect(screen.getByText('Ghi chú của thành viên')).toBeInTheDocument()
  })

  it('lets an active member create the handover and tells a returned handover apart in the list', { timeout: 25_000 }, async () => {
    const reason = 'Báo cáo còn thiếu mục 7.'
    fakeBackend(signedInUser, {
      ...inEveryJob('CREW'),
      'GET /api/v1/boards/j1/handovers': () =>
        json({
          ...list,
          audience: 'CREW',
          selfMentored: true,
          canHandOver: true,
          waiting: { milestoneId: 'm1', number: 1, title: 'Báo cáo' },
          handovers: [
            {
              id: 'h1',
              number: 1,
              milestoneTitle: 'Báo cáo',
              round: 1,
              status: 'VOIDED',
              createdAt: '2026-10-20T03:00:00Z',
              checkBy: '2026-10-27T03:00:00Z',
              auto: false,
              returned: true,
            },
          ],
        }),
      'GET /api/v1/handovers/h1': () =>
        json(
          memberReport({ audience: 'CREW', status: 'VOIDED', decision: null, returned: { at: '2026-11-22T03:00:00Z', reason } }),
        ),
    })
    renderAt('/cong-viec/j1/ban-giao/h1')

    expect(
      await screen.findByText('Bạn và các thành viên khác tạo bàn giao; khách hàng nghiệm thu, yêu cầu sửa hoặc trả lại.'),
    ).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Các lần bàn giao' })
    expect(within(nav).getByRole('link', { name: 'Tạo bàn giao' })).toHaveAttribute('href', '/cong-viec/j1/ban-giao/tao')
    expect(within(nav).getByText('Khách hàng đã trả lại')).toBeInTheDocument()
    expect(await screen.findByText(reason)).toBeInTheDocument()
    // The member reads why, and is told nothing about held money: that line is the Customer's.
    expect(screen.queryByText('Tiền của giai đoạn vẫn được giữ.')).toBeNull()
  })
})
