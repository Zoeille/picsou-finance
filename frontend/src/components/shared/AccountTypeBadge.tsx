import { useTranslation } from 'react-i18next'
import { Badge } from '@/components/ui/badge'
import type { AccountType } from '@/types/api'

interface AccountTypeBadgeProps {
  type: AccountType
  className?: string
}

const TYPE_KEY: Partial<Record<AccountType, string>> = {
  COMPTE_TITRES: 'compteTitres',
  REAL_ESTATE: 'realEstate',
  EMPLOYEE_SAVINGS: 'employeeSavings',
}

function getTypeKey(type: AccountType): string {
  return TYPE_KEY[type] ?? type.toLowerCase()
}

export function AccountTypeBadge({ type, className }: AccountTypeBadgeProps) {
  const { t } = useTranslation()
  return (
    <Badge variant="secondary" className={className}>
      {t(`accountTypes.${getTypeKey(type)}`)}
    </Badge>
  )
}
