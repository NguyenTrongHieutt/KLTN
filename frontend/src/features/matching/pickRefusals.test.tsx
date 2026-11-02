import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, inEveryJob, json, signedInUser } from '@/test/fakeBackend'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

const SIGN_DUE = '2026-11-01T03:00:00Z'

const card = (overrides: object = {}) => ({
  id: 'a1',
  status: 'PENDING',
  note: 'Mình đã làm kiểm thử phân quyền cho đồ án.',
  skills: [{ id: 'react', name: 'React' }],
  submittedAt: '2026-10-20T03:00:00Z',
  lapsesAt: '2026-11-03T03:00:00Z',
  applicant: { displayName: 'Trần Quang Minh', githubLogin: 'minh-dev', verified: [], memberSince: '2026-06-01T03:00:00Z' },
  proof: [],
  ...overrides,
})

type Handlers = Record<string, (request: Request) => Response | Promise<Response>>

/** JOB-9 for the Job's Mentor, recruiting one more person. */
const jobNine = (overrides: Handlers = {}) => ({
  ...inEveryJob('MENTOR'),
  'GET /api/v1/mentor/jobs/j1': () =>
    json({
      id: 'j1',
      title: 'Sửa lỗi đặt bàn',
      productName: 'Sổ đặt bàn Quán Mây',
      state: 'AGREEMENT',
      version: 3,
      jobTypes: ['BUG_FIX'],
      selectedAt: '2026-10-18T03:00:00Z',
      codeJob: true,
      commitEmailNoticeAcknowledged: true,
      agreementConfirmed: false,
    }),
  'GET /api/v1/mentor/jobs/j1/recruitment': () =>
    json({
      crewVersion: 0,
      state: 'AGREEMENT',
      available: true,
      recruiting: true,
      openSlots: 1,
      maxSlots: 5,
      cap: 5,
      signedMember: false,
      skills: [],
      crew: [],
    }),
  'GET /api/v1/mentor/jobs/j1/crew-applications': () => json([card()]),
  'GET /api/v1/catalog': () => json({ categories: [] }),
  'GET /api/v1/reputation/histories': () => json([]),
  ...overrides,
})

const panel = {
  scope: 'Sửa lỗi hai khách đặt trùng bàn cùng giờ.',
  milestones: [{ title: 'Sửa lỗi trùng bàn', dueOn: '2026-11-10', priceVnd: 2_000_000 }],
  codeJob: true,
  commitEmailNoticeAcknowledged: true,
  githubLogin: 'minh-dev',
  ndaVersion: '1.0',
  commitmentVersion: '1.0',
  splitRules: { mentorShareCapPercent: 40, draftDays: 7, confirmationDays: 3, editLimit: 3 },
}

const commitment = (overrides: object = {}) => ({
  applicationId: 'a1',
  jobId: 'j1',
  jobTitle: 'Sửa lỗi đặt bàn',
  mentorName: 'Đỗ Mạnh Hùng',
  status: 'PICKED',
  pickedAt: '2026-10-29T03:00:00Z',
  signDueAt: SIGN_DUE,
  panel,
  ...overrides,
})

/**
 * MEN-3 for a picked person on a Job no longer listed, as when the pick filled the last place: the Commitment panel
 * still stands, and the person ticks N-5 and signs.
 */
