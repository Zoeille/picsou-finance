import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useGoals, useCreateGoal, useUpdateGoal, useDeleteGoal } from '@/features/goals/hooks'
import { useAccounts } from '@/features/accounts/hooks'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { EmptyState } from '@/components/shared/EmptyState'
import { PageHeader } from '@/components/shared/PageHeader'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { LoadingSkeleton } from '@/components/shared/LoadingSkeleton'
import { GoalDetailModal } from './GoalDetailModal'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { NumericInput } from '@/components/shared/NumericInput'
import { DateInput } from '@/components/shared/DateInput'
import { parseAmount } from '@/lib/utils'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Progress } from '@/components/ui/progress'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from '@/components/ui/dialog'
import {
  Target,
  Plus,
  Pencil,
  Trash2,
  Calendar,
  Loader2,
  TrendingUp,
  TrendingDown,
} from 'lucide-react'
import type { GoalProgress } from '@/types/api'

const emptyForm = {
  name: '',
  targetAmount: '',
  deadline: '',
  accountIds: [] as number[],
}

export function GoalsPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()

  const { data: goals, isLoading } = useGoals()
  const { data: accounts } = useAccounts()
  const createGoal = useCreateGoal()
  const updateGoal = useUpdateGoal()
  const deleteGoal = useDeleteGoal()

  const [showForm, setShowForm] = useState(false)
  const [editingGoal, setEditingGoal] = useState<GoalProgress | null>(null)
  const [deleteId, setDeleteId] = useState<number | null>(null)
  const [form, setForm] = useState(emptyForm)
  const [detailGoalId, setDetailGoalId] = useState<number | null>(null)

  const openCreate = () => {
    setEditingGoal(null)
    setForm(emptyForm)
    setShowForm(true)
  }

  const openEdit = (goal: GoalProgress) => {
    setEditingGoal(goal)
    setForm({
      name: goal.name,
      targetAmount: String(goal.targetAmount),
      deadline: goal.deadline,
      accountIds: goal.accounts.map((a) => a.id),
    })
    setShowForm(true)
  }

  const closeForm = () => {
    setShowForm(false)
    setEditingGoal(null)
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    const data = {
      name: form.name,
      targetAmount: parseAmount(form.targetAmount),
      deadline: form.deadline,
      accountIds: form.accountIds,
    }
    if (editingGoal) {
      await updateGoal.mutateAsync({ id: editingGoal.id, data })
    } else {
      await createGoal.mutateAsync(data)
    }
    closeForm()
  }

  const toggleAccount = (id: number) => {
    setForm((f) => ({
      ...f,
      accountIds: f.accountIds.includes(id)
        ? f.accountIds.filter((a) => a !== id)
        : [...f.accountIds, id],
    }))
  }

  const handleConfirmDelete = () => {
    if (deleteId != null) {
      deleteGoal.mutate(deleteId)
      setDeleteId(null)
    }
  }

  if (isLoading) return <LoadingSkeleton />

  const goalList = goals ?? []

  return (
    <div className="space-y-6">
      <PageHeader
        title={t('goals.title')}
        actions={
          <Button onClick={openCreate} size="sm" className="gap-1.5">
            <Plus className="size-4" />
            {t('goals.addGoal')}
          </Button>
        }
      />

      {goalList.length === 0 ? (
        <EmptyState
          className="min-h-[calc(100vh-14rem)]"
          icon={<Target className="size-12" />}
          title={t('goals.noGoals')}
          action={{ label: t('goals.addGoal'), onClick: openCreate }}
        />
      ) : (
        <div className="flex flex-col gap-4">
          {goalList.map((goal) => (
            <GoalCard
              key={goal.id}
              goal={goal}
              onEdit={() => openEdit(goal)}
              onDelete={() => setDeleteId(goal.id)}
              onCalendar={() => navigate(`/goals/${goal.id}/calendar`)}
              onOpenDetail={() => setDetailGoalId(goal.id)}
            />
          ))}
        </div>
      )}

      {/* Goal detail modal */}
      <GoalDetailModal
        goalId={detailGoalId}
        onClose={() => setDetailGoalId(null)}
      />

      {/* Create / Edit dialog */}
      <Dialog open={showForm} onOpenChange={(open) => { if (!open) closeForm() }}>
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>
              {editingGoal ? t('goals.editGoal') : t('goals.addGoal')}
            </DialogTitle>
          </DialogHeader>

          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="goal-name">{t('goals.title')}</Label>
              <Input
                id="goal-name"
                required
                value={form.name}
                onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))}
                placeholder="Apport immobilier"
              />
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="goal-target">{t('goals.targetAmount')}</Label>
                <NumericInput
                  id="goal-target"
                  required
                  value={form.targetAmount}
                  onChange={(e) => setForm((f) => ({ ...f, targetAmount: e.target.value }))}
                  placeholder="50000"
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="goal-deadline">{t('goals.deadline')}</Label>
                <DateInput
                  id="goal-deadline"
                  required
                  value={form.deadline}
                  onChange={(iso) => setForm((f) => ({ ...f, deadline: iso }))}
                />
              </div>
            </div>

            <div className="flex flex-col gap-2">
              <Label>Comptes inclus</Label>
              <div className="flex flex-col gap-2 max-h-40 overflow-y-auto">
                {(accounts ?? []).map((a) => (
                  <label
                    key={a.id}
                    className="flex items-center gap-2.5 cursor-pointer select-none"
                  >
                    <input
                      type="checkbox"
                      checked={form.accountIds.includes(a.id)}
                      onChange={() => toggleAccount(a.id)}
                      className="rounded accent-primary"
                    />
                    <span
                      className="w-2.5 h-2.5 rounded-full shrink-0"
                      style={{ background: a.color }}
                    />
                    <span className="text-sm flex-1">{a.name}</span>
                    <span className="text-xs text-muted-foreground">
                      <CurrencyDisplay value={a.currentBalanceEur} />
                    </span>
                  </label>
                ))}
              </div>
            </div>

            <DialogFooter>
              <Button type="button" variant="outline" onClick={closeForm}>
                {t('common.cancel')}
              </Button>
              <Button
                type="submit"
                disabled={
                  createGoal.isPending ||
                  updateGoal.isPending ||
                  form.accountIds.length === 0
                }
              >
                {(createGoal.isPending || updateGoal.isPending) && (
                  <Loader2
                    className="size-4 animate-spin mr-1"
                  />
                )}
                {t('common.save')}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Delete confirm dialog */}
      <ConfirmDialog
        open={deleteId != null}
        onOpenChange={(open) => { if (!open) setDeleteId(null) }}
        title={t('goals.deleteGoal')}
        description={t('goals.deleteGoal')}
        onConfirm={handleConfirmDelete}
        loading={deleteGoal.isPending}
        variant="destructive"
      />
    </div>
  )
}

