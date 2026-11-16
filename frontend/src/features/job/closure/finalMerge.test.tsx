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
  productId: 'pr1',
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
    // Story 23.10c: the way forward is the self-download card above; vibefix stores no ZIP of its own any more.
    expect(screen.queryByRole('link', { name: /ZIP/ })).toBeNull()
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

  /**
   * Story 23.10c: a refusal leaves the closing choice open, so the reason keeps its own notice -- with the Job's
   * Sensitive Changes and "Đăng công việc ghép mã" under it, and no ZIP of vibefix's own anywhere.
   */
  it('keeps the refusal, its Sensitive Changes and the merge-Job link', async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () =>
        json({
          ...ready,
          merge: { ...ready.merge, status: 'REFUSED', refusal: 'WORKFLOW_FILES_CHANGED', run: null },
        }),
    })
    renderStep()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Chưa chép được mã vào dự án của bạn: mã đã nghiệm thu có thay đổi trong thư mục .github/workflows, mà GitHub không cho bước này ghi',
    )
    expect(screen.getByText('package.json')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Đăng công việc ghép mã' })).toHaveAttribute(
      'href',
      '/san-pham/pr1/cong-viec-moi?ghep-ma=j1',
    )
    expect(screen.queryByRole('link', { name: /ZIP/ })).toBeNull()
  })

  /**
   * Story 23.10c: nothing follows a refusal any more, so the step reads once and stops. It used to read again until
   * the closure fallback's own row appeared.
   */
  it('reads once after a refusal and does not poll', async () => {
    let reads = 0
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () => {
        reads += 1
        return json({ ...ready, merge: { ...ready.merge, status: 'REFUSED', refusal: 'MERGE_REFUSED', run: null } })
      },
    })
    renderStep()

    expect(await screen.findByRole('alert')).toHaveTextContent('Chưa chép được mã vào dự án của bạn')
    // The timers are real and RUNNING_POLL_MS is 5 s: a step that still polled would have read again by now.
    await new Promise((resolve) => setTimeout(resolve, 6_000))
    expect(reads).toBe(1)
  }, 20_000)

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

describe('A Final Merge that could not run (FR-38, D10, Story 23.10c)', () => {
  /** The deadline ends the row after the refusal, and the reason it carries is still the Customer's answer. */
  it('still names its reason and offers the merge Job once the row has ended', async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () =>
        json({
          ...ready,
          status: 'ENDED',
          endReason: 'DEADLINE',
          deadlineAt: null,
          merge: { ...ready.merge, status: 'REFUSED', refusal: 'ACTIONS_QUOTA', run: null },
          choice: { deadlineAt: null, near: false, finalMerge: false, selfDownload: true },
        }),
    })
    renderStep()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Chưa chép được mã vào dự án của bạn: GitHub Actions của dự án gốc đã hết số phút trong tháng',
    )
    expect(screen.getByRole('link', { name: 'Đăng công việc ghép mã' })).toHaveAttribute(
      'href',
      '/san-pham/pr1/cong-viec-moi?ghep-ma=j1',
    )
    expect(screen.queryByRole('link', { name: /ZIP/ })).toBeNull()
  })

  /** A row that ended with no Final Merge of its own: a cancellation at J10, or a proposal that ended. */
  it('shows nothing for a row that ended with no Final Merge of its own', async () => {
    let read = false
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () => {
        read = true
        return json({
          ...requested,
          status: 'ENDED',
          endReason: 'CANCELLED',
          deadlineAt: null,
          choice: { deadlineAt: null, near: false, finalMerge: false, selfDownload: true },
        })
      },
    })
    renderStep()

    await vi.waitFor(() => expect(read).toBe(true))
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.queryByRole('link', { name: 'Đăng công việc ghép mã' })).toBeNull()
  })
})
