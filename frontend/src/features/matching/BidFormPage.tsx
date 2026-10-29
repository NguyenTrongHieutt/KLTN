import { FileText, Link2, Paperclip, X } from 'lucide-react'
import { useId, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { FormField } from '@/components/FormField'
import { fieldProps, inputClass } from '@/components/formField-props'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { TestTag } from '@/components/TestTag'
import { Button } from '@/components/ui/button'
import { buttonVariants } from '@/components/ui/button-variants'
import { MAX_UPLOAD_BYTES } from '@/features/files/uploadRules'
import { GitHubGate } from '@/features/identity/GitHubGate'
import { useSession } from '@/features/identity/useSession'
import { CapacityBanners } from '@/features/job/CapacityBanners'
import { ReadSessionAgain } from '@/features/job/FindWorkGate'
import { useJobPost, useMentorCapacity, type JobPost } from '@/features/job/jobPostApi'
import { roleLabel, useJobRole, type JobRoleView } from '@/features/job/jobRoleApi'
import { t } from '@/i18n/t'
import { formatSize } from '@/lib/fileSize'
import { formatVnd, groupDigits, parseVnd } from '@/lib/money'
import { isNotFound, problemMessage, type ProblemLink } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import {
  MAX_CAPTION,
  MAX_PROOF,
  isProofLink,
  useAddProofFile,
  useApply,
  useMyApplications,
  useProofUploads,
  useRemoveProofFile,
  type Application,
  type BidProofRequest,
  type BidProofUpload,
} from './applicationApi'
import { bidRefusal, bidRefusalLink, bidStateText, proofUploadRefusal } from './bidText'
import { ProofList } from './ProofList'

/**
 * FND-1 Chào giá (`/tim-viec/{jobId}/chao-gia`, FR-13, UX-DR99), a focused task route: the bid, called "Đơn chào
 * giá", with the price in whole VND (test money), the days, the approach, and "Bằng chứng năng lực" (at most five
 * files or `https` links shown with their domain, each with a caption) under N-27, then the required N-5 and "Gửi
 * chào giá". The GitHub gate stands in for the form while GitHub is not linked (X1); the Job's own people see their
 * role, and a Job no longer Open its plain reason. Once sent, FND-1 shows the bid as sent, read-only.
 */
export function BidFormPage() {
  const { postId } = useParams()
  usePageTitle(t('screen.matching.apply.title'))
  const account = useSession().data?.account
  return (
    <div className="mx-auto flex max-w-[760px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      {account && !account.capabilities.githubVerified ? (
        <>
          <PageHeading>{t('screen.matching.apply.heading')}</PageHeading>
          <GitHubGate
            action={t('screen.githubGate.action.bid')}
            returnTo={`/tim-viec/${postId ?? ''}/chao-gia`}
            keepsInput={false}
            onLeave={() => undefined}
          >
            <ReadSessionAgain />
          </GitHubGate>
        </>
      ) : (
        postId && <BidRoute postId={postId} />
      )}
    </div>
  )
}

/** What FND-1 shows for this Job and user: the sent bid, a gate, or the form. */
function BidRoute({ postId }: { postId: string }) {
  const mine = useMyApplications()
  const post = useJobPost(postId)
  const role = useJobRole(postId)
  if (mine.isPending || post.isPending || role.isPending) {
    return <Skeleton className="h-96 w-full" />
  }
  const sent = mine.data?.find((application) => application.jobId === postId)
  if (sent) {
    return <SentBid application={sent} />
  }
  if (role.data) {
    return <RoleGate jobId={postId} role={role.data} />
  }
  if (post.isError || !post.data) {
    return isNotFound(post.error) ? (
      <NotOpen />
    ) : (
      <p role="alert" className="text-body-sm text-destructive">
        {problemMessage(post.error).text}
      </p>
    )
  }
  // A listed Job no longer looking for a Mentor (its Mentor recruits the Crew) takes no bid.
  if (post.data.phase !== 'FINDING_MENTOR') {
    return <NotOpen />
  }
  return <BidForm post={post.data} />
}

