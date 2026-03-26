import { motion } from 'motion/react'
import {
  AreaChart, Area, PieChart, Pie, Cell, Tooltip,
  XAxis, YAxis, ResponsiveContainer, CartesianGrid,
} from 'recharts'
import { TrendingUp, TrendingDown, Minus } from 'lucide-react'
import { useDashboard } from '../../hooks/useDashboard'
import { GlassCard, GlowBackground, PageHeader } from '../../components/shared'
import { formatEur, todayLabel, formatLocalDate } from '../../lib/utils'

export function DashboardPage() {
  const { data, isLoading } = useDashboard()

  if (isLoading || !data) return <DashboardSkeleton />

  const { totalNetWorth, netWorthHistory, distribution, goalSummaries } = data

  // Net worth trend (compare first and last point)
  const firstPoint = netWorthHistory[0]?.total ?? 0
  const trend = totalNetWorth - firstPoint
  const trendPct = firstPoint > 0 ? ((trend / firstPoint) * 100).toFixed(1) : null

  return (
    <GlowBackground
      glows={[
        { color: 'bg-indigo-200/20', size: 400, blur: 120, position: '-top-20 right-1/4' },
        { color: 'bg-blue-100/20', size: 300, blur: 100, position: 'bottom-10 left-1/3' },
      ]}
    >
      <PageHeader surtitle={todayLabel()} title="Tableau de bord" />

      {/* Net worth hero */}
      <motion.div
        initial={{ opacity: 0, y: 24 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.55, ease: [0.22, 1, 0.36, 1] }}
        className="mb-6"
      >
        <GlassCard>
          <div className="flex items-start justify-between mb-6">
            <div>
              <p className="text-gray-400 mb-1" style={{ fontSize: 13, fontWeight: 500 }}>
                Patrimoine total
              </p>
              <p className="text-gray-900" style={{ fontSize: 36, fontWeight: 700, lineHeight: 1.1 }}>
                {formatEur(totalNetWorth)}
              </p>
              {trendPct !== null && (
                <div className="flex items-center gap-1.5 mt-1.5">
                  {trend >= 0 ? (
                    <TrendingUp size={13} className="text-green-600" />
                  ) : (
                    <TrendingDown size={13} className="text-red-500" />
                  )}
                  <span
                    className={trend >= 0 ? 'text-green-600' : 'text-red-500'}
                    style={{ fontSize: 12, fontWeight: 600 }}
                  >
                    {trend >= 0 ? '+' : ''}{formatEur(trend)} ({trendPct}%) sur 12 mois
                  </span>
                </div>
              )}
            </div>
          </div>

          {netWorthHistory.length > 1 && (
            <ResponsiveContainer width="100%" height={180}>
              <AreaChart data={netWorthHistory} margin={{ top: 0, right: 0, left: 0, bottom: 0 }}>
                <defs>
                  <linearGradient id="netWorthGradient" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="#6366f1" stopOpacity={0.15} />
                    <stop offset="95%" stopColor="#6366f1" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid stroke="rgba(0,0,0,0.04)" strokeDasharray="4 4" vertical={false} />
                <XAxis
                  dataKey="date"
                  tickLine={false}
                  axisLine={false}
                  tick={{ fontSize: 11, fill: '#9ca3af', fontWeight: 500 }}
                  tickFormatter={d => {
                    const [, m] = d.split('-')
                    return ['Jan', 'Fév', 'Mar', 'Avr', 'Mai', 'Jun', 'Jul', 'Aoû', 'Sep', 'Oct', 'Nov', 'Déc'][parseInt(m) - 1] ?? ''
                  }}
                  interval="preserveStartEnd"
                />
                <YAxis
                  tickLine={false}
                  axisLine={false}
                  tick={{ fontSize: 11, fill: '#9ca3af', fontWeight: 500 }}
                  tickFormatter={v => formatEur(v, { compact: true })}
                  width={60}
                />
                <Tooltip
                  contentStyle={{
                    background: 'rgba(255,255,255,0.9)',
                    backdropFilter: 'blur(8px)',
                    border: '1px solid rgba(255,255,255,0.6)',
                    borderRadius: 12,
                    fontSize: 12,
                    fontWeight: 500,
                  }}
                  formatter={(v: number) => [formatEur(v), 'Patrimoine']}
                  labelFormatter={(l: string) => formatLocalDate(l)}
                />
                <Area
                  type="monotone"
                  dataKey="total"
                  stroke="#6366f1"
                  strokeWidth={2}
                  fill="url(#netWorthGradient)"
                  dot={false}
                />
              </AreaChart>
            </ResponsiveContainer>
          )}
        </GlassCard>
      </motion.div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 mb-4">
        {/* Distribution donut */}
        <motion.div
          initial={{ opacity: 0, y: 24 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.55, delay: 0.08, ease: [0.22, 1, 0.36, 1] }}
        >
          <GlassCard className="h-full">
            <p className="text-gray-900 mb-4" style={{ fontSize: 15, fontWeight: 600 }}>
              Répartition
            </p>
            <div className="flex items-center gap-4">
              <ResponsiveContainer width={160} height={160}>
                <PieChart>
                  <Pie
                    data={distribution}
                    dataKey="balanceEur"
                    nameKey="name"
                    cx="50%"
                    cy="50%"
                    innerRadius={48}
                    outerRadius={72}
                    strokeWidth={0}
                  >
                    {distribution.map((entry, i) => (
                      <Cell key={i} fill={entry.color} />
                    ))}
                  </Pie>
                  <Tooltip
                    contentStyle={{
                      background: 'rgba(255,255,255,0.9)',
                      backdropFilter: 'blur(8px)',
                      border: '1px solid rgba(255,255,255,0.6)',
                      borderRadius: 12,
                      fontSize: 12,
                    }}
                    formatter={(v: number) => [formatEur(v)]}
                  />
                </PieChart>
              </ResponsiveContainer>
              <div className="flex flex-col gap-2 flex-1 min-w-0">
                {distribution.map(item => (
                  <div key={item.accountId} className="flex items-center justify-between gap-2">
                    <div className="flex items-center gap-2 min-w-0">
                      <div
                        className="w-2 h-2 rounded-full flex-shrink-0"
                        style={{ background: item.color }}
                      />
                      <span className="text-gray-600 truncate" style={{ fontSize: 12, fontWeight: 500 }}>
                        {item.name}
                      </span>
                    </div>
                    <span className="text-gray-900 flex-shrink-0" style={{ fontSize: 12, fontWeight: 600 }}>
                      {item.percentage.toFixed(1)}%
                    </span>
                  </div>
                ))}
              </div>
            </div>
          </GlassCard>
        </motion.div>

        {/* Goal summaries */}
        <motion.div
          initial={{ opacity: 0, y: 24 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.55, delay: 0.16, ease: [0.22, 1, 0.36, 1] }}
        >
          <GlassCard className="h-full">
            <p className="text-gray-900 mb-4" style={{ fontSize: 15, fontWeight: 600 }}>
              Objectifs
            </p>
            {goalSummaries.length === 0 ? (
              <p className="text-gray-400" style={{ fontSize: 13 }}>Aucun objectif défini.</p>
            ) : (
              <div className="flex flex-col gap-4">
                {goalSummaries.slice(0, 3).map(goal => (
                  <div key={goal.id}>
                    <div className="flex items-center justify-between mb-1.5">
                      <span className="text-gray-700 truncate" style={{ fontSize: 13, fontWeight: 500 }}>
                        {goal.name}
                      </span>
                      <div className="flex items-center gap-1.5 flex-shrink-0 ml-2">
                        {goal.isOnTrack ? (
                          <TrendingUp size={12} className="text-green-600" />
                        ) : goal.monthsLeft === 0 ? (
                          <Minus size={12} className="text-gray-400" />
                        ) : (
                          <TrendingDown size={12} className="text-red-500" />
                        )}
                        <span
                          className={goal.isOnTrack ? 'text-green-600' : 'text-red-500'}
                          style={{ fontSize: 11, fontWeight: 600 }}
                        >
                          {goal.percentComplete.toFixed(0)}%
                        </span>
                      </div>
                    </div>
                    <div className="w-full bg-gray-100 rounded-full h-1.5">
                      <div
                        className="h-1.5 rounded-full transition-all"
                        style={{
                          width: `${Math.min(100, goal.percentComplete)}%`,
                          background: goal.isOnTrack ? '#22c55e' : '#f43f5e',
                        }}
                      />
                    </div>
                    <p className="text-gray-400 mt-1" style={{ fontSize: 11, fontWeight: 500 }}>
                      {formatEur(goal.currentTotal)} / {formatEur(goal.targetAmount)} • {goal.monthsLeft} mois restants
                    </p>
                  </div>
                ))}
              </div>
            )}
          </GlassCard>
        </motion.div>
      </div>
    </GlowBackground>
  )
}

function DashboardSkeleton() {
  return (
    <div className="animate-pulse">
      <div className="h-7 bg-gray-200/70 rounded-[6px] w-48 mb-6" />
      <div className="h-56 bg-white/60 rounded-[20px] mb-4" />
      <div className="grid grid-cols-2 gap-4">
        <div className="h-48 bg-white/60 rounded-[20px]" />
        <div className="h-48 bg-white/60 rounded-[20px]" />
      </div>
    </div>
  )
}
