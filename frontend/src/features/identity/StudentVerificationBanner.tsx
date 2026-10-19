import { ShieldCheck } from 'lucide-react'
import { Link } from 'react-router'
import { buttonVariants } from '@/components/ui/button-variants'
import { t } from '@/i18n/t'
import { cn } from '@/lib/utils'
import { useSession } from './useSession'
import { useVerification } from './verificationApi'

/**
 * STU-1's banner for a Student who is not verified (UX-DR27): why to verify, and "Xác minh ngay" to
 * STU-5. Nothing for other roles, a verified Student, or while the status is loading or failing.
 */
export function StudentVerificationBanner() {
  const session = useSession()
  const student = session.data?.account?.role === 'STUDENT'
  const verification = useVerification(student)
  if (!student || !verification.data || verification.data.status === 'VERIFIED') {
    return null
  }
  return (
    <div className="flex flex-wrap items-center gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4">
      <ShieldCheck aria-hidden="true" className="size-6 shrink-0 text-warning" />
      <div className="flex min-w-0 grow flex-col gap-0.5">
        <p className="text-label font-semibold">{t('screen.student.banner.title')}</p>
        <p className="text-body-sm">{t('screen.student.banner.body')}</p>
      </div>
      <Link to="/ho-so" className={cn(buttonVariants({ variant: 'secondary', size: 'compact' }))}>
        {t('screen.student.banner.action')}
      </Link>
    </div>
  )
}
