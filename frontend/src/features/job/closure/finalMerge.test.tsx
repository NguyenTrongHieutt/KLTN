import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { Toaster } from '@/components/Toaster'
import { fakeBackend, json, signedInUser, inEveryJob } from '@/test/fakeBackend'
import { FinalMergeStep } from './FinalMergeStep'

const requested = {
  id: 'a1',
  status: 'REQUESTED',
  repository: 'quan-may/so-dat-ban',
  deadlineAt: '2026-10-29T03:00:00Z',
  audience: 'CUSTOMER',
  choice: { deadlineAt: '2026-10-29T03:00:00Z', near: false, finalMerge: true, selfDownload: true },
}

const ready = {
  ...requested,
  merge: {
    status: 'READY',
    sensitive: [{ path: 'package.json', kind: 'DEPENDENCIES' }],
    workflows: [],
  },
}

function renderStep(path = '/cong-viec/j1') {
  const router = createMemoryRouter([{ path: '/cong-viec/:jobId', element: <FinalMergeStep jobId="j1" /> }], {
    initialEntries: [path],
  })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <Toaster>
        <RouterProvider router={router} />
      </Toaster>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('The Final Merge on the Customer’s own Actions (FR-37, UX-DR116, Story 23.9a)', () => {
  it('shows the whole Job’s Sensitive Changes and starts the run only after N-18', async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () => json(ready),
      'POST /api/v1/jobs/j1/final-merge/confirm': async (request) => {
        sent.push(await request.json())
        return json({ ...ready, merge: { ...ready.merge, status: 'RUNNING', run: { state: 'QUEUED' } } }, 202)
      },
    })
    renderStep()

    expect(await screen.findByText('Hạn chọn cách nhận mã: 29/10.')).toBeInTheDocument()
    expect(screen.getByText('package.json')).toBeInTheDocument()
    expect(screen.getByText(/Sau khi bạn hợp nhất nhánh vibefix\/final-… trên GitHub/)).toBeInTheDocument()
    const run = screen.getByRole('button', { name: 'Chép mã vào dự án của tôi' })
    expect(run).toBeDisabled()
    await user.click(screen.getByRole('checkbox', { name: 'Tôi đã xem và muốn chép mã vào dự án của tôi.' }))
    await user.click(run)

    const row = await screen.findByRole('group', { name: 'Hợp nhất cuối' })
    expect(row).toHaveTextContent('Đang chờ GitHub')
    expect(sent).toEqual([{ agreed: true }])
  })

  it('reads the run as running once GitHub started it', async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () =>
        json({
          ...ready,
          merge: {
            ...ready.merge,
            status: 'RUNNING',
            run: { state: 'IN_PROGRESS', htmlUrl: 'https://github.com/quan-may/so-dat-ban/actions/runs/7' },
          },
        }),
    })
    renderStep()

    const row = await screen.findByRole('group', { name: 'Hợp nhất cuối' })
    expect(row).toHaveTextContent('Đang chạy')
    expect(within(row).getByRole('link', { name: /Xem lần chạy/ })).toHaveAttribute(
      'href',
      'https://github.com/quan-may/so-dat-ban/actions/runs/7',
    )
  })

  it('names the branch and links GitHub’s compare page once vibefix read it back', async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () =>
        json({
          ...ready,
          status: 'USED',
          merge: {
            ...ready.merge,
            status: 'DONE',
            branch: 'vibefix/final-j1',
            branchUrl: 'https://github.com/quan-may/so-dat-ban/tree/vibefix/final-j1',
            compareUrl: 'https://github.com/quan-may/so-dat-ban/compare/main...vibefix/final-j1?expand=1',
            run: { state: 'COMPLETED', conclusion: 'success' },
          },
        }),
    })
    renderStep()

    expect(await screen.findByText('Nhánh vibefix/final-j1 đã sẵn sàng')).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Hợp nhất cuối' })).toHaveTextContent('Xong')
    expect(screen.getByRole('link', { name: /Mở trang hợp nhất trên GitHub/ })).toHaveAttribute(
      'href',
      'https://github.com/quan-may/so-dat-ban/compare/main...vibefix/final-j1?expand=1',
    )
    expect(screen.getByRole('link', { name: /Xem nhánh/ })).toHaveAttribute(
      'href',
      'https://github.com/quan-may/so-dat-ban/tree/vibefix/final-j1',
    )
    expect(screen.queryByRole('button', { name: 'Chép mã vào dự án của tôi' })).toBeNull()
  })

  /** UX-DR35, deferred-work 626: the run can never push a workflow file, so N-18's checkbox gives way. */
  it('replaces N-18 with its workflow-file state when the accepted code changes one', async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () =>
        json({ ...ready, merge: { ...ready.merge, workflows: ['.github/workflows/ci.yml'] } }),
    })
    renderStep()

    expect(await screen.findByText('Mã đã nghiệm thu có thay đổi trong thư mục .github/workflows')).toBeInTheDocument()
    expect(screen.getByText('.github/workflows/ci.yml')).toBeInTheDocument()
    expect(screen.getByText(/GitHub không cho bước hợp nhất cuối ghi các tệp này/)).toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Chép mã vào dự án của tôi' })).toBeNull()
    // Story 23.10a: the way forward is the self-download card above, never a ZIP this step offers.
    expect(screen.queryByRole('button', { name: 'Nhận mã dạng ZIP' })).toBeNull()
  })

  it('names the reason of a run that could not finish', async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () =>
        json({
          ...ready,
          merge: {
            ...ready.merge,
            status: 'REFUSED',
            refusal: 'ACTIONS_QUOTA',
            run: { state: 'COMPLETED', conclusion: 'failure' },
          },
        }),
    })
    renderStep()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Chưa chép được mã vào dự án của bạn: GitHub Actions của dự án gốc đã hết số phút trong tháng. Bạn vẫn có thể tự tải mã về từ kho trung gian',
    )
    expect(screen.getByRole('group', { name: 'Hợp nhất cuối' })).toHaveTextContent('Không chạy được')
  })

  /** The refusal ends the row in the same transaction, so the reason stands above the fallback it offers. */
  it('keeps naming the reason once the fallback has taken over', async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () =>
        json({
          ...ready,
          status: 'ENDED',
          endReason: 'ZIP_FALLBACK',
          productId: 'pr1',
          merge: {
            ...ready.merge,
            status: 'REFUSED',
            refusal: 'WORKFLOW_FILES_CHANGED',
            run: null,
          },
          zip: { status: 'READY', reason: 'MERGE_REFUSED', fileId: 'f1', sensitive: [] },
        }),
    })
    renderStep()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Chưa chép được mã vào dự án của bạn: mã đã nghiệm thu có thay đổi trong thư mục .github/workflows, mà GitHub không cho bước này ghi',
    )
    expect(screen.getByRole('link', { name: 'Tải mã về (tệp ZIP)' })).toHaveAttribute('href', '/api/v1/files/f1')
  })

  /**
   * Story 23.10b: a row that ended ZIP_FALLBACK is the one ending still waiting for something, so the step reads
   * again until the fallback's own row appears. Every other ending -- the closing choice's deadline, a cancellation
   * at J10, a proposal that ended -- waits for nothing and no longer polls.
   */
  it('reads again until the fallback a refusal started is stored', async () => {
    const refused = {
      ...ready,
      status: 'ENDED',
      endReason: 'ZIP_FALLBACK',
      productId: 'pr1',
      merge: { ...ready.merge, status: 'REFUSED', refusal: 'MERGE_REFUSED', run: null },
    }
    const stored = { status: 'READY', reason: 'MERGE_REFUSED', fileId: 'f1', sensitive: [] }
    let reads = 0
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () => {
        reads += 1
        return json({ ...refused, zip: reads === 1 ? null : stored })
      },
    })
    renderStep()

    expect(await screen.findByRole('alert')).toHaveTextContent('Chưa chép được mã vào dự án của bạn')
    // The timers are real and RUNNING_POLL_MS is 5 s, so the second read needs more than the default 1 s.
    expect(await screen.findByRole('link', { name: 'Tải mã về (tệp ZIP)' }, { timeout: 20_000 })).toHaveAttribute(
      'href',
      '/api/v1/files/f1',
    )
  }, 30_000)

  it('says the code is checked first', async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () => json({ ...ready, merge: { ...ready.merge, status: 'CHECKING' } }),
    })
    renderStep()
    expect(await screen.findByRole('status')).toHaveTextContent('Đang kiểm tra mã đã nghiệm thu…')
    expect(screen.queryByRole('button', { name: 'Chép mã vào dự án của tôi' })).toBeNull()
  })

  /** Story 23.10a: the Closing choice cards show that outcome alone, so this step steps aside. */
  it('shows nothing once the Customer took the code themselves', async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () =>
        json({
          ...requested,
          status: 'USED',
          outcome: 'SELF_DOWNLOAD',
          receipt: { confirmedBy: '00000000-0000-7000-8000-000000000001', confirmedAt: '2026-10-28T07:30:00Z' },
        }),
    })
    renderStep()

    await vi.waitFor(() => expect(screen.queryByText(/Hạn chọn cách nhận mã/)).toBeNull())
    expect(screen.queryByRole('button', { name: 'Chép mã vào dự án của tôi' })).toBeNull()
  })

  it('shows nothing to someone else', async () => {
    let read = false
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/jobs/j1/final-merge': () => {
        read = true
        return json({ status: 404, code: 'NOT_FOUND' }, 404)
      },
    })
    renderStep()
    await vi.waitFor(() => expect(read).toBe(true))
    expect(screen.queryByText(/Hợp nhất vào dự án của bạn/)).toBeNull()
  })
})

