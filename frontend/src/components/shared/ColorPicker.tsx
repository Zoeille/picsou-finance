import { cn } from '@/lib/utils'
import { ACCOUNT_COLORS } from '@/lib/constants'

interface ColorPickerProps {
  value: string
  onChange: (color: string) => void
}

export function ColorPicker({ value, onChange }: ColorPickerProps) {
  return (
    <div className="flex flex-wrap gap-2">
      {ACCOUNT_COLORS.map((color) => (
        <button
          key={color}
          type="button"
          className={cn(
            'size-8 rounded-full border-2 transition-[border-color,box-shadow]',
            value === color ? 'border-background ring-2 ring-foreground' : 'border-transparent'
          )}
          style={{ backgroundColor: color }}
          onClick={() => onChange(color)}
        />
      ))}
    </div>
  )
}
