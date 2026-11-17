<script setup lang="ts">
import Textarea from 'primevue/textarea'
import CompanyFiguresFields from '@/components/CompanyFiguresFields.vue'

/**
 * The figures an organization certifies for a free license: annual revenue, capital raised and intended use. Free is
 * for annual revenue under $1M with under $5M raised.
 */
const annualRevenue = defineModel<number | null>('annualRevenue', { required: true })
const capitalRaised = defineModel<number | null>('capitalRaised', { required: true })
const intendedUse = defineModel<string>('intendedUse', { required: true })
</script>

<template>
  <CompanyFiguresFields v-model:annual-revenue="annualRevenue" v-model:capital-raised="capitalRaised" always-ask-capital />
  <div>
    <label for="intended-use" class="mb-2 block text-sm font-medium">What will you build with Kinotic?</label>
    <Textarea id="intended-use" v-model="intendedUse" class="w-full" rows="4" auto-resize />
  </div>
</template>