interface GoalCardProps {
  goal: GoalProgress
  onEdit: () => void
  onDelete: () => void
  onCalendar: () => void
  onOpenDetail: () => void
}

function GoalCard({ goal, onEdit, onDelete, onCalendar, onOpenDetail }: GoalCardProps) {
  const { t } = useTranslation()

  const statusBadge = (() => {
    if (goal.monthlyNeeded <= 0) {
      return (
        <Badge className="gap-1">
          <TrendingUp className="size-3" />
          {t('goals.achieved')}
        </Badge>
      )
    }
    if (goal.avgMonthlyContribution == null) {
      return (
        <Badge variant="secondary" className="gap-1">
          {t('goals.waiting')}
        </Badge>
      )
    }
    if (goal.isOnTrack) {
      return (
        <Badge className="gap-1">
          <TrendingUp className="size-3" />
          {t('goals.onTrack')}
        </Badge>
      )
    }
    return (
      <Badge variant="destructive" className="gap-1">
        <TrendingDown className="size-3" />
        {t('goals.behind')}
      </Badge>
    )
  })()

  return (
    <Card
      className="cursor-pointer transition-colors hover:bg-accent/50"
      onClick={onOpenDetail}
    >
      <CardContent className="p-4">
        {/* Header: name + status + actions */}
        <div className="flex items-start justify-between gap-2 mb-3">
          <div className="flex items-center gap-2 min-w-0">
            <span className="cn-font-heading truncate text-sm font-semibold text-foreground">
              {goal.name}
            </span>
            {statusBadge}
          </div>
          <div className="flex items-center gap-0.5 shrink-0" onClick={(e) => e.stopPropagation()}>
            <Button variant="ghost" size="icon" onClick={onEdit} aria-label={t('common.edit')}>
              <Pencil className="size-4" />
            </Button>
            <Button variant="ghost" size="icon" onClick={onCalendar} aria-label={t('goals.viewCalendar')}>
              <Calendar className="size-4" />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              className="text-muted-foreground hover:text-destructive"
              onClick={onDelete}
              aria-label={t('goals.deleteGoal')}
            >
              <Trash2 className="size-4" />
            </Button>
          </div>
        </div>

        {/* Current total */}
        <CurrencyDisplay
          value={goal.currentTotal}
          className="text-3xl font-semibold tabular-nums"
        />

        {/* Progress bar */}
        <Progress
          value={goal.percentComplete}
          className="h-2.5 mt-3 [&_[data-slot=progress-indicator]]:bg-emerald-500"
        />

        {/* Footer: percent + target */}
        <div className="flex items-center justify-between mt-3">
          <span className="text-sm text-muted-foreground">
            {Math.round(goal.percentComplete)}% {t('dashboard.achieved')}
          </span>
          <CurrencyDisplay
            value={goal.targetAmount}
            className="text-sm font-medium tabular-nums"
          />
        </div>

        {/* Secondary stats */}
        <div className="grid grid-cols-3 gap-3 mt-3 pt-3 border-t">
          <div>
            <p className="text-xs text-muted-foreground mb-0.5">{t('goals.monthsLeft')}</p>
            <p className="text-sm font-semibold">{goal.monthsLeft}</p>
          </div>
          <div>
            <p className="text-xs text-muted-foreground mb-0.5">{t('goals.monthlyNeeded')}</p>
            <p className="text-sm font-semibold">
              <CurrencyDisplay value={goal.monthlyNeeded} />
            </p>
          </div>
          <div>
            <p className="text-xs text-muted-foreground mb-0.5">{t('goals.avgContribution')}</p>
            <p className="text-sm font-semibold">
              {goal.avgMonthlyContribution != null ? (
                <CurrencyDisplay value={goal.avgMonthlyContribution} />
              ) : (
                '\u2013'
              )}
            </p>
          </div>
        </div>
      </CardContent>
    </Card>
  )
}
