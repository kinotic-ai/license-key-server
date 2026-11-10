<script setup lang="ts">
import { computed } from 'vue'
import InputNumber from 'primevue/inputnumber'

/**
 * A company's figures, which set its plan: annual revenue, and the capital raised when the revenue is under $1M, where
 * it decides between Free and Small.
 */
const annualRevenue = defineModel<number | null>('annualRevenue', { required: true })
const capitalRaised = defineModel<number | null>('capitalRaised', { required: true })

const props = defineProps<{
  /** Asks for the capital raised whatever the revenue, as a free license certifies it. */
  alwaysAskCapital?: boolean
}>()

/** The annual revenue under which the capital raised decides the plan. */
const FREE_REVENUE_LIMIT = 1_000_000

const asksCapital = computed(() => props.alwaysAskCapital || (annualRevenue.value !== null && annualRevenue.value < FREE_REVENUE_LIMIT))
</script>

<template>
  <div class="grid gap-5 sm:grid-cols-2">
    <div>
      <label for="annual-revenue" class="mb-2 block text-sm font-medium">Annual revenue (USD)</label>
      <InputNumber input-id="annual-revenue" v-model="annualRevenue" class="w-full" mode="currency" currency="USD" locale="en-US"
                   :min="0" :max-fraction-digits="0" />
      <p class="app-subtle-text mt-1 text-xs">Including any companies yours owns.</p>
    </div>
    <div v-if="asksCapital">
      <label for="capital-raised" class="mb-2 block text-sm font-medium">Capital raised in total (USD)</label>
      <InputNumber input-id="capital-raised" v-model="capitalRaised" class="w-full" mode="currency" currency="USD" locale="en-US"
                   :min="0" :max-fraction-digits="0" />
      <p class="app-subtle-text mt-1 text-xs">Equity, SAFEs and convertible notes ever raised; not grants or loans.</p>
    </div>
  </div>
</template>
