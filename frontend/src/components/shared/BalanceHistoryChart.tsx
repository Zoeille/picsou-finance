import { Area, AreaChart, CartesianGrid, XAxis, YAxis } from 'recharts'
import { useTranslation } from 'react-i18next'
import { type ChartConfig, ChartContainer, ChartTooltip, ChartTooltipContent } from '@/components/ui/chart'
import { formatCurrency, localeFromLanguage } from '@/lib/utils'

interface BalanceHistoryChartProps {
  data: { date: string; balance: number }[]
}

const chartConfig = {
  balance: {
    label: 'Balance',
    color: 'var(--chart-2)',
  },
} satisfies ChartConfig

export function BalanceHistoryChart({ data }: BalanceHistoryChartProps) {
  const { i18n } = useTranslation()
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)

  if (data.length === 0) return null
  return (
    <ChartContainer config={chartConfig} className="h-[200px] w-full">
      <AreaChart data={data} margin={{ top: 10, right: 10, left: 0, bottom: 0 }}>
        <defs>
          <linearGradient id="fillBalance" x1="0" y1="0" x2="0" y2="1">
            <stop offset="5%" stopColor="var(--color-balance)" stopOpacity={0.3} />
            <stop offset="95%" stopColor="var(--color-balance)" stopOpacity={0.05} />
          </linearGradient>
        </defs>
        <CartesianGrid strokeDasharray="3 3" vertical={false} />
        <XAxis
          dataKey="date"
          tickLine={false}
          axisLine={false}
          tickMargin={8}
          tickFormatter={(value) => new Date(value).toLocaleDateString(locale, { month: 'short', day: 'numeric' })}
        />
        <YAxis
          tickLine={false}
          axisLine={false}
          tickMargin={8}
          tickFormatter={(value) => `${(value / 1000).toFixed(0)}k`}
          width={45}
        />
        <ChartTooltip
          content={<ChartTooltipContent formatter={(value) => formatCurrency(value as number, 'EUR', locale)} />}
        />
        <Area dataKey="balance" type="monotone" fill="url(#fillBalance)" stroke="var(--color-balance)" strokeWidth={2} />
      </AreaChart>
    </ChartContainer>
  )
}
