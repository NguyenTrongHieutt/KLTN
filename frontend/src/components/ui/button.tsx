import { Button as ButtonPrimitive } from '@base-ui/react/button'
import { cn } from '@/lib/utils'
import { buttonVariants, type ButtonVariantProps } from './button-variants'

/** shadcn/ui's Button on Base UI, with the DESIGN.md variants. */
function Button({ className, variant, size, ...props }: ButtonPrimitive.Props & ButtonVariantProps) {
  return <ButtonPrimitive data-slot="button" className={cn(buttonVariants({ variant, size, className }))} {...props} />
}

export { Button }
