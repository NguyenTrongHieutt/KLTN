import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { Toaster } from '@/components/Toaster'
import { fakeBackend, json, signedInUser, inEveryJob } from '@/test/fakeBackend'
import { ClosingChoice } from './ClosingChoice'

const open = {
  id: 'a1',
  status: 'REQUESTED',
  repository: 'quan-may/so-dat-ban',
  deadlineAt: '2026-11-13T03:00:00Z',
  audience: 'CUSTOMER',
  productId: 'pr1',
  choice: {
    deadlineAt: '2026-11-13T03:00:00Z',
    near: false,
    finalMerge: true,
    selfDownload: true,
    intermediaryUrl: 'https://github.com/vibefix-dev/job-7-intermediary',
  },
}

function renderChoice() {
  const router = createMemoryRouter(
    [{ path: '/cong-viec/:jobId', element: <ClosingChoice jobId="j1" version={8} /> }],
    { initialEntries: ['/cong-viec/j1'] },
  )
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

describe('The Closing choice cards (FR-37, FR-38, D10, UX-DR116, Stories 23.10a and 23.10b)', () => {
  it('shows both ways of receiving the code under the deadline chip, neither preselected', async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () => json(open),
    })
    renderChoice()

    expect(await screen.findByText('Chọn trước 13/11')).toBeInTheDocument()
    expect(screen.getByText('Hợp nhất vào dự án của bạn')).toBeInTheDocument()
    expect(screen.getByText(/GitHub Actions trong quan-may\/so-dat-ban chép mã đã nghiệm thu/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Xem bước hợp nhất cuối' })).toHaveAttribute('href', '#hop-nhat-cuoi')
    expect(screen.getByRole('heading', { name: 'Tôi đã tự tải / clone từ GitHub' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Tôi đã tự tải / clone từ GitHub' })).toBeInTheDocument()
    expect(screen.queryByRole('radio')).toBeNull()
    expect(screen.queryByText(/khuyến nghị/)).toBeNull()
  })

  it('links the Intermediary Repository and numbers the steps without asking for a command', async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () => json(open),
    })
    renderChoice()

    expect(await screen.findByRole('link', { name: /Mở kho trung gian/ })).toHaveAttribute(
      'href',
      'https://github.com/vibefix-dev/job-7-intermediary',
    )
    const steps = screen.getByRole('list')
    expect(within(steps).getAllByRole('listitem')).toHaveLength(4)
    expect(steps).toHaveTextContent('Download ZIP')
    expect(screen.getByText('Bạn không cần chạy lệnh nào.')).toBeInTheDocument()
  })

  it('records the code receipt after the confirmation dialog, which names the deletion date', async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    const confirmed = {
      ...open,
      status: 'USED',
      outcome: 'SELF_DOWNLOAD',
      receipt: { confirmedBy: '00000000-0000-7000-8000-000000000001', confirmedAt: '2026-11-10T07:30:00Z' },
      choice: { ...open.choice, finalMerge: false, selfDownload: false },
    }
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () =>
        json({ ...open, choice: { ...open.choice, deletionAt: '2026-12-10T03:00:00Z' } }),
      'POST /api/v1/jobs/j1/code-receipt': async (request) => {
        sent.push(await request.json())
        return json(confirmed)
      },
    })
    renderChoice()

    await user.click(await screen.findByRole('button', { name: 'Tôi đã tự tải / clone từ GitHub' }))
    const dialog = await screen.findByRole('dialog', { name: 'Bạn đã có bản mã cuối cùng?' })
    expect(dialog).toHaveTextContent('đến 10/12')
    expect(dialog).toHaveTextContent('không hoàn tác được')
    await user.click(within(dialog).getByRole('button', { name: 'Tôi đã tự tải / clone từ GitHub' }))

    expect(await screen.findByText('Bạn đã xác nhận đã nhận mã lúc 14:30 10/11.')).toBeInTheDocument()
    expect(sent).toEqual([{ confirmed: true, version: 8 }])
    expect(screen.queryByText('Chọn trước 13/11')).toBeNull()
  })

  /** A Job Cancelled with accepted work: the self-download path only, until the repositories are deleted. */
  it('offers the self-download alone when the Final Merge is no longer open', async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () =>
        json({
          ...open,
          status: 'ENDED',
          endReason: 'CANCELLED',
          deadlineAt: null,
          choice: { ...open.choice, deadlineAt: null, finalMerge: false, selfDownload: true },
        }),
    })
    renderChoice()

    expect(await screen.findByRole('button', { name: 'Tôi đã tự tải / clone từ GitHub' })).toBeInTheDocument()
    expect(screen.queryByText('Hợp nhất vào dự án của bạn')).toBeNull()
    expect(screen.queryByText(/Chọn trước/)).toBeNull()
  })

  it('shows nothing once neither path is open and nothing was received', async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () =>
        json({ ...open, status: 'USED', choice: { ...open.choice, finalMerge: false, selfDownload: false } }),
    })
    renderChoice()

    await vi.waitFor(() => expect(screen.queryByText(/Chọn trước/)).toBeNull())
    expect(screen.queryByRole('button', { name: 'Tôi đã tự tải / clone từ GitHub' })).toBeNull()
  })

  /** Story 23.10b: the deadline passed with neither path taken, so the Job ended without the Customer's word. */
  it('tells the Customer the Job ended unconfirmed and keeps the self-download open until the deletion', async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () =>
        json({
          ...open,
          status: 'ENDED',
          endReason: 'DEADLINE',
          deadlineAt: null,
          outcome: 'UNCONFIRMED',
          choice: {
            ...open.choice,
            deadlineAt: null,
            finalMerge: false,
            selfDownload: true,
            deletionAt: '2026-12-10T03:00:00Z',
          },
        }),
    })
    renderChoice()

    expect(await screen.findByRole('heading', { name: 'Bạn chưa xác nhận đã nhận mã' })).toBeInTheDocument()
    expect(screen.getByText(/Kho trung gian vẫn mở cho bạn đọc và tải đến 10\/12/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Tôi đã tự tải / clone từ GitHub' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Mở kho trung gian/ })).toBeInTheDocument()
    expect(screen.queryByText(/Chọn trước/)).toBeNull()
    expect(screen.queryByText('Hợp nhất vào dự án của bạn')).toBeNull()
  })

  /** The repositories went with the deletion: the ending still reads, with nothing left to download. */
  it('states the unconfirmed ending even once there is nothing left to download', async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1/final-merge': () =>
        json({
          ...open,
          status: 'ENDED',
          endReason: 'DEADLINE',
          deadlineAt: null,
          outcome: 'UNCONFIRMED',
          choice: { deadlineAt: null, near: false, finalMerge: false, selfDownload: false, intermediaryUrl: null },
        }),
    })
    renderChoice()

    expect(await screen.findByRole('heading', { name: 'Bạn chưa xác nhận đã nhận mã' })).toBeInTheDocument()
    expect(screen.getByText(/đến khi nó bị xóa/)).toBeInTheDocument()
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('gives the Mentor one line saying the Customer never confirmed, with no date or action', async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/jobs/j1/final-merge': () =>
        json({
          ...open,
          audience: 'MENTOR',
          status: 'ENDED',
          endReason: 'DEADLINE',
          deadlineAt: null,
          outcome: 'UNCONFIRMED',
          receipt: null,
          merge: null,
          zip: null,
          choice: { ...open.choice, deadlineAt: null, intermediaryUrl: null, finalMerge: false, selfDownload: true },
        }),
    })
    renderChoice()

    expect(await screen.findByText('Khách hàng chưa xác nhận đã nhận code.')).toBeInTheDocument()
    expect(screen.queryByRole('button')).toBeNull()
    expect(screen.queryByRole('link')).toBeNull()
    expect(screen.queryByText(/10\/12/)).toBeNull()
  })

  it('gives the Mentor one read-only line with the deadline, then the outcome', async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/jobs/j1/final-merge': () =>
        json({ ...open, audience: 'MENTOR', receipt: null, merge: null, zip: null }),
    })
    renderChoice()

    expect(await screen.findByText('Khách hàng đang chọn cách nhận mã (hạn 13/11)')).toBeInTheDocument()
    expect(screen.queryByRole('button')).toBeNull()
    expect(screen.queryByRole('link')).toBeNull()
  })

  /** A Job the closure fallback ended has no choice left to report and no outcome of this story's: nothing shows. */
  it('tells the Mentor nothing about a Job that ended another way', async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/jobs/j1/final-merge': () =>
        json({
          ...open,
          audience: 'MENTOR',
          status: 'ENDED',
          endReason: 'ZIP_FALLBACK',
          outcome: null,
          choice: { ...open.choice, deadlineAt: null, finalMerge: false, selfDownload: true },
        }),
    })
    renderChoice()

    await vi.waitFor(() => expect(screen.queryByText(/đang chọn cách nhận mã/)).toBeNull())
    expect(screen.queryByText(/Khách hàng/)).toBeNull()
  })

  it('tells the Mentor the outcome once the Customer took the code themselves', async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/jobs/j1/final-merge': () =>
        json({
          ...open,
          audience: 'MENTOR',
          status: 'USED',
          outcome: 'SELF_DOWNLOAD',
          choice: { ...open.choice, intermediaryUrl: null, finalMerge: false, selfDownload: false },
        }),
    })
    renderChoice()

    expect(await screen.findByText('Khách hàng đã tự tải mã từ kho trung gian.')).toBeInTheDocument()
    expect(screen.queryByText(/Chọn trước/)).toBeNull()
  })
})