async function signOnMenThree(user: ReturnType<typeof userEvent.setup>, overrides: Handlers) {
  fakeBackend(signedInUser, {
    ...inEveryJob('CREW', 'ASSIGNED'),
    'GET /api/v1/mentor/job-posts/j1': () => json({ status: 404, code: 'NOT_FOUND' }, 404),
    'GET /api/v1/mentor/crew-applications': () => json([]),
    'GET /api/v1/crew/jobs/j1/commitment': () => json(commitment()),
    ...overrides,
  })
  renderAt('/tim-viec/j1')
  const sign = await screen.findByRole('button', { name: 'Ký và tham gia' })
  await user.click(screen.getByRole('checkbox', { name: /Tôi hiểu mã tôi và nhóm làm/ }))
  await user.click(sign)
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('JOB-9 says why a pick was refused, and writes nothing (FR-81, AD-23, Story 21.5)', () => {
  it.each([
    ['CREW_FULL', { n: 5 }, /^Nhóm thực hiện đã đủ 5 người\.$/],
    ['CONTRIBUTORS_UNKNOWN', {}, /^Chưa thể chọn người này: vibefix chưa kiểm tra được kho mã của khách hàng/],
    ['APPLICANT_IS_CONTRIBUTOR', {}, /^Trần Quang Minh đã từng đóng góp vào dự án gốc này nên không vào nhóm được\.$/],
    ['CONFLICT_OF_INTEREST', {}, /^Trần Quang Minh không thể tham gia công việc này\.$/],
    // In the Mentor's words, not "Người hướng dẫn đang không nhận đơn" about themselves.
    ['RECRUITMENT_CLOSED', {}, /^Công việc không còn nhận thêm thành viên\.$/],
  ])('%s', { timeout: 20_000 }, async (reason, extra, text) => {
    const user = userEvent.setup()
    fakeBackend(
      signedInUser,
      jobNine({
        'POST /api/v1/mentor/jobs/j1/crew-applications/a1/pick': () =>
          json({ status: 422, code: 'GUARD_FAILED', reason, ...extra }, 422),
      }),
    )
    renderAt('/cong-viec/j1/tuyen-thanh-vien')

    await user.click(within(await screen.findByRole('listitem', { name: 'Trần Quang Minh' })).getByRole('button', { name: 'Chọn' }))
    await user.click(
      within(await screen.findByRole('dialog', { name: 'Chọn Trần Quang Minh vào nhóm thực hiện?' })).getByRole('button', {
        name: 'Chọn',
      }),
    )

    expect((await screen.findAllByText(text)).length).toBeGreaterThan(0)
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    const item = screen.getByRole('listitem', { name: 'Trần Quang Minh' })
    expect(within(item).getByRole('button', { name: 'Chọn' })).toBeInTheDocument()
    expect(within(item).queryByRole('button', { name: 'Rút lựa chọn' })).toBeNull()
  })

  it('says an application that stopped waiting is no longer pickable', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(
      signedInUser,
      jobNine({
        'POST /api/v1/mentor/jobs/j1/crew-applications/a1/pick': () =>
          json({ status: 409, code: 'CONFLICT', reason: 'NOT_PICKABLE' }, 409),
      }),
    )
    renderAt('/cong-viec/j1/tuyen-thanh-vien')

    await user.click(within(await screen.findByRole('listitem', { name: 'Trần Quang Minh' })).getByRole('button', { name: 'Chọn' }))
    await user.click(
      within(await screen.findByRole('dialog', { name: 'Chọn Trần Quang Minh vào nhóm thực hiện?' })).getByRole('button', {
        name: 'Chọn',
      }),
    )

    expect((await screen.findAllByText('Đơn này không còn chờ chọn. Danh sách đã tải lại.')).length).toBeGreaterThan(0)
  })

  it('refuses to withdraw once the person signed first, since only they can leave', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(
      signedInUser,
      jobNine({
        'GET /api/v1/mentor/jobs/j1/crew-applications': () => json([card({ status: 'PICKED', signDueAt: SIGN_DUE })]),
        'POST /api/v1/mentor/jobs/j1/crew-applications/a1/withdraw-pick': () =>
          json({ status: 409, code: 'CONFLICT', reason: 'NOT_WITHDRAWABLE' }, 409),
      }),
    )
    renderAt('/cong-viec/j1/tuyen-thanh-vien')

    await user.click(
      within(await screen.findByRole('listitem', { name: 'Trần Quang Minh' })).getByRole('button', { name: 'Rút lựa chọn' }),
    )
    await user.click(
      within(await screen.findByRole('dialog', { name: 'Rút lựa chọn Trần Quang Minh?' })).getByRole('button', {
        name: 'Rút lựa chọn',
      }),
    )

    expect(
      (await screen.findAllByText('Trần Quang Minh đã ký cam kết nên không rút lựa chọn được nữa. Sau khi ký, chỉ họ tự rời.'))
        .length,
    ).toBeGreaterThan(0)
  })
})

