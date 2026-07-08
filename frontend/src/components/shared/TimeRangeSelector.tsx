import { useTranslation } from 'react-i18next'
import { cn } from '@/lib/utils'

export type TimeRange = '24H' | '7D' | '1M' | '3M' | 'YTD' | '1Y' | 'ALL'

interface TimeRangeSelectorProps {
  value: TimeRange
  onChange: (range: TimeRange) => void
}

const RANGES: TimeRange[] = ['24H', '7D', '1M', '3M', 'YTD', '1Y', 'ALL']

export function TimeRangeSelector({ value, onChange }: TimeRangeSelectorProps) {
  const { t } = useTranslation()

  return (
    <div className="flex flex-wrap items-center gap-2">
      {RANGES.map(range => (
        <button
          key={range}
          onClick={() => onChange(range)}
          className={cn(
            'inline-flex h-10 min-w-12 items-center justify-center rounded-full px-4 text-sm font-medium transition-[background-color,color]',
            value === range
              ? 'bg-primary text-primary-foreground'
              : 'text-muted-foreground hover:bg-muted hover:text-foreground'
          )}
        >
          {t(`dashboard.ranges.${range}`)}
        </button>
      ))}
    </div>
  )
}