describe('The closure fallback (FR-38, Story 8.9)', () => {
  it('says why, offers the archive and a Job that merges it, and asks nothing to be run', async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () =>
        json({
          ...requested,
          status: 'ENDED',
          endReason: 'ZIP_FALLBACK',
          productId: 'pr1',
          zip: { status: 'READY', reason: 'MERGE_REFUSED', fileId: 'f1', sensitive: [] },
        }),
    })
    renderStep()

    expect(
      await screen.findByText(/Không thể hợp nhất tự động: GitHub không chạy xong được bước chép mã/),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Tải mã về (tệp ZIP)' })).toHaveAttribute('href', '/api/v1/files/f1')
    expect(screen.getByRole('link', { name: 'Đăng công việc ghép mã' })).toHaveAttribute(
      'href',
      '/san-pham/pr1/cong-viec-moi?ghep-ma=j1',
    )
    expect(screen.getByText(/Bạn không cần chạy lệnh nào/)).toBeInTheDocument()
  })

  it('names the Source App when it no longer reads the Source Repository (Story 23.5)', async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () =>
        json({
          ...requested,
          status: 'ENDED',
          endReason: 'ZIP_FALLBACK',
          productId: 'pr1',
          zip: { status: 'READY', reason: 'SOURCE_APP_MISSING', fileId: 'f1', sensitive: [] },
        }),
    })
    renderStep()

    expect(
      await screen.findByText(
        /Không thể hợp nhất tự động: ứng dụng vibefix không còn đọc được dự án gốc: ứng dụng đã bị gỡ, hoặc dự án đã bị xóa\./,
      ),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Tải mã về (tệp ZIP)' })).toHaveAttribute('href', '/api/v1/files/f1')
  })

  /** Story 23.10a (D10): the Customer never starts a ZIP; the step shows only one a failure or the deadline built. */
  it('offers the Customer no way to ask for a ZIP', async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () => json(ready),
    })
    renderStep()

    expect(await screen.findByRole('button', { name: 'Chép mã vào dự án của tôi' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Nhận mã dạng ZIP' })).toBeNull()
  })
})
