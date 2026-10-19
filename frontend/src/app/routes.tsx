import { Navigate, type RouteObject } from 'react-router'
import { SignInPage } from '@/features/identity/SignInPage'
import { StartPage } from '@/features/identity/StartPage'
import { LandingPage } from '@/features/public/LandingPage'
import { PrivacyPage } from '@/features/public/PrivacyPage'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { PublicLayout } from './PublicLayout'

/**
 * The route table (EXPERIENCE.md Surfaces). Routes use Vietnamese slugs without diacritics; a
 * surface that is not built yet has no route, so its path shows the not-found screen. The signed-in
 * shell and its screens load as their own chunk, so a public visit downloads only what it shows
 * (NFR-14).
 */
export const routes: RouteObject[] = [
  {
    // Signed-in surfaces: the guard sends anyone else to PUB-2 or PUB-3.
    lazy: async () => ({ Component: (await import('./AppLayout')).AppLayout }),
    children: [
      {
        path: 'viec-can-lam',
        lazy: async () => ({ Component: (await import('@/features/notification/TodoListPage')).TodoListPage }),
      },
      {
        path: 'thong-bao',
        lazy: async () => ({
          Component: (await import('@/features/notification/NotificationsPage')).NotificationsPage,
        }),
      },
      {
        path: 'ho-so',
        lazy: async () => ({ Component: (await import('@/features/identity/ProfileStub')).ProfileStub }),
      },
      {
        path: 'nhom',
        lazy: async () => ({ Component: (await import('@/features/team/TeamPage')).TeamPage }),
      },
      {
        path: 'san-pham',
        lazy: async () => ({ Component: (await import('@/features/product/ProductsPage')).ProductsPage }),
      },
      {
        path: 'san-pham/:productId',
        lazy: async () => ({ Component: (await import('@/features/product/ProductPage')).ProductPage }),
      },
      {
        path: 'san-pham/:productId/cong-viec-moi',
        lazy: async () => ({ Component: (await import('@/features/job/JobFormPage')).JobFormPage }),
      },
      {
        path: 'tim-viec',
        lazy: async () => ({ Component: (await import('@/features/job/JobPostsPage')).JobPostsPage }),
      },
      {
        path: 'tim-viec/:postId',
        lazy: async () => ({ Component: (await import('@/features/job/JobPostPage')).JobPostPage }),
      },
      {
        path: 'cong-viec',
        lazy: async () => ({ Component: (await import('@/features/job/JobsPage')).JobsPage }),
      },
      {
        path: 'cong-viec/:jobId',
        lazy: async () => ({ Component: (await import('@/features/job/JobPage')).JobPage }),
      },
      {
        path: 'cong-viec/:jobId/ung-tuyen',
        lazy: async () => ({ Component: (await import('@/features/matching/JobApplicationsPage')).JobApplicationsPage }),
      },
      {
        path: 'cong-viec/:jobId/thoa-thuan',
        lazy: async () => ({ Component: (await import('@/features/agreement/AgreementPage')).AgreementPage }),
      },
      {
        path: 'cong-viec/:jobId/tro-chuyen',
        lazy: async () => ({ Component: (await import('@/features/chat/JobChatPage')).JobChatPage }),
      },
      {
        path: 'cong-viec/:jobId/tim-nguoi',
        lazy: async () => ({ Component: (await import('@/features/matching/MentorSearchPage')).MentorSearchPage }),
      },
      {
        path: 'cong-viec/:jobId/sua',
        lazy: async () => ({ Component: (await import('@/features/job/JobFormPage')).JobFormPage }),
      },
    ],
  },
  {
    // CUS-3, a focused task route (UX-DR8): signed in, without the navigation.
    path: 'san-pham/moi',
    lazy: async () => {
      const [{ FocusedLayout }, { t }] = await Promise.all([import('./FocusedLayout'), import('@/i18n/t')])
      return { element: <FocusedLayout title={t('screen.product.wizard.title')} closeTo="/san-pham" /> }
    },
    children: [
      {
        index: true,
        lazy: async () => ({
          Component: (await import('@/features/product/RegisterProductPage')).RegisterProductPage,
        }),
      },
    ],
  },
  {
    // CUS-5, a focused task route (UX-DR8) that "Đóng" leaves for its Product.
    path: 'san-pham/:productId/van-de/:findingId',
    lazy: async () => {
      const [{ FocusedLayout }, { t }] = await Promise.all([import('./FocusedLayout'), import('@/i18n/t')])
      return {
        element: (
          <FocusedLayout
            title={t('screen.product.finding.title')}
            closeTo={(params) => `/san-pham/${params.productId ?? ''}`}
          />
        ),
      }
    },
    children: [
      {
        index: true,
        lazy: async () => ({ Component: (await import('@/features/product/FindingPage')).FindingPage }),
      },
    ],
  },
  {
    // PUB-6, linked from no public page (FR-2); its own header, outside the public layout.
    path: 'quan-tri/dang-nhap',
    lazy: async () => ({ Component: (await import('@/features/admin/AdminSignInPage')).AdminSignInPage }),
  },
  {
    // The Admin shell (UX-DR13): its guard sends anyone not signed in to PUB-6.
    path: 'quan-tri',
    lazy: async () => ({ Component: (await import('@/features/admin/AdminLayout')).AdminLayout }),
    children: [
      {
        index: true,
        lazy: async () => ({ Component: (await import('@/features/admin/AdminOverviewPage')).AdminOverviewPage }),
      },
      {
        path: 'the-sinh-vien',
        lazy: async () => ({ Component: (await import('@/features/admin/StudentCardsPage')).StudentCardsPage }),
      },
      {
        path: 'nguoi-huong-dan/:accountId?',
        lazy: async () => ({
          Component: (await import('@/features/admin/MentorApplicationsPage')).MentorApplicationsPage,
        }),
      },
      {
        path: 'quan-tri-vien',
        lazy: async () => ({ Component: (await import('@/features/admin/AdministratorsPage')).AdministratorsPage }),
      },
    ],
  },
  {
    // A new Administrator's enrolment link (FR-2), emailed; outside the shell, for a visitor not signed in.
    path: 'quan-tri/kich-hoat/:token',
    lazy: async () => ({ Component: (await import('@/features/admin/EnrolmentPage')).EnrolmentPage }),
  },
  {
    element: <PublicLayout />,
    children: [
      { index: true, element: <LandingPage /> },
      { path: 'dang-nhap', element: <SignInPage /> },
      { path: 'bat-dau', element: <StartPage /> },
      { path: 'quyen-rieng-tu', element: <PrivacyPage /> },
      {
        // PUB-5, for anyone: a signed-in visitor keeps the public header with "Vào trang của bạn".
        path: 'mentor/:username',
        lazy: async () => ({ Component: (await import('@/features/team/MentorPublicPage')).MentorPublicPage }),
      },
      {
        // PUB-4, for anyone; linked from MEN-6 and Portfolio links, never from a Customer screen.
        path: 'sv/:username',
        lazy: async () => ({ Component: (await import('@/features/team/StudentPublicPage')).StudentPublicPage }),
      },
      // The AI notice was removed with the AI (2026-10-18); its address now shows the privacy notice.
      { path: 'ai', element: <Navigate to="/quyen-rieng-tu" replace /> },
      { path: '*', element: <NotFoundScreen /> },
    ],
  },
]
