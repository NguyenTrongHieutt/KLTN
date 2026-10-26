import { ShieldCheck } from 'lucide-react'
import { Link } from 'react-router'
import { buttonVariants } from '@/components/ui/button-variants'
import { t } from '@/i18n/t'
import { formatCalendarDate } from '@/lib/dates'
import { cn } from '@/lib/utils'
import { useSession } from './useSession'
import { useVerification } from './verificationApi'

/**
 * STU-1's banner for a Student who is not verified (UX-DR27): why to verify and "Xác minh ngay", or,
 * once the verification expired, "Xác minh đã hết hạn ngày 30/09" and "Gia hạn", both to ACC-2.
 * Nothing for other roles, a verified Student, or while the status is loading or failing.
 */
export function StudentVerificationBanner() {
  const session = useSession()
  const student = session.data?.account?.role === 'STUDENT'
  const verification = useVerification(student)
  if (!student || !verification.data || verification.data.status === 'VERIFIED') {
    return null
  }
  const expired = verification.data.status === 'EXPIRED' && verification.data.verifiedUntil
  return (
    <div className="flex flex-wrap items-center gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4">
      <ShieldCheck aria-hidden="true" className="size-6 shrink-0 text-warning" />
      <div className="flex min-w-0 grow flex-col gap-0.5">
        <p className="text-label font-semibold">
          {expired
            ? t('screen.student.banner.expiredTitle', { date: formatCalendarDate(expired).slice(0, 5) })
            : t('screen.student.banner.title')}
        </p>
        <p className="text-body-sm">{t(expired ? 'screen.student.banner.expiredBody' : 'screen.student.banner.body')}</p>
      </div>
      <Link to="/ho-so/xac-minh" className={cn(buttonVariants({ variant: 'secondary', size: 'compact' }))}>
        {t(expired ? 'screen.student.banner.renew' : 'screen.student.banner.action')}
      </Link>
    </div>
  )
}
