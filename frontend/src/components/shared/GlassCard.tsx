import { type ReactNode } from 'react'

interface GlassCardProps {
  children: ReactNode
  className?: string
  padding?: boolean
  rounded?: 'xl' | '2xl'
  onClick?: () => void
}

export function GlassCard({
  children,
  className = '',
  padding = true,
  rounded = 'xl',
  onClick,
}: GlassCardProps) {
  const roundedClass = rounded === '2xl' ? 'rounded-[24px]' : 'rounded-[20px]'
  const paddingClass = padding ? 'p-5' : ''
  const cursorClass = onClick ? 'cursor-pointer' : ''

  return (
    <div
      className={`
        bg-white/80 backdrop-blur-xl border border-white/60
        ${roundedClass} ${paddingClass} ${cursorClass}
        shadow-[0_1px_3px_rgba(0,0,0,0.04),0_8px_24px_rgba(0,0,0,0.04)]
        ${className}
      `}
      onClick={onClick}
    >
      {children}
    </div>
  )
}
