import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { Toaster } from '@/components/Toaster'
import { redirectTo } from '@/lib/redirect'
import { fakeBackend, json, signedInUser, inEveryJob } from '@/test/fakeBackend'
import { FinalMergeStep } from './FinalMergeStep'

vi.mock('@/lib/redirect', () => ({ redirectTo: vi.fn() }))

const requested = {
  id: 'a1',
  status: 'REQUESTED',
  repository: 'quan-may/so-dat-ban',
  owner: 'quan-may',
  deadlineAt: '2026-10-29T03:00:00Z',
  requestWaiting: false,
  requestRefused: false,
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
  vi.mocked(redirectTo).mockReset()
})

describe('The Customer step "Cho phép hợp nhất" (FR-37, AD-10, UX-DR55)', () => {
  it('goes to GitHub with a fresh single-use link, and shows the deadline', async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () => json(requested),
      'POST /api/v1/jobs/j1/final-merge/install-link': () =>
        json({ url: 'https://github.com/apps/vibefix-merge/installations/new?state=s1' }),
    })
    renderStep()

    expect(await screen.findByText('Hạn cho phép: 29/10. Sau hạn này bạn sẽ nhận mã dạng ZIP.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Cài ứng dụng hợp nhất trên GitHub ↗ (github.com)' }))
    await vi.waitFor(() =>
      expect(redirectTo).toHaveBeenCalledWith('https://github.com/apps/vibefix-merge/installations/new?state=s1'),
    )
  })

  it('says the repository once bound, and refuses a wider selection plainly', async () => {
    let bound = false
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () =>
        json(bound ? { ...requested, status: 'BOUND' } : { ...requested, refusal: 'MERGE_APP_WRONG_REPOSITORY' }),
    })
    renderStep()
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Ứng dụng hợp nhất chỉ được cài cho đúng dự án gốc. Hãy chọn lại.',
    )
    expect(screen.getByRole('button', { name: 'Cài lại trên GitHub ↗ (github.com)' })).toBeInTheDocument()

    bound = true
    renderStep()
    expect(await screen.findByText('Đã cho phép cho kho quan-may/so-dat-ban')).toBeInTheDocument()
  })

  it('waits for an organization owner with a message to copy', async () => {
    const user = userEvent.setup()
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () => json({ ...requested, requestWaiting: true, requestOrg: 'quan-may' }),
    })
    renderStep()

    expect(await screen.findByText('Chờ chủ tổ chức duyệt')).toBeInTheDocument()
    expect(screen.getByText(/đang chờ chủ tổ chức quan-may duyệt trên GitHub/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Cài ứng dụng hợp nhất/ })).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Sao chép lời nhắn' }))
    expect(writeText).toHaveBeenCalledWith(
      'Nhờ duyệt giúp yêu cầu cài ứng dụng hợp nhất của vibefix cho đúng kho quan-may/so-dat-ban trên GitHub, trước 29/10. Yêu cầu đang chờ trong phần GitHub Apps của tổ chức quan-may.',
    )
  })

  it('explains a return that installed nothing, and a request no longer listed', async () => {
    let refused = false
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () => json(refused ? { ...requested, requestRefused: true } : requested),
    })
    renderStep('/cong-viec/j1?hop-nhat=chua-thay')
    expect(await screen.findByText(/Chưa thấy ứng dụng hợp nhất được cài cho kho quan-may\/so-dat-ban/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cài lại trên GitHub ↗ (github.com)' })).toBeInTheDocument()

    refused = true
    renderStep()
    expect(await screen.findByText('Yêu cầu chưa được duyệt')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Gửi lại yêu cầu trên GitHub ↗ (github.com)' })).toBeInTheDocument()
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
    expect(screen.queryByText(/Cho phép hợp nhất/)).toBeNull()
  })
})

