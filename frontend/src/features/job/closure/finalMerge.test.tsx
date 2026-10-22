import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { Toaster } from '@/components/Toaster'
import { redirectTo } from '@/lib/redirect'
import { fakeBackend, json, signedIn } from '@/test/fakeBackend'
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
    fakeBackend(signedIn('CUSTOMER'), {
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
    fakeBackend(signedIn('CUSTOMER'), {
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
    fakeBackend(signedIn('CUSTOMER'), {
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
    fakeBackend(signedIn('CUSTOMER'), {
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
    fakeBackend(signedIn('MENTOR'), {
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
