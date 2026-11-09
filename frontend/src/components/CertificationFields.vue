<script setup lang="ts">
import InputNumber from 'primevue/inputnumber'
import Textarea from 'primevue/textarea'

/**
 * The figures an organization certifies for a free license: annual revenue, capital raised and intended use.
 */
const annualRevenue = defineModel<number | null>('annualRevenue', { required: true })
const capitalRaised = defineModel<number | null>('capitalRaised', { required: true })
const intendedUse = defineModel<string>('intendedUse', { required: true })
</script>

<template>
  <div class="grid gap-5 sm:grid-cols-2">
    <div>
      <label for="annual-revenue" class="mb-2 block text-sm font-medium">Annual revenue (USD)</label>
      <InputNumber input-id="annual-revenue" v-model="annualRevenue" class="w-full" mode="currency" currency="USD" locale="en-US"
                   :min="0" :max-fraction-digits="0" />
      <p class="app-subtle-text mt-1 text-xs">The free plan is for organizations below about $1M.</p>
    </div>
    <div>
      <label for="capital-raised" class="mb-2 block text-sm font-medium">Capital raised (USD)</label>
      <InputNumber input-id="capital-raised" v-model="capitalRaised" class="w-full" mode="currency" currency="USD" locale="en-US"
                   :min="0" :max-fraction-digits="0" />
      <p class="app-subtle-text mt-1 text-xs">Below about $3M in total.</p>
    </div>
  </div>
  <div>
    <label for="intended-use" class="mb-2 block text-sm font-medium">What will you build with Kinotic?</label>
    <Textarea id="intended-use" v-model="intendedUse" class="w-full" rows="4" auto-resize />
  </div>
</template>
