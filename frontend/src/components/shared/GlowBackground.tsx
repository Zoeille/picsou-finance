import { type ReactNode } from 'react'

interface GlowConfig {
  color: string
  size?: number
  blur?: number
  position: string
}

interface GlowBackgroundProps {
  children: ReactNode
  glows?: GlowConfig[]
  className?: string
}

const DEFAULT_GLOWS: GlowConfig[] = [
  { color: 'bg-indigo-200/20', size: 400, blur: 120, position: '-top-20 right-1/4' },
  { color: 'bg-blue-100/25', size: 300, blur: 100, position: 'bottom-0 left-1/3' },
]

export function GlowBackground({ children, glows = DEFAULT_GLOWS, className = '' }: GlowBackgroundProps) {
  return (
    <div className={`relative ${className}`}>
      {glows.map((glow, i) => (
        <div
          key={i}
          className={`absolute ${glow.position} rounded-full ${glow.color} pointer-events-none`}
          style={{
            width: glow.size ?? 400,
            height: glow.size ?? 400,
            filter: `blur(${glow.blur ?? 120}px)`,
          }}
        />
      ))}
      <div className="relative">{children}</div>
    </div>
  )
}
