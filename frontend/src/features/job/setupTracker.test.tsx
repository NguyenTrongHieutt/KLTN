import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { Toaster } from '@/components/Toaster'
import messages from '@/i18n/vi.json'
import { fakeBackend, json, signedInUser } from '@/test/fakeBackend'
import { SetupTracker } from './SetupTracker'

const HEAD = 'e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3'
const MOVED = 'f00ba12345678901234567890123456789012345'
const SOURCE_URL = 'https://github.com/lan-nguyen/so-dat-ban'

/** 07:05 UTC: 14:05 on 13/11 in Vietnam. */
const READ_AT = '2026-11-13T07:05:00Z'

const KEYS = ['CHECK', 'CONFIRM', 'REPOSITORIES', 'COPY', 'INVITE', 'VERIFY']

/** The steps' states in order, the rest pending. */
const steps = (...states: string[]) => KEYS.map((key, index) => ({ key, state: states[index] ?? 'PENDING' }))

/** The Setup as the Customer reads it, unless `extra` names another viewer. */
const setup = (status: string, states: string[], extra: Record<string, unknown> = {}) => ({
  jobId: 'j1',
  jobState: 'STARTING',
  viewer: 'CUSTOMER',
  productId: 'p1',
  sourceFullName: 'lan-nguyen/so-dat-ban',
  defaultBranch: 'main',
  sourceUrl: SOURCE_URL,
  status,
  steps: steps(...states),
  canRetry: false,
  people: [],
  ...extra,
})

/** Waiting for the Customer at the head read: the check done, the confirmation current. */
const confirming = (extra: Record<string, unknown> = {}) =>
  setup('CONFIRMING', ['DONE', 'CURRENT'], { head: { sha: HEAD, readAt: READ_AT }, ...extra })

/** Copying the confirmed head on the Customer's Actions. */
const copying = (run: Record<string, unknown>, extra: Record<string, unknown> = {}) =>
  setup('RUNNING', ['DONE', 'DONE', 'DONE', 'CURRENT'], {
    head: { sha: HEAD, readAt: READ_AT },
    confirmation: { sha: HEAD, confirmedAt: READ_AT },
    run,
    ...extra,
  })

/** Blocked at the copy: the run's own reason. */
const copyBlocked = (reason: string, extra: Record<string, unknown> = {}) =>
  copying(
    { state: 'COMPLETED', conclusion: 'failure' },
    { status: 'BLOCKED', blockedReason: reason, ...extra, steps: steps('DONE', 'DONE', 'DONE', 'BLOCKED') },
  )

const mentor = { viewer: 'MENTOR' }

function renderTracker() {
  render(
    <MemoryRouter>
      <QueryClientProvider client={createQueryClient()}>
        <Toaster>
          <SetupTracker jobId="j1" />
        </Toaster>
      </QueryClientProvider>
    </MemoryRouter>,
  )
}