/** A Job no longer Open, or hidden (UX-DR95): one plain reason and "Tìm việc khác". */
function NotOpen() {
  return (
    <div role="status" className="flex flex-col items-center gap-3 rounded-lg border border-border bg-card px-6 py-14 text-center">
      <PageHeading className="text-h3">{t('screen.matching.apply.notOpen')}</PageHeading>
      <p className="text-body-sm text-muted-foreground">{t('screen.matching.apply.notOpenBody')}</p>
      <Link to="/tim-viec" className={buttonVariants()}>
        {t('screen.jobPost.detail.findOther')}
      </Link>
    </div>
  )
}

/** The Job's own people bid on it never (AD-43): "Đây là công việc bạn đăng." or their role, with the way to the Job. */
function RoleGate({ jobId, role }: { jobId: string; role: JobRoleView }) {
  return (
    <div role="status" className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
      <PageHeading className="text-h3">
        {role.role === 'CUSTOMER'
          ? t('reason.matching.ROLE_TAKEN')
          : t('screen.jobPost.detail.role', { role: roleLabel(role.role) })}
      </PageHeading>
      <Link to={`/cong-viec/${jobId}`} className={cn(buttonVariants({ variant: 'secondary' }), 'w-fit')}>
        {t('screen.jobPost.detail.openJob')}
      </Link>
    </div>
  )
}

/** FND-1 with the sent bid, read-only ("Xem" on MEN-3). */
function SentBid({ application }: { application: Application }) {
  return (
    <section aria-label={t('screen.matching.applied.readTitle')} className="flex flex-col gap-4">
      <div className="flex flex-col gap-1">
        <PageHeading>{t('screen.matching.applied.readTitle')}</PageHeading>
        <p className="text-body-sm break-words text-muted-foreground">{application.jobTitle}</p>
      </div>
      <p role="status" className="text-label font-semibold">
        {bidStateText(application)}
      </p>
      <p className="text-body-sm text-muted-foreground">{t('screen.matching.applied.readLead')}</p>
      <div className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5 text-body-sm">
        <p>
          <span className="font-semibold">{formatVnd(application.priceVnd)}</span> <TestTag />
          {' · '}
          {t('screen.matching.days', { n: application.timelineDays })}
        </p>
        <div className="flex flex-col gap-1">
          <p className="text-label font-semibold">{t('screen.matching.apply.approach')}</p>
          <p className="break-words whitespace-pre-line">{application.approach}</p>
        </div>
        <div className="flex flex-col gap-1.5">
          <h2 className="text-label font-semibold">{t('screen.matching.proof.heading')}</h2>
          <ProofList proof={application.proof} />
        </div>
      </div>
      <Link to={`/tim-viec/${application.jobId}`} className={cn(buttonVariants({ variant: 'secondary' }), 'w-fit')}>
        {t('screen.matching.apply.back')}
      </Link>
    </section>
  )
}

/** One link proof item the bidder added on this page; files come from the waiting uploads. */
interface LinkDraft {
  url: string
  caption: string
}

/** A proof item in the bidder's order: a waiting upload's file, or a link. */
type ProofItem =
  | { key: string; kind: 'FILE'; upload: BidProofUpload }
  | { key: string; kind: 'LINK'; url: string }

