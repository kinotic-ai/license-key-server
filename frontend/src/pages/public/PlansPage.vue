<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useToast } from 'primevue/usetoast'
import { PageHeader, showErrorToast } from '@kinotic-ai/frontend-common'
import type { PlanInfo } from '@/api/model'
import { fetchPlans } from '@/api/rest'
import PlanCard from '@/components/PlanCard.vue'

/** The plans, priced from Stripe, each leading to its purchase or request. */
const toast = useToast()
const plans = ref<PlanInfo[]>([])
const loading = ref(true)

onMounted(async () => {
  try {
    plans.value = await fetchPlans()
  } catch (err) {
    showErrorToast(toast, 'The plans could not be loaded', err)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div>
    <PageHeader title="Kinotic Enterprise plans"
                description="Every paid plan is self-serve and bills monthly through Stripe. A license key is issued the moment payment succeeds, and a new one is emailed before each twelve-month term ends." />
    <div v-if="loading" class="app-muted-text text-sm">Loading plans…</div>
    <div v-else class="grid gap-6 md:grid-cols-2 xl:grid-cols-4">
      <PlanCard v-for="plan in plans" :key="plan.id" :plan="plan" />
    </div>
  </div>
</template>