/** The copy's Actions run status row. */
const copyRow = () => screen.getByRole('group', { name: 'Sao chép dự án gốc' })

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('JOB-H step tracker while Starting (UX-DR114, AD-11, Story 23.6c)', () => {
  it("shows the Mentor EXPERIENCE.md's steps in order, the confirmed head, and that they need not act", async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/setup': () =>
        json(
          setup('RUNNING', ['DONE', 'DONE', 'CURRENT'], {
            ...mentor,
            head: { sha: HEAD, readAt: READ_AT },
            confirmation: { sha: HEAD, confirmedAt: READ_AT },
          }),
        ),
    })
    renderTracker()

    expect(await screen.findByRole('heading', { name: 'Chuẩn bị kho mã' })).toBeInTheDocument()
    const items = within(screen.getByRole('list')).getAllByRole('listitem')
    expect(items.map((item) => item.querySelector('span')?.textContent)).toEqual([
      'Kiểm tra ứng dụng vibefix và file workflow',
      'Khách hàng xác nhận dự án gốc đã sạch',
      'Tạo kho trung gian và kho làm việc (riêng tư)',
      'Sao chép trên GitHub Actions của khách hàng',
      'Mời trên GitHub',
      'Cấp quyền ghi và kiểm tra',
    ])
    expect(screen.getByText(/Đã xác nhận lần lưu/)).toHaveTextContent('Đã xác nhận lần lưu e4f5a6b')
    expect(screen.getByText('e4f5a6b').tagName).toBe('CODE')
    expect(screen.getByText('Bạn không cần làm gì.')).toBeInTheDocument()
    expect(screen.getByText('Tạo kho trung gian và kho làm việc (riêng tư): Đang làm')).toHaveAttribute('aria-live', 'polite')
  })

  it('tells the Mentor to wait for the Customer, with no card', async () => {
    fakeBackend(signedInUser, { 'GET /api/v1/jobs/j1/setup': () => json(confirming(mentor)) })
    renderTracker()

    expect(await screen.findByText('Chờ khách hàng xác nhận')).toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Xác nhận và bắt đầu sao chép' })).toBeNull()
    expect(screen.getByText('Bạn không cần làm gì.')).toBeInTheDocument()
  })

  it('shows the Customer the SHA confirmation card, enabled once ticked, and records the head with N-32 1.0', async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/setup': () => json(confirming()),
      'POST /api/v1/jobs/j1/setup/confirmation': async (request) => {
        sent.push(await request.json())
        return json(copying({ state: 'DISPATCHED' }))
      },
    })
    renderTracker()

    expect(await screen.findByText('Bạn xác nhận dự án gốc đã sạch')).toBeInTheDocument()
    expect(screen.getByText('Dự án gốc: lan-nguyen/so-dat-ban')).toBeInTheDocument()
    expect(screen.getByText(/Lần lưu mới nhất:/)).toHaveTextContent('Lần lưu mới nhất: e4f5a6b · 14:05 13/11')
    expect(screen.getByRole('button', { name: 'Sao chép mã lần lưu' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Xác nhận dự án gốc đã sạch' })).toBeInTheDocument()
    const box = screen.getByRole('checkbox', {
      name: /Tôi xác nhận: trong toàn bộ lịch sử của lan-nguyen\/so-dat-ban đến lần lưu e4f5a6b, mọi khóa bí mật/,
    })
    expect(screen.getByRole('button', { name: 'Chưa chắc? Xem lại hướng dẫn' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Tự kiểm tra khóa bí mật trên GitHub/ })).toHaveAttribute(
      'href',
      `${SOURCE_URL}/actions/workflows/vibefix.yml`,
    )
    // The confirmation step is the Customer's own: they are never told they need not act on it.
    expect(screen.queryByText('Bạn không cần làm gì.')).toBeNull()
    const confirm = screen.getByRole('button', { name: 'Xác nhận và bắt đầu sao chép' })
    expect(confirm).toBeDisabled()

    await user.click(box)
    expect(confirm).toBeEnabled()
    await user.click(confirm)

    expect(await screen.findByText(/Đã xác nhận lần lưu/)).toHaveTextContent('Đã xác nhận lần lưu e4f5a6b lúc 14:05 13/11')
    expect(sent).toEqual([{ sha: HEAD, noticeVersion: '1.0' }])
    expect(messages.notice.selfConfirmation.version).toBe('1.0')
    expect(screen.getByText('Sao chép trên GitHub Actions của bạn')).toBeInTheDocument()
    expect(within(copyRow()).getByText('Đang chờ GitHub')).toHaveTextContent('Trạng thái lần chạy: Đang chờ GitHub')
  })

  it('keeps the confirmation disabled until the Setup names the repository', async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/setup': () => json(confirming({ sourceFullName: undefined, sourceUrl: undefined })),
    })
    renderTracker()

    await user.click(await screen.findByRole('checkbox'))

    expect(screen.getByRole('button', { name: 'Xác nhận và bắt đầu sao chép' })).toBeDisabled()
    expect(screen.queryByText(/^Dự án gốc:/)).toBeNull()
  })

  it('copies the full head and says so', async () => {
    const user = userEvent.setup()
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
    fakeBackend(signedInUser, { 'GET /api/v1/jobs/j1/setup': () => json(confirming()) })
    renderTracker()

    await user.click(await screen.findByRole('button', { name: 'Sao chép mã lần lưu' }))

    expect(writeText).toHaveBeenCalledWith(HEAD)
    expect(HEAD).toHaveLength(40)
    expect(await screen.findByText('Đã sao chép mã lần lưu.')).toBeInTheDocument()
  })

  it("opens N-2's guidance in a Sheet", async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, { 'GET /api/v1/jobs/j1/setup': () => json(confirming()) })
    renderTracker()

    await user.click(await screen.findByRole('button', { name: 'Chưa chắc? Xem lại hướng dẫn' }))

    const sheet = await screen.findByRole('dialog', { name: 'Tự kiểm tra trước khi người khác đọc mã' })
    expect(within(sheet).getByText(messages.notice.product.guidance)).toBeInTheDocument()
    expect(within(sheet).getByRole('link', { name: /Mở trang khóa của Supabase/ })).toBeInTheDocument()
  })

  it('opens the Stale dialog when the head moved, and shows the new head unticked', async () => {
    const user = userEvent.setup()
    let head = HEAD
    const seen = fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/setup': () => json(confirming({ head: { sha: head, readAt: READ_AT } })),
      'POST /api/v1/jobs/j1/setup/confirmation': () => {
        head = MOVED
        return json({ status: 409, code: 'STALE', head: MOVED }, 409)
      },
    })
    renderTracker()

    await user.click(await screen.findByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: 'Xác nhận và bắt đầu sao chép' }))

    const dialog = await screen.findByRole('dialog', { name: 'Thông tin đã thay đổi trong lúc bạn xem.' })
    expect(within(dialog).getByText(/lần xác nhận của bạn chưa được gửi/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Tải lại và xem' }))

    expect(await screen.findAllByText('f00ba12')).not.toHaveLength(0)
    expect(screen.getByText(/Lần lưu mới nhất:/)).toHaveTextContent('Lần lưu mới nhất: f00ba12 · 14:05 13/11')
    expect(screen.getByRole('checkbox')).not.toBeChecked()
    expect(screen.getByRole('button', { name: 'Xác nhận và bắt đầu sao chép' })).toBeDisabled()
    expect(seen.filter((request) => request.method === 'POST')).toHaveLength(1)
  })

  it.each([
    [422, { code: 'VALIDATION', reason: 'NOTICE_REQUIRED' }, messages.screen.job.setup.card.noticeChanged],
    [
      409,
      { code: 'CONFLICT', reason: 'SETUP_NOT_CONFIRMING' },
      'Không thể làm việc này vì dữ liệu đã thay đổi. Việc chuẩn bị kho mã không còn chờ bạn xác nhận. Hãy tải lại trang.',
    ],
    [
      409,
      { code: 'STALE', reason: 'JOB_NOT_STARTING' },
      'Thông tin đã thay đổi trong lúc bạn xem. Công việc không còn ở bước chờ bắt đầu.',
    ],
    [422, { code: 'GUARD_FAILED', reason: 'SOURCE_APP_MISSING' }, undefined],
  ])('answers a %i %o refusal of the confirmation without the head-moved dialog', async (status, problem, text) => {
    const user = userEvent.setup()
    const seen = fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/setup': () => json(confirming()),
      'POST /api/v1/jobs/j1/setup/confirmation': () => json({ status, ...problem }, status),
    })
    renderTracker()

    await user.click(await screen.findByRole('checkbox'))
    const reads = () => seen.filter((request) => request.method === 'GET' && request.url.endsWith('/setup')).length
    const before = reads()
    await user.click(screen.getByRole('button', { name: 'Xác nhận và bắt đầu sao chép' }))

    if (text) {
      expect(await screen.findByRole('alert')).toHaveTextContent(text)
    } else {
      // The Source App's block blocks the Setup: it is read again, and the tracker then says why.
      await vi.waitFor(() => expect(reads()).toBeGreaterThan(before))
      expect(screen.queryByRole('alert')).toBeNull()
    }
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.queryAllByRole('button', { name: 'Tải lại trang' })).toHaveLength(
      problem.reason === 'NOTICE_REQUIRED' ? 1 : 0,
    )
  })

  it('brings the card back after the head moved past the confirmation (sha)', async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/setup': () => json(confirming({ head: { sha: MOVED, readAt: READ_AT }, exchangeReason: 'sha' })),
    })
    renderTracker()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Dự án gốc có lần lưu mới (f00ba12) sau khi bạn xác nhận. Hãy xác nhận lại cho lần lưu này.',
    )
    expect(screen.getByRole('button', { name: 'Xác nhận và bắt đầu sao chép' })).toBeDisabled()
  })

  it('tells the Mentor that the Customer confirms again after the head moved', async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/setup': () =>
        json(confirming({ ...mentor, head: { sha: MOVED, readAt: READ_AT }, exchangeReason: 'sha' })),
    })
    renderTracker()

    expect(await screen.findByText(/sau khi khách hàng xác nhận/)).toHaveTextContent(
      'Dự án gốc có lần lưu mới (f00ba12) sau khi khách hàng xác nhận. Chờ khách hàng xác nhận lại cho lần lưu này.',
    )
  })

  it.each([
    ['DISPATCHED', undefined, 'Đang chờ GitHub'],
    ['QUEUED', undefined, 'Đang chờ GitHub'],
    ['IN_PROGRESS', undefined, 'Đang chạy'],
    // Completed, but the copy is read back first: still running.
    ['COMPLETED', 'success', 'Đang chạy'],
    // Completed without success while the step is current: vibefix runs it again, so it is no failure yet.
    ['COMPLETED', 'failure', 'Đang chạy'],
  ])('shows a %s (%s) run on the current copy step as "%s"', async (state, conclusion, badge) => {
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/setup': () => json(copying({ state, conclusion }, mentor)),
    })
    renderTracker()

    const row = await screen.findByRole('group', { name: 'Sao chép dự án gốc' })
    expect(within(row).getByText(badge)).toBeInTheDocument()
    expect(within(row).queryByText('Xong')).toBeNull()
    expect(within(row).queryByText('Không chạy được')).toBeNull()
  })

  it("links the Customer's own run while it runs", async () => {
    const run = { state: 'IN_PROGRESS', htmlUrl: `${SOURCE_URL}/actions/runs/42` }
    fakeBackend(signedInUser, { 'GET /api/v1/jobs/j1/setup': () => json(copying(run)) })
    renderTracker()

    expect(await screen.findByText('Đang chạy')).toHaveTextContent('Trạng thái lần chạy: Đang chạy')
    expect(within(copyRow()).getByRole('link', { name: /Xem lần chạy/ })).toHaveAttribute('href', run.htmlUrl)
    expect(screen.getByText('Bạn không cần làm gì.')).toBeInTheDocument()
  })

  it('never links the run for the Crew, whose repository it is not', async () => {
    const run = { state: 'IN_PROGRESS', htmlUrl: `${SOURCE_URL}/actions/runs/42` }
    fakeBackend(signedInUser, { 'GET /api/v1/jobs/j1/setup': () => json(copying(run, { viewer: 'CREW' })) })
    renderTracker()

    expect(await screen.findByText('Sao chép trên GitHub Actions của khách hàng')).toBeInTheDocument()
    expect(screen.getByText('Đang chạy')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Xem lần chạy/ })).toBeNull()
  })

  it('shows the copy as done only once its step is done', async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/setup': () =>
        json(
          copying(
            { state: 'COMPLETED', conclusion: 'success' },
            { ...mentor, status: 'DONE', steps: steps('DONE', 'DONE', 'DONE', 'DONE', 'CURRENT') },
          ),
        ),
    })
    renderTracker()

    const row = await screen.findByRole('group', { name: 'Sao chép dự án gốc' })
    expect(within(row).getByText('Xong')).toHaveTextContent('Trạng thái lần chạy: Xong')
  })

  it('names the quota for the Customer in the row, with "Thử lại"', async () => {
    const user = userEvent.setup()
    const seen = fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/setup': () => json(copyBlocked('ACTIONS_QUOTA', { canRetry: true })),
      'POST /api/v1/jobs/j1/setup/retry': () => json(setup('CONFIRMING', ['CURRENT'])),
    })
    renderTracker()

    const row = await screen.findByRole('group', { name: 'Sao chép dự án gốc' })
    expect(within(row).getByText('Không chạy được')).toBeInTheDocument()
    expect(within(row).getByText(messages.screen.job.setup.blockedACTIONS_QUOTA)).toBeInTheDocument()
    await user.click(within(row).getByRole('button', { name: 'Thử lại' }))
    expect(seen.some((request) => request.method === 'POST' && request.url.endsWith('/setup/retry'))).toBe(true)
  })

  it.each([
    ['EXCHANGE_REFUSED', 'blob', messages.screen.job.setup.blockedExchangeBlob, true],
    [
      'EXCHANGE_REFUSED',
      'actor',
      'GitHub chưa xác nhận được lần chạy này. vibefix đã báo quản trị viên; bạn chưa cần làm gì.',
      false,
    ],
    ['EXCHANGE_REFUSED', undefined, messages.screen.job.setup.blockedEXCHANGE_REFUSED, false],
    ['RUN_FAILED', undefined, messages.screen.job.setup.blockedRUN_FAILED, true],
  ])('tells the Customer about a %s (%s) copy in its row, with "Thử lại" only where it helps', async (reason, exchangeReason, text, retry) => {
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/setup': () => json(copyBlocked(reason, { exchangeReason, canRetry: true })),
    })
    renderTracker()

    const row = await screen.findByRole('group', { name: 'Sao chép dự án gốc' })
    expect(within(row).getByText(text)).toBeInTheDocument()
    expect(within(row).getByText('Không chạy được')).toBeInTheDocument()
    expect(within(row).queryAllByRole('button', { name: 'Thử lại' })).toHaveLength(retry ? 1 : 0)
    expect(screen.queryAllByRole('button', { name: 'Thử lại' })).toHaveLength(retry ? 1 : 0)
  })

  it('gives the Customer the published vibefix.yml to put back after a blob refusal', async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/setup': () => json(copyBlocked('EXCHANGE_REFUSED', { exchangeReason: 'blob', canRetry: true })),
    })
    renderTracker()

    const row = await screen.findByRole('group', { name: 'Sao chép dự án gốc' })
    expect(await within(row).findByRole('link', { name: /Sửa file workflow/ })).toHaveAttribute(
      'href',
      `${SOURCE_URL}/edit/main/.github/workflows/vibefix.yml`,
    )
    expect(within(row).getByRole('button', { name: 'Sao chép nội dung' })).toBeInTheDocument()
    expect(within(row).getByText(/rồi quay lại đây và bấm “Thử lại”/)).toBeInTheDocument()
  })

  it('says RUN_FAILED starts over from the confirmation of the newest commit', () => {
    expect(messages.screen.job.setup.blockedRUN_FAILED).toContain(
      'Bấm Thử lại để bắt đầu lại từ bước xác nhận, cho lần lưu mới nhất của dự án gốc.',
    )
  })

  it.each([
    ['RUN_FAILED', undefined, 'Lần chạy trên GitHub Actions chưa xong: sao chép dự án gốc. vibefix đã báo quản trị viên.'],
    ['EXCHANGE_REFUSED', 'blob', messages.screen.job.setup.teamExchangeBlob],
    ['EXCHANGE_REFUSED', 'run', messages.screen.job.setup.teamEXCHANGE_REFUSED],
    ['ACTIONS_QUOTA', undefined, 'GitHub Actions của dự án gốc đã hết số phút trong tháng, nên việc sao chép chưa chạy được.'],
  ])("gives the Mentor a %s (%s) block's own text, never the Customer's actions", async (reason, exchangeReason, text) => {
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/setup': () => json(copyBlocked(reason, { ...mentor, exchangeReason })),
    })
    renderTracker()

    const row = await screen.findByRole('group', { name: 'Sao chép dự án gốc' })
    expect(within(row).getByText(text)).toBeInTheDocument()
    expect(screen.queryByText(/Đang chờ khách hàng xử lý/)).toBeNull()
    expect(screen.queryByRole('button', { name: 'Thử lại' })).toBeNull()
    expect(screen.queryByRole('link', { name: /file workflow/ })).toBeNull()
  })

  it('tells the Customer what to fix after a block, and starts again on "Thử lại"', async () => {
    const user = userEvent.setup()
    const seen = fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/setup': () =>
        json(setup('BLOCKED', ['BLOCKED'], { blockedReason: 'NO_ADMIN_PERMISSION', canRetry: true })),
      'POST /api/v1/jobs/j1/setup/retry': () => json(setup('CONFIRMING', ['CURRENT'])),
    })
    renderTracker()

    expect(
      await screen.findByText('Tài khoản GitHub của bạn không còn quyền quản trị dự án gốc. Hãy lấy lại quyền rồi bấm Thử lại.'),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Thử lại' }))

    // The check is not the Customer's step.
    expect(await screen.findByText('Bạn không cần làm gì.')).toBeInTheDocument()
    expect(seen.some((request) => request.method === 'POST' && request.url.endsWith('/api/v1/jobs/j1/setup/retry'))).toBe(true)
  })

  it('says why a retry was refused, naming what keeps the Job busy', async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/setup': () =>
        json(setup('BLOCKED', ['BLOCKED'], { blockedReason: 'NO_ADMIN_PERMISSION', canRetry: true })),
      'POST /api/v1/jobs/j1/setup/retry': () => json({ status: 409, code: 'JOB_BUSY' }, 409),
    })
    renderTracker()

    await user.click(await screen.findByRole('button', { name: 'Thử lại' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Đợi việc đang chạy trên công việc này xong rồi thử lại.')
  })

  it.each([
    ['SOURCE_APP_MISSING', messages.screen.job.setup.blockedSOURCE_APP_MISSING],
    ['SOURCE_APP_WRONG_REPOSITORY', messages.screen.job.setup.blockedSOURCE_APP_WRONG_REPOSITORY],
  ])('sends the Customer to the Product page to fix a %s block', async (reason, text) => {
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/setup': () => json(setup('BLOCKED', ['BLOCKED'], { blockedReason: reason, canRetry: true })),
    })
    renderTracker()

    expect(await screen.findByText(text)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Mở trang sản phẩm' })).toHaveAttribute('href', '/san-pham/p1')
    expect(screen.getByRole('button', { name: 'Thử lại' })).toBeInTheDocument()
  })

  it.each([
    ['WORKFLOW_MISSING', messages.screen.job.setup.blockedWORKFLOW_MISSING, /Thêm file workflow/, `${SOURCE_URL}/new/main?`],
    ['WORKFLOW_MISMATCH', messages.screen.job.setup.blockedWORKFLOW_MISMATCH, /Sửa file workflow/, `${SOURCE_URL}/edit/main/`],
  ])('gives the Customer the published vibefix.yml to add after a %s block', async (reason, text, link, href) => {
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/setup': () => json(setup('BLOCKED', ['BLOCKED'], { blockedReason: reason, canRetry: true })),
    })
    renderTracker()

    expect(await screen.findByText(text)).toBeInTheDocument()
    expect((await screen.findByRole('link', { name: link })).getAttribute('href')).toContain(href)
    expect(screen.getByRole('button', { name: 'Thử lại' })).toBeInTheDocument()
  })

  it('shows the Mentor what the Customer must fix, without a retry or the means to fix it', async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/setup': () =>
        json(setup('BLOCKED', ['BLOCKED'], { ...mentor, blockedReason: 'SOURCE_APP_MISSING' })),
    })
    renderTracker()

    expect(await screen.findByText('Ứng dụng vibefix chưa được cài trên dự án gốc. Đang chờ khách hàng cài lại.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Thử lại' })).toBeNull()
    expect(screen.queryByRole('link', { name: 'Mở trang sản phẩm' })).toBeNull()
  })

  it('reads a block it does not know neutrally to the team', async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/setup': () => json(setup('BLOCKED', ['BLOCKED'], { ...mentor, blockedReason: 'SOMETHING_NEW' })),
    })
    renderTracker()

    expect(await screen.findByText('Việc chuẩn bị kho mã đang tạm dừng.')).toBeInTheDocument()
    expect(screen.queryByText(/khách hàng xử lý/)).toBeNull()
  })

  it("lists each invitee with their state and a GitHub link on the viewer's own row", async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/setup': () =>
        json(
          setup('DONE', ['DONE', 'DONE', 'DONE', 'DONE', 'CURRENT', 'PENDING'], {
            viewer: 'CREW',
            people: [
              { role: 'MENTOR', name: 'Đỗ Mạnh Hùng', state: 'ACCEPTED' },
              {
                role: 'CREW',
                name: 'Lê Thảo Vy',
                state: 'INVITED',
                expiresAt: '2026-10-28T03:00:00Z',
                githubUrl: 'https://github.com/vibefix-dev/job-1-working/invitations',
              },
              { role: 'CREW', name: '@hai-dev', state: 'TWO_FACTOR_MISSING' },
            ],
          }),
        ),
    })
    renderTracker()

    expect(await screen.findByText('Mời trên GitHub')).toBeInTheDocument()
    expect(screen.getByText('· đã chấp nhận')).toBeInTheDocument()
    expect(screen.getByText('· chờ chấp nhận lời mời · hết hạn 28/10')).toBeInTheDocument()
    expect(screen.getByText('· cần bật xác thực hai lớp')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Mở trên GitHub/ })).toHaveAttribute(
      'href',
      'https://github.com/vibefix-dev/job-1-working/invitations',
    )
    // Spike S4 under 2FA: GitHub greys out the acceptance until it is on, so the own row says it first.
    expect(screen.getByText(/Bật xác thực hai lớp trên GitHub trước \(nếu chưa bật\), rồi chấp nhận lời mời/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /^Bật xác thực hai lớp/ })).toHaveAttribute('href', 'https://github.com/settings/security')
    expect(screen.queryByText('Bạn không cần làm gì.')).not.toBeInTheDocument()
    expect(screen.getByText('Mời trên GitHub: Đang làm')).toHaveAttribute('aria-live', 'polite')
  })

  it('says the start ended, not that administrators were told', async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/setup': () => json(setup('ENDED', ['DONE', 'PENDING'], { head: { sha: HEAD, readAt: READ_AT } })),
    })
    renderTracker()

    expect(await screen.findByText('Công việc không còn ở bước bắt đầu, nên việc chuẩn bị kho mã đã dừng.')).toBeInTheDocument()
    expect(screen.queryByText(/quản trị viên/)).toBeNull()
    expect(screen.queryByRole('checkbox')).toBeNull()
  })
})
