<script setup lang="ts">
import Button from 'primevue/button'
import type { PlanInfo } from '@/api/model'
import { formatMoney } from '@/util/format'

defineProps<{
  plan: PlanInfo
}>()
</script>

<template>
  <article class="app-card-surface flex h-full flex-col rounded-2xl border p-6">
    <h2 class="app-heading-text text-xl font-medium">{{ plan.name }}</h2>
    <p class="mt-3 flex items-baseline gap-1">
      <span class="app-heading-text text-3xl font-semibold tabular-nums">
        {{ plan.requiresApproval ? 'Free' : formatMoney(plan.monthlyPrice, plan.currency) }}
      </span>
      <span v-if="!plan.requiresApproval" class="app-muted-text text-sm">/ month</span>
    </p>
    <p v-if="plan.requiresApproval" class="app-muted-text mt-2 text-sm">
      For small organizations below about $1M in annual revenue and $3M raised. Approved by Minds Ignited.
    </p>
    <ul class="app-muted-text mt-5 flex-1 space-y-2 text-sm">
      <li v-for="highlight in plan.highlights" :key="highlight" class="flex gap-2">
        <i class="pi pi-check mt-0.5 text-primary-500" /> {{ highlight }}
      </li>
    </ul>
    <RouterLink :to="plan.requiresApproval ? '/free' : { path: '/buy', query: { plan: plan.id } }" class="mt-6">
      <Button :label="plan.requiresApproval ? 'Request a free license' : 'Buy'" class="w-full"
              :severity="plan.requiresApproval ? 'secondary' : 'primary'" :outlined="plan.requiresApproval" />
    </RouterLink>
  </article>
</template>
