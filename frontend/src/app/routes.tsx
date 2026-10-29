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
        // The sign-in-again page of before Story 19.2: re-linking GitHub happens on ACC-2 now (AD-10).
        path: 'dang-nhap-lai',
        element: <Navigate to="/ho-so/xac-minh" replace />,
      },
      {
        // ACC-1, the one profile of every account (Story 19.3).
        path: 'ho-so',
        lazy: async () => ({ Component: (await import('@/features/identity/ProfilePage')).ProfilePage }),
      },
      {
        // "Hồ sơ công khai" of the avatar menu (Story 20.6): redirects to the user's PUB-4, or shows the GitHub gate.
        path: 'ho-so/cong-khai',
        lazy: async () => ({ Component: (await import('@/features/identity/PublicProfilePage')).PublicProfilePage }),
      },
      {
        // ACC-2, where the identity.link_github and identity.relink_github to-dos lead (Story 19.2).
        path: 'ho-so/xac-minh',
        lazy: async () => ({ Component: (await import('@/features/identity/VerificationPage')).VerificationPage }),
      },
      {
        path: 'tai-khoan/du-lieu',
        lazy: async () => ({ Component: (await import('@/features/identity/MyDataPage')).MyDataPage }),
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
        // CUS-13, the comparison of 2 or 3 bids (UX-DR101, Story 21.3).
        path: 'cong-viec/:jobId/ung-tuyen/so-sanh',
        lazy: async () => ({ Component: (await import('@/features/matching/CompareBidsPage')).CompareBidsPage }),
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
        path: 'cong-viec/:jobId/tai-lieu/:pageId?',
        lazy: async () => ({ Component: (await import('@/features/work/docs/DocsPage')).DocsPage }),
      },
      {
        path: 'cong-viec/:jobId/tai-lieu-cu/:pageId?',
        lazy: async () => ({ Component: (await import('@/features/work/docs/SharedDocsPage')).SharedDocsPage }),
      },
      {
        path: 'cong-viec/:jobId/bang',
        lazy: async () => ({ Component: (await import('@/features/work/BoardPage')).BoardPage }),
      },
      {
        path: 'cong-viec/:jobId/ban-giao/:handoverId?',
        lazy: async () => ({ Component: (await import('@/features/delivery/HandoverPage')).HandoverPage }),
      },
      {
        path: 'cong-viec/:jobId/trao-doi-nhom',
        lazy: async () => ({ Component: (await import('@/features/chat/CrewChatPage')).CrewChatPage }),
      },
      {
        path: 'lich-su-thanh-toan',
        lazy: async () => ({ Component: (await import('@/features/payment/PaymentHistoryPage')).PaymentHistoryPage }),
      },
      {
        path: 'so-du',
        lazy: async () => ({ Component: (await import('@/features/payment/BalancePage')).BalancePage }),
      },
      {
        path: 'cong-viec/:jobId/thanh-toan',
        lazy: async () => ({ Component: (await import('@/features/payment/JobPaymentPage')).JobPaymentPage }),
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
    // FND-1 "Chào giá", a focused task route (UX-DR99) that "Đóng" leaves for MEN-3, the Job it bids on.
    path: 'tim-viec/:postId/chao-gia',
    lazy: async () => {
      const [{ FocusedLayout }, { t }] = await Promise.all([import('./FocusedLayout'), import('@/i18n/t')])
      return {
        element: (
          <FocusedLayout title={t('screen.matching.apply.title')} closeTo={(params) => `/tim-viec/${params.postId ?? ''}`} />
        ),
      }
    },
    children: [
      {
        index: true,
        lazy: async () => ({ Component: (await import('@/features/matching/BidFormPage')).BidFormPage }),
      },
    ],
  },
  {
    // JOB-5 "Tạo bàn giao", a focused task route (UX-DR54) that "Đóng" leaves for the Job's handovers.
    path: 'cong-viec/:jobId/ban-giao/tao',
    lazy: async () => {
      const [{ FocusedLayout }, { t }] = await Promise.all([import('./FocusedLayout'), import('@/i18n/t')])
      return {
        element: (
          <FocusedLayout
            title={t('screen.handover.form.title')}
            closeTo={(params) => `/cong-viec/${params.jobId ?? ''}/ban-giao`}
          />
        ),
      }
    },
    children: [
      {
        index: true,
        lazy: async () => ({ Component: (await import('@/features/delivery/HandoverFormPage')).HandoverFormPage }),
      },
    ],
  },
  {
    // JOB-5's guided check, a focused task route (UX-DR33) that "Đóng" leaves for the Delivery Report.
    path: 'cong-viec/:jobId/ban-giao/:handoverId/kiem-tra',
    lazy: async () => {
      const [{ FocusedLayout }, { t }] = await Promise.all([import('./FocusedLayout'), import('@/i18n/t')])
      return {
        element: (
          <FocusedLayout
            title={t('screen.handover.check.title')}
            closeTo={(params) => `/cong-viec/${params.jobId ?? ''}/ban-giao/${params.handoverId ?? ''}`}
          />
        ),
      }
    },
    children: [
      {
        index: true,
        lazy: async () => ({ Component: (await import('@/features/delivery/GuidedCheckPage')).GuidedCheckPage }),
      },
    ],
  },
  {
    // CUS-9, VNPay's return URL, a focused route (AD-14): it only reads the order's status.
    path: 'thanh-toan/:paymentOrderId',
    lazy: async () => {
      const [{ FocusedLayout }, { t }] = await Promise.all([import('./FocusedLayout'), import('@/i18n/t')])
      return { element: <FocusedLayout title={t('screen.payment.result.title')} closeTo="/cong-viec" /> }
    },
    children: [
      {
        index: true,
        lazy: async () => ({ Component: (await import('@/features/payment/PaymentResultPage')).PaymentResultPage }),
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
        path: 'quan-tri-vien',
        lazy: async () => ({ Component: (await import('@/features/admin/AdministratorsPage')).AdministratorsPage }),
      },
      {
        path: 'nguoi-dung/:accountId?',
        lazy: async () => ({ Component: (await import('@/features/admin/AdminUsersPage')).AdminUsersPage }),
      },
      {
        path: 'cong-viec/:jobId?',
        lazy: async () => ({ Component: (await import('@/features/admin/AdminJobsPage')).AdminJobsPage }),
      },
      {
        path: 'nhat-ky',
        lazy: async () => ({ Component: (await import('@/features/admin/AdminAuditPage')).AdminAuditPage }),
      },
      {
        path: 'thao-tac-loi',
        lazy: async () => ({ Component: (await import('@/features/admin/AdminFailuresPage')).AdminFailuresPage }),
      },
      {
        path: 'thong-ke',
        lazy: async () => ({ Component: (await import('@/features/admin/AdminStatisticsPage')).AdminStatisticsPage }),
      },
      {
        path: 'ky-nang',
        lazy: async () => ({ Component: (await import('@/features/admin/AdminCatalogPage')).AdminCatalogPage }),
      },
      {
        path: 'quy-tac',
        lazy: async () => ({ Component: (await import('@/features/admin/AdminRulesPage')).AdminRulesPage }),
      },
      {
        path: 'kiem-duyet',
        lazy: async () => ({ Component: (await import('@/features/admin/AdminModerationPage')).AdminModerationPage }),
      },
      {
        path: 'tranh-chap/:disputeId?',
        lazy: async () => ({ Component: (await import('@/features/admin/AdminDisputesPage')).AdminDisputesPage }),
      },
      {
        path: 'khieu-nai-noi-bo/:teamIssueId?',
        lazy: async () => ({ Component: (await import('@/features/admin/AdminTeamIssuesPage')).AdminTeamIssuesPage }),
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
        // PUB-4, one public profile for anyone (Story 21.3): a signed-in visitor keeps the public header with "Vào
        // trang của bạn". A Crew member's username on a Customer's screen never links here (UX-DR108).
        path: 'nguoi-dung/:username',
        lazy: async () => ({ Component: (await import('@/features/identity/PersonPage')).PersonPage }),
      },
      {
        // PUB-5's old address, merged into PUB-4 (UX-DR96).
        path: 'mentor/:username',
        lazy: async () => ({ Component: (await import('@/features/identity/PersonPage')).RedirectToPerson }),
      },
      {
        // The Student page's old address, merged into PUB-4 (UX-DR96).
        path: 'sv/:username',
        lazy: async () => ({ Component: (await import('@/features/identity/PersonPage')).RedirectToPerson }),
      },
      // The AI notice was removed with the AI (2026-10-18); its address now shows the privacy notice.
      { path: 'ai', element: <Navigate to="/quyen-rieng-tu" replace /> },
      { path: '*', element: <NotFoundScreen /> },
    ],
  },
]