describe('The Final Merge once bound (FR-37, SG-19, UX-DR35, Story 8.8)', () => {
  const ready = {
    ...requested,
    status: 'BOUND',
    merge: {
      status: 'READY',
      sensitive: [{ path: 'package.json', kind: 'DEPENDENCIES' }],
      workflows: ['.github/workflows/ci.yml'],
    },
  }

  it('shows the whole Job’s Sensitive Changes and merges only after the checkbox', async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () => json(ready),
      'POST /api/v1/jobs/j1/final-merge/confirm': async (request) => {
        sent.push(await request.json())
        return json({ ...ready, merge: { ...ready.merge, status: 'CONFIRMED' } })
      },
    })
    renderStep()

    expect(await screen.findByText('package.json')).toBeInTheDocument()
    expect(screen.getByText('.github/workflows/ci.yml')).toBeInTheDocument()
    expect(screen.getByText('Dự án của bạn đã có sẵn các tệp cấu hình tự động này')).toBeInTheDocument()
    const merge = screen.getByRole('button', { name: 'Hợp nhất vào dự án' })
    expect(merge).toBeDisabled()
    await user.click(screen.getByRole('checkbox', { name: 'Tôi đã xem và đồng ý hợp nhất.' }))
    await user.click(merge)

    expect(await screen.findByText('Đang đưa mã vào dự án của bạn…')).toBeInTheDocument()
    expect(sent).toEqual([{ agreed: true }])
  })

  it('says the code reached the project, with its pull request on GitHub', async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () =>
        json({
          ...ready,
          status: 'USED',
          merge: { ...ready.merge, status: 'MERGED', pullUrl: 'https://github.com/quan-may/so-dat-ban/pull/1' },
        }),
    })
    renderStep()

    expect(await screen.findByText('Đã hợp nhất vào dự án của bạn')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Xem thay đổi trên GitHub/ })).toHaveAttribute(
      'href',
      'https://github.com/quan-may/so-dat-ban/pull/1',
    )
  })

  it('says the code is checked first', async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () => json({ ...ready, merge: { ...ready.merge, status: 'CHECKING' } }),
    })
    renderStep()
    expect(await screen.findByRole('status')).toHaveTextContent('Đang kiểm tra mã trước khi hợp nhất…')
    expect(screen.queryByRole('button', { name: 'Hợp nhất vào dự án' })).toBeNull()
  })
})

describe('N-17, the ZIP Fallback (FR-38, Story 8.9)', () => {
  it('says why, offers the ZIP and a Job that merges it, and asks nothing to be run', async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () =>
        json({
          ...requested,
          status: 'ENDED',
          endReason: 'ZIP_FALLBACK',
          productId: 'pr1',
          zip: { status: 'READY', reason: 'TRIAL_CONFLICT', fileId: 'f1', sensitive: [] },
        }),
    })
    renderStep()

    expect(
      await screen.findByText(/Không thể hợp nhất tự động: dự án gốc đã thay đổi sau khi công việc bắt đầu/),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Tải mã về (tệp ZIP)' })).toHaveAttribute('href', '/api/v1/files/f1')
    expect(screen.getByRole('link', { name: 'Đăng công việc ghép mã' })).toHaveAttribute(
      'href',
      '/san-pham/pr1/cong-viec-moi?ghep-ma=j1',
    )
    expect(screen.getByText(/Bạn không cần chạy lệnh nào/)).toBeInTheDocument()
  })

  it('lets the Customer choose the ZIP after the confirmation dialog', async () => {
    const user = userEvent.setup()
    let declined = false
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () =>
        json(declined ? { ...requested, status: 'ENDED', productId: 'pr1', zip: { status: 'BUILDING', reason: 'DECLINED', sensitive: [] } } : requested),
      'POST /api/v1/jobs/j1/final-merge/decline': () => {
        declined = true
        return json({ ...requested, status: 'ENDED', productId: 'pr1', zip: { status: 'BUILDING', reason: 'DECLINED', sensitive: [] } })
      },
    })
    renderStep()

    await user.click(await screen.findByRole('button', { name: 'Nhận mã dạng ZIP' }))
    const dialog = await screen.findByRole('dialog', { name: 'Nhận mã dạng ZIP thay vì hợp nhất?' })
    expect(dialog).toHaveTextContent('vibefix sẽ không hợp nhất vào quan-may/so-dat-ban')
    expect(dialog).toHaveTextContent('không hoàn tác được')
    await user.click(within(dialog).getByRole('button', { name: 'Nhận mã dạng ZIP' }))

    expect(await screen.findByText('Đang chuẩn bị tệp ZIP…')).toBeInTheDocument()
    expect(declined).toBe(true)
  })
})
