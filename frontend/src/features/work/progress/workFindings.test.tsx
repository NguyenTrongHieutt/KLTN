import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { fakeBackend, json, signedIn } from '@/test/fakeBackend'
import { WorkFindings } from './WorkFindings'

const finding = (canRotate: boolean) => ({
  id: 'f1',
  category: 'CREDENTIAL',
  type: 'Khóa bí mật Stripe',
  path: 'server/pay.js',
  commit: 'a1b2c3d',
  maskedPreview: 'sk_l••••••••Q7mZ',
  createdAt: '2026-10-21T03:00:00Z',
  canRotate,
})

function renderFindings() {
  render(
    <QueryClientProvider client={createQueryClient()}>
      <WorkFindings jobId="j1" />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('The Job’s push Findings (FR-8, UX-DR31)', () => {
  it('lets the Mentor confirm a rotated credential, and then shows nothing', async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/jobs/j1/findings': () => json([finding(true)]),
      'POST /api/v1/jobs/j1/findings/f1/rotation': () => json([]),
    })
    renderFindings()

    expect(await screen.findByRole('heading', { name: '1 vấn đề bảo mật trong mã của công việc' })).toBeInTheDocument()
    expect(screen.getByText('sk_l••••••••Q7mZ')).toBeInTheDocument()
    expect(screen.getByText('server/pay.js · commit a1b2c3d')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Đã đổi khóa' }))

    await waitFor(() => expect(screen.queryByRole('heading', { name: /vấn đề bảo mật/ })).toBeNull())
  })

  it('shows the Customer the Finding without the rotation', async () => {
    fakeBackend(signedIn('CUSTOMER'), { 'GET /api/v1/jobs/j1/findings': () => json([finding(false)]) })
    renderFindings()

    expect(await screen.findByText('Khóa bí mật Stripe')).toBeInTheDocument()
    expect(screen.getByText('Bàn giao tiếp theo chờ đến khi các vấn đề này được xử lý.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Đã đổi khóa' })).toBeNull()
  })
})
