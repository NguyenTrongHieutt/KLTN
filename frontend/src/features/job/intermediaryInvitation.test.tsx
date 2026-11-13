import { render, screen } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { redirectTo } from '@/lib/redirect'
import { fakeBackend, json, signedInUser } from '@/test/fakeBackend'
import { IntermediaryInvitationPage } from './IntermediaryInvitationPage'

vi.mock('@/lib/redirect', () => ({ redirectTo: vi.fn() }))

const INVITATIONS = 'https://github.com/vibefix-dev/job-1-intermediary/invitations'

/** The Setup as the Customer reads it once the team is Active, with `people` as given. */
const setup = (people: unknown[]) => ({
  jobId: 'j1',
  jobState: 'ACTIVE',
  viewer: 'CUSTOMER',
  productId: 'p1',
  status: 'DONE',
  steps: [],
  canRetry: false,
  people,
})

function renderPage() {
  const router = createMemoryRouter(
    [{ path: '/cong-viec/:jobId/kho-trung-gian', element: <IntermediaryInvitationPage /> }],
    { initialEntries: ['/cong-viec/j1/kho-trung-gian'] },
  )
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.mocked(redirectTo).mockReset()
})

describe("The Customer's read invitation link (R5, Story 23.7)", () => {
  it('sends the Customer on to their invitation on GitHub, with a link should the browser stay', async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/setup': () =>
        json(
          setup([
            { role: 'MENTOR', name: 'Đỗ Mạnh Hùng', state: 'WRITE' },
            { role: 'CUSTOMER', name: '@lan-dev', state: 'INVITED', githubUrl: INVITATIONS },
          ]),
        ),
    })
    renderPage()

    expect(await screen.findByText('Đang mở lời mời trên GitHub…')).toBeInTheDocument()
    expect(redirectTo).toHaveBeenCalledWith(INVITATIONS)
    expect(screen.getByRole('link', { name: /Mở trên GitHub/ })).toHaveAttribute('href', INVITATIONS)
  })

  it('says there is no open invitation when the Customer holds no live read', async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/setup': () => json(setup([{ role: 'MENTOR', name: 'Đỗ Mạnh Hùng', state: 'WRITE' }])),
    })
    renderPage()

    expect(
      await screen.findByText('Bạn không có lời mời đọc kho trung gian nào đang mở ở công việc này.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Về trang công việc' })).toHaveAttribute('href', '/cong-viec/j1')
    expect(redirectTo).not.toHaveBeenCalled()
  })
})
