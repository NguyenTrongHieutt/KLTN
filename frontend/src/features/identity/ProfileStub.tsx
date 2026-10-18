import { MentorProfileStub } from '@/features/mentor/MentorProfileStub'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { CustomerProfilePage } from './CustomerProfilePage'
import { StudentProfilePage } from './StudentProfilePage'
import { useSession } from './useSession'

/**
 * `/ho-so`, one route for each role's profile: CUS-11 for a Customer, STU-5 for a Student, and
 * MEN-5 for a Mentor (its application is Story 1.16).
 */
export function ProfileStub() {
  const role = useSession().data?.account?.role
  switch (role) {
    case 'CUSTOMER':
      return <CustomerProfilePage />
    case 'STUDENT':
      return <StudentProfilePage />
    case 'MENTOR':
      return <MentorProfileStub />
    default:
      return <NotFoundScreen />
  }
}
