import { Menu } from '@base-ui/react/menu'
import { BriefcaseBusiness, Package, Plus, type LucideIcon } from 'lucide-react'
import { useNavigate } from 'react-router'
import { buttonVariants } from '@/components/ui/button-variants'
import { useProducts } from '@/features/product/productApi'
import { t, type MessageKey } from '@/i18n/t'
import { cn } from '@/lib/utils'

const item =
  'flex min-h-control-compact cursor-pointer items-center gap-2.5 rounded-md px-3 text-body-sm data-highlighted:bg-muted'

/**
 * The top bar's "Tạo" menu, from 768 px: the two things a user starts from anywhere. "Đăng công việc" opens the Job
 * form of the user's Product when they have exactly one, and Sản phẩm otherwise, where they pick the Product (or
 * register their first one); "Đăng ký sản phẩm" opens CUS-3.
 */
export function CreateMenu() {
  const navigate = useNavigate()
  const products = useProducts()
  const list = products.data ?? []
  const postTo = list.length === 1 ? `/san-pham/${list[0].id}/cong-viec-moi` : '/san-pham'
  const items: { label: MessageKey; to: string; icon: LucideIcon }[] = [
    { label: 'shell.createJob', to: postTo, icon: BriefcaseBusiness },
    { label: 'shell.createProduct', to: '/san-pham/moi', icon: Package },
  ]
  return (
    <Menu.Root>
      <Menu.Trigger className={cn(buttonVariants({ variant: 'secondary', size: 'compact' }), 'gap-1.5 pr-3.5 pl-2.5')}>
        <Plus aria-hidden="true" className="size-4.5" />
        {t('shell.create')}
      </Menu.Trigger>
      <Menu.Portal>
        <Menu.Positioner align="end" sideOffset={6} className="z-50">
          <Menu.Popup className="w-60 rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-float">
            {items.map((entry) => (
              <Menu.Item key={entry.label} className={item} onClick={() => navigate(entry.to)}>
                <entry.icon aria-hidden="true" className="size-4.5 text-muted-foreground" />
                {t(entry.label)}
              </Menu.Item>
            ))}
          </Menu.Popup>
        </Menu.Positioner>
      </Menu.Portal>
    </Menu.Root>
  )
}