describe('MEN-3 says why a signature was refused, in the words of the person signing (AD-23, A-16, Story 21.5)', () => {
  it.each([
    [400, 'VALIDATION', 'COMMITMENT_VERSION_OUTDATED', 'Văn bản cam kết vừa thay đổi. Hãy tải lại trang và đọc lại trước khi ký.'],
    [400, 'VALIDATION', 'NDA_VERSION_OUTDATED', 'Cam kết bảo mật vừa có phiên bản mới. Hãy tải lại trang rồi ký lại.'],
    [400, 'VALIDATION', 'COMMIT_EMAIL_NOT_ACKNOWLEDGED', 'Hãy đánh dấu "Tôi đã hiểu" trước khi ký.'],
    [
      422,
      'GUARD_FAILED',
      'CONTRIBUTORS_UNKNOWN',
      'vibefix chưa kiểm tra được kho mã của khách hàng nên chưa nhận đơn này. Việc này không do bạn; hãy thử lại sau.',
    ],
    [
      422,
      'GUARD_FAILED',
      'APPLICANT_IS_CONTRIBUTOR',
      'Bạn đã từng đóng góp vào dự án gốc này nên không thể chào giá hay xin vào nhóm.',
    ],
    [422, 'GUARD_FAILED', 'CONFLICT_OF_INTEREST', 'Bạn không thể tham gia công việc này.'],
    // A-16: never saying whether another Crew or a lock is why, and never the words written for the Mentor.
    [422, 'GUARD_FAILED', 'APPLICANT_UNAVAILABLE', 'Bạn không thể tham gia công việc này.'],
    // Not the Job-ending sentence: this pick may have been withdrawn or have lapsed, as the re-read below shows.
    [422, 'GUARD_FAILED', 'PICK_ENDED', 'Lựa chọn này không còn hiệu lực.'],
  ])('%s %s %s', { timeout: 20_000 }, async (status, code, reason, text) => {
    const user = userEvent.setup()
    await signOnMenThree(user, {
      'POST /api/v1/crew/jobs/j1/commitment': () => json({ status, code, reason }, status),
    })

    expect(await screen.findByText(text)).toHaveAttribute('role', 'alert')
    expect(screen.queryByText(/người này|Hãy chọn người khác/)).toBeNull()
    expect(screen.getByRole('button', { name: 'Ký và tham gia' })).toBeInTheDocument()
  })

  it.each([
    ['LAPSED', 'PICK_ENDED', 'Công việc đã chuyển sang giai đoạn kết thúc nên không nhận thêm thành viên.'],
    ['WITHDRAWN', 'PICK_WITHDRAWN', 'Người hướng dẫn đã rút lựa chọn.'],
    ['LAPSED', 'COMMITMENT_LAPSED', 'Đã quá hạn ký cam kết.'],
  ])('reads the pick again after a refused signature and shows how it ended: %s %s', { timeout: 20_000 }, async (status, endReason, text) => {
    const user = userEvent.setup()
    let ended = false
    await signOnMenThree(user, {
      'GET /api/v1/crew/jobs/j1/commitment': () => json(ended ? commitment({ status, endReason, panel: undefined }) : commitment()),
      'POST /api/v1/crew/jobs/j1/commitment': () => {
        ended = true
        return json({ status: 422, code: 'GUARD_FAILED', reason: 'PICK_ENDED' }, 422)
      },
    })

    expect(await screen.findByText(text)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Tìm việc khác' })).toHaveAttribute('href', '/tim-viec')
    expect(screen.queryByRole('button', { name: 'Ký và tham gia' })).toBeNull()
  })
})