function BidForm({ post }: { post: JobPost }) {
  const ids = useId()
  const navigate = useNavigate()
  const toasts = useToasts()
  const apply = useApply(post.id)
  const capacity = useMentorCapacity()
  const uploads = useProofUploads(post.id)
  const add = useAddProofFile(post.id)
  const remove = useRemoveProofFile(post.id)
  const fileInput = useRef<HTMLInputElement>(null)
  const [price, setPrice] = useState('')
  const [days, setDays] = useState('')
  const [approach, setApproach] = useState('')
  const [acknowledged, setAcknowledged] = useState(false)
  const [order, setOrder] = useState<string[]>([])
  const [links, setLinks] = useState<Record<string, LinkDraft>>({})
  const [captions, setCaptions] = useState<Record<string, string>>({})
  const [linkUrl, setLinkUrl] = useState('')
  const [linkCaption, setLinkCaption] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [proofError, setProofError] = useState<string>()
  const [refusal, setRefusal] = useState<{ text: string; link?: ProblemLink }>()
  const atLimit = capacity.data?.reasons.includes('MENTOR_JOB_LIMIT') ?? false

  // Waiting uploads not placed yet (after a reload) come first, in their upload order; then the items as added.
  const fileKeys = (uploads.data ?? []).map((upload) => `file:${upload.fileId}`)
  const keys = [
    ...fileKeys.filter((key) => !order.includes(key)),
    ...order.filter((key) => (key.startsWith('file:') ? fileKeys.includes(key) : key in links)),
  ]
  const items: ProofItem[] = keys.flatMap((key): ProofItem[] => {
    if (!key.startsWith('file:')) {
      return [{ key, kind: 'LINK', url: links[key].url }]
    }
    const upload = (uploads.data ?? []).find((waiting) => `file:${waiting.fileId}` === key)
    return upload ? [{ key, kind: 'FILE', upload }] : []
  })
  const caption = (item: ProofItem) => (item.kind === 'LINK' ? links[item.key].caption : (captions[item.key] ?? ''))
  const roomLeft = () => {
    if (items.length >= MAX_PROOF) {
      setProofError(t('reason.matching.PROOF_LIMIT'))
      return false
    }
    setProofError(undefined)
    return true
  }

  const addFile = (file: File) => {
    if (!roomLeft()) {
      return
    }
    // Only the size is checked here (NFR-3): the server reads the bytes, so a file the browser gives no type or another
    // image type goes to it, and its refusal says itself.
    if (file.size > MAX_UPLOAD_BYTES) {
      setProofError(t('reason.files.FILE_TOO_LARGE'))
      return
    }
    add.mutate(file, {
      onSuccess: (upload) => setOrder((current) => [...current, `file:${upload.fileId}`]),
      onError: (error) => setProofError(proofUploadRefusal(error)),
    })
  }

  const addLink = () => {
    if (!roomLeft()) {
      return
    }
    if (!isProofLink(linkUrl)) {
      setProofError(t('reason.matching.LINK_INVALID'))
      return
    }
    if (linkCaption.trim() === '') {
      setProofError(t('screen.matching.apply.captionRequired'))
      return
    }
    const key = `link:${Date.now()}:${keys.length}`
    setLinks((current) => ({ ...current, [key]: { url: linkUrl.trim(), caption: linkCaption.slice(0, MAX_CAPTION) } }))
    setOrder((current) => [...current, key])
    setLinkUrl('')
    setLinkCaption('')
  }

  const removeItem = (item: ProofItem) => {
    setProofError(undefined)
    if (item.kind === 'LINK') {
      setOrder((current) => current.filter((key) => key !== item.key))
      setLinks((current) => {
        const next = { ...current }
        delete next[item.key]
        return next
      })
      return
    }
    remove.mutate(item.upload.fileId, {
      onSuccess: () => setOrder((current) => current.filter((key) => key !== item.key)),
      onError: (error) => setProofError(proofUploadRefusal(error)),
    })
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    setRefusal(undefined)
    const priceVnd = parseVnd(price)
    const timelineDays = Number.parseInt(days, 10)
    const found: Record<string, string> = {}
    if (priceVnd === undefined || priceVnd < 1000 || priceVnd > 1_000_000_000) {
      found.price = t('screen.matching.apply.priceInvalid')
    }
    if (!Number.isInteger(timelineDays) || timelineDays < 1 || timelineDays > 365) {
      found.days = t('screen.matching.apply.daysInvalid')
    }
    if (approach.trim() === '') {
      found.approach = t('screen.matching.apply.approachRequired')
    }
    if (items.some((item) => caption(item).trim() === '')) {
      found.proof = t('screen.matching.apply.captionRequired')
    }
    if (items.length > MAX_PROOF) {
      found.proof = t('reason.matching.PROOF_LIMIT')
    }
    if (!acknowledged) {
      found.acknowledged = t('screen.matching.apply.acknowledgeRequired')
    }
    setErrors(found)
    if (Object.keys(found).length > 0 || priceVnd === undefined) {
      return
    }
    const proof: BidProofRequest[] = items.map((item) =>
      item.kind === 'FILE'
        ? { kind: 'FILE', fileId: item.upload.fileId, caption: caption(item).trim() }
        : { kind: 'LINK', url: item.url, caption: caption(item).trim() },
    )
    apply.mutate(
      { priceVnd, timelineDays, approach: approach.trim(), publicCodeAcknowledged: true, proof },
      {
        onSuccess: () => {
          toasts.success(t('screen.matching.apply.sent'))
          void navigate(`/tim-viec/${post.id}`)
        },
        onError: (error) => setRefusal({ text: bidRefusal(error), link: bidRefusalLink(error) }),
      },
    )
  }

  return (
    <form onSubmit={submit} noValidate aria-label={t('screen.matching.apply.heading')} className="flex flex-col gap-5">
      <div className="flex flex-col gap-1">
        <PageHeading>{t('screen.matching.apply.heading')}</PageHeading>
        <p className="text-body-sm break-words text-muted-foreground">{post.title}</p>
        <p className="text-body-sm text-muted-foreground">{t('screen.matching.apply.lead')}</p>
      </div>
      {atLimit && <CapacityBanners capacity={capacity.data} />}
      <div className="grid gap-4 sm:grid-cols-2">
        <FormField id={`${ids}-price`} label={t('screen.matching.apply.price')} error={errors.price}>
          <div className="relative">
            <input
              {...fieldProps(`${ids}-price`, errors.price)}
              type="text"
              inputMode="numeric"
              autoComplete="off"
              value={groupDigits(price)}
              onChange={(event) => setPrice(event.target.value.replace(/\D/g, '').slice(0, 10))}
              className={cn(inputClass, 'pr-10')}
            />
            <span aria-hidden="true" className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-muted-foreground">
              ₫
            </span>
          </div>
          <p className="text-caption text-muted-foreground">
            <TestTag /> {t('screen.matching.apply.priceHint')}
          </p>
        </FormField>
        <FormField id={`${ids}-days`} label={t('screen.matching.apply.days')} error={errors.days}>
          <input
            {...fieldProps(`${ids}-days`, errors.days)}
            type="number"
            inputMode="numeric"
            min={1}
            max={365}
            value={days}
            onChange={(event) => setDays(event.target.value)}
            className={inputClass}
          />
        </FormField>
      </div>
      <FormField
        id={`${ids}-approach`}
        label={t('screen.matching.apply.approach')}
        helper={t('screen.matching.apply.approachHint')}
        error={errors.approach}
      >
        <textarea
          {...fieldProps(`${ids}-approach`, errors.approach, t('screen.matching.apply.approachHint'))}
          rows={4}
          maxLength={2000}
          value={approach}
          onChange={(event) => setApproach(event.target.value)}
          className={cn(inputClass, 'min-h-24')}
        />
      </FormField>
      <section aria-labelledby={`${ids}-proof`} className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
        <div role="note" className="rounded-md border-l-4 border-primary bg-primary-soft p-4 text-body-sm">
          {t('notice.proofConfidentiality', { who: t('screen.matching.apply.proofWho') })}
        </div>
        <h2 id={`${ids}-proof`} className="text-h3">
          {t('screen.matching.apply.proofHeading')}
        </h2>
        <p className="text-caption text-muted-foreground">{t('screen.matching.apply.proofHint')}</p>
        {items.length > 0 && (
          <ul className="flex flex-col gap-3">
            {items.map((item) => {
              const name = item.kind === 'FILE' ? item.upload.fileName : item.url
              const captionId = `${ids}-${item.key}`
              return (
                <li key={item.key} className="flex flex-col gap-2 rounded-md border border-border p-3">
                  <div className="flex items-start gap-2 text-body-sm">
                    {item.kind === 'FILE' ? (
                      <FileText aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
                    ) : (
                      <Link2 aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
                    )}
                    <span className="min-w-0 flex-1 break-all">
                      {item.kind === 'FILE'
                        ? t('screen.matching.proof.file', { name, size: formatSize(item.upload.sizeBytes) })
                        : `${name} (${new URL(item.url).hostname.toLowerCase()})`}
                    </span>
                    <Button
                      type="button"
                      variant="ghost"
                      size="compact"
                      aria-label={t('screen.matching.apply.removeItem', { name })}
                      disabled={remove.isPending}
                      onClick={() => removeItem(item)}
                    >
                      <X aria-hidden="true" className="size-4" />
                    </Button>
                  </div>
                  {item.kind === 'FILE' ? (
                    <input
                      id={captionId}
                      aria-label={t('screen.matching.apply.caption', { name })}
                      maxLength={MAX_CAPTION}
                      value={captions[item.key] ?? ''}
                      onChange={(event) => setCaptions((current) => ({ ...current, [item.key]: event.target.value }))}
                      className={inputClass}
                    />
                  ) : (
                    <p className="text-body-sm break-words text-muted-foreground">{links[item.key].caption}</p>
                  )}
                </li>
              )
            })}
          </ul>
        )}
        <p className="text-caption text-muted-foreground">{t('screen.matching.apply.proofCount', { n: items.length })}</p>
        <div className="grid gap-3 sm:grid-cols-2">
          <FormField id={`${ids}-link`} label={t('screen.matching.apply.linkUrl')}>
            <input
              {...fieldProps(`${ids}-link`)}
              type="url"
              inputMode="url"
              maxLength={2000}
              placeholder="https://"
              value={linkUrl}
              onChange={(event) => setLinkUrl(event.target.value)}
              className={inputClass}
            />
          </FormField>
          <FormField id={`${ids}-link-caption`} label={t('screen.matching.apply.linkCaption')}>
            <input
              {...fieldProps(`${ids}-link-caption`)}
              maxLength={MAX_CAPTION}
              value={linkCaption}
              onChange={(event) => setLinkCaption(event.target.value)}
              className={inputClass}
            />
          </FormField>
        </div>
        <div className="flex flex-wrap gap-3">
          <Button type="button" variant="secondary" onClick={addLink}>
            <Link2 aria-hidden="true" className="size-4" />
            {t('screen.matching.apply.addLink')}
          </Button>
          <input
            ref={fileInput}
            type="file"
            accept="image/png,image/jpeg,image/gif,image/bmp,application/pdf"
            className="sr-only"
            tabIndex={-1}
            aria-hidden="true"
            onChange={(event) => {
              const file = event.target.files?.[0]
              event.target.value = ''
              if (file) {
                addFile(file)
              }
            }}
          />
          <Button type="button" variant="secondary" disabled={add.isPending} onClick={() => fileInput.current?.click()}>
            <Paperclip aria-hidden="true" className="size-4" />
            {t(add.isPending ? 'screen.matching.apply.uploading' : 'screen.matching.apply.addFile')}
          </Button>
        </div>
        {(proofError ?? errors.proof) && (
          <p role="alert" className="text-body-sm text-destructive">
            {proofError ?? errors.proof}
          </p>
        )}
      </section>
      <div className="flex flex-col gap-1">
        <label className="flex items-start gap-2 text-body-sm">
          <input
            type="checkbox"
            className="mt-1"
            checked={acknowledged}
            onChange={(event) => setAcknowledged(event.target.checked)}
            aria-invalid={errors.acknowledged ? true : undefined}
          />
          <span>{t('screen.matching.apply.n5')}</span>
        </label>
        {errors.acknowledged && (
          <p role="alert" className="text-body-sm text-destructive">
            {errors.acknowledged}
          </p>
        )}
      </div>
      {refusal && (
        <div role="alert" className="flex flex-col gap-1 rounded-md border-l-4 border-destructive bg-card p-4 text-body-sm">
          <p>{refusal.text}</p>
          {refusal.link && (
            <Link to={refusal.link.href} className="w-fit text-primary underline-offset-4 hover:underline">
              {refusal.link.label}
            </Link>
          )}
        </div>
      )}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Link to={`/tim-viec/${post.id}`} className={buttonVariants({ variant: 'secondary' })}>
          {t('screen.matching.apply.back')}
        </Link>
        <Button type="submit" disabled={atLimit || apply.isPending}>
          {t(apply.isPending ? 'screen.matching.apply.sending' : 'screen.matching.apply.submit')}
        </Button>
      </div>
    </form>
  )
}
