<script setup lang="ts">
import { ref } from 'vue'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'
import { FunctionalIterablePage, type IterablePage, type Pageable } from '@kinotic-ai/core'
import {
  CrudTable,
  FormDrawer,
  PageHeader,
  TimePill,
  pageNumberOf,
  showErrorToast,
  useCrudTablePage,
  type CrudHeader,
  type DescriptiveIdentifiable
} from '@kinotic-ai/frontend-common'
import type { DesignPartnerApplication } from '@/api/model'
import { staffDesignPartnerService } from '@/api/services'
import { billingPeriodName, formatDate, formatMoney, planName } from '@/util/format'

/** The queue of design partner applications, each reviewed in a drawer showing the company's figures and use. */
const toast = useToast()

const headers: CrudHeader[] = [
  { field: 'organization', header: 'Organization', sortable: true },
  { field: 'contact', header: 'Contact' },
  { field: 'plan', header: 'Plan' },
  { field: 'annualRevenue', header: 'Annual revenue' },
  { field: 'createdAt', header: 'Applied' }
]

type Row = DescriptiveIdentifiable & DesignPartnerApplication

async function load(pageable: Pageable, searchText: string | null): Promise<IterablePage<DescriptiveIdentifiable>> {
  const needle = searchText?.trim().toLowerCase()
  const rows: Row[] = (await staffDesignPartnerService.findPendingApplications())
      .map(application => ({ ...application, name: application.organization }))
      .filter(row => !needle || [row.organization, row.contactName ?? '', row.contactEmail ?? ''].some(field => field.toLowerCase().includes(needle)))
  const start = pageNumberOf(pageable) * pageable.pageSize
  const page = { content: rows.slice(start, start + pageable.pageSize), totalElements: rows.length, cursor: undefined }
  return new FunctionalIterablePage(pageable, page, (next: Pageable) => load(next, searchText))
}

const { tableSearch, dataSource, refreshTable } = useCrudTablePage(load)

const reviewing = ref<DesignPartnerApplication | null>(null)
const deciding = ref(false)

async function decide(approve: boolean) {
  if (!reviewing.value) {
    return
  }
  deciding.value = true
  try {
    if (approve) {
      await staffDesignPartnerService.approveApplication(reviewing.value.id)
      toast.add({ severity: 'success', summary: 'Application approved', detail: 'The contact was emailed the link to start the subscription.', life: 5000 })
    } else {
      await staffDesignPartnerService.rejectApplication(reviewing.value.id)
      toast.add({ severity: 'success', summary: 'Application rejected', detail: 'The contact was told.', life: 5000 })
    }
    reviewing.value = null
    refreshTable()
  } catch (err) {
    showErrorToast(toast, 'The application could not be reviewed', err, { life: 8000 })
  } finally {
    deciding.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader title="Design partner applications"
                description="Applications awaiting review, oldest first. Approving emails the link to a subscription at half off for 36 months." />
    <CrudTable ref="crudTable" :headers="headers" :data-source="dataSource" :search="tableSearch" :is-show-add-new="false"
               empty-state-text="No applications waiting" @update:search="tableSearch = $event" @on-row-click="reviewing = $event">
      <template #item.contact="{ item }">{{ item.contactName }} <span class="app-muted-text">&lt;{{ item.contactEmail }}&gt;</span></template>
      <template #item.plan="{ item }">{{ planName(item.plan) }} · {{ billingPeriodName(item.billingPeriod) }}</template>
      <template #item.annualRevenue="{ item }">{{ formatMoney(item.figures.annualRevenue, 'usd') }}</template>
      <template #item.createdAt="{ item }"><TimePill :date="item.createdAt" /></template>
    </CrudTable>

    <FormDrawer :visible="reviewing !== null" :title="reviewing?.organization ?? ''" description="The company's figures and what it uses Kinotic for." @close="reviewing = null">
      <dl v-if="reviewing" class="flex flex-col gap-4 text-sm">
        <div><dt class="app-muted-text">Contact</dt><dd class="app-heading-text mt-1">{{ reviewing.contactName }} &lt;{{ reviewing.contactEmail }}&gt;</dd></div>
        <div class="grid grid-cols-2 gap-4">
          <div><dt class="app-muted-text">Plan</dt><dd class="app-heading-text mt-1">{{ planName(reviewing.plan) }}, billed {{ billingPeriodName(reviewing.billingPeriod).toLowerCase() }}</dd></div>
          <div><dt class="app-muted-text">Annual revenue</dt><dd class="app-heading-text mt-1 tabular-nums">{{ formatMoney(reviewing.figures.annualRevenue, 'usd') }}</dd></div>
          <div v-if="reviewing.figures.capitalRaised !== null"><dt class="app-muted-text">Capital raised</dt><dd class="app-heading-text mt-1 tabular-nums">{{ formatMoney(reviewing.figures.capitalRaised, 'usd') }}</dd></div>
        </div>
        <div><dt class="app-muted-text">What they use Kinotic for</dt><dd class="app-heading-text mt-1 whitespace-pre-wrap">{{ reviewing.intendedUse }}</dd></div>
        <div><dt class="app-muted-text">Applied</dt><dd class="app-heading-text mt-1">{{ formatDate(reviewing.createdAt) }}</dd></div>
      </dl>
      <template #footer>
        <Button type="button" label="Reject" severity="danger" outlined :loading="deciding" @click="decide(false)" />
        <Button type="button" label="Approve" :loading="deciding" @click="decide(true)" />
      </template>
    </FormDrawer>
  </div>
</template>
