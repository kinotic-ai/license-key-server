<script setup lang="ts">
import { computed } from 'vue'
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import type { IssuedKey, LicensedCluster, LicenseWithKeys } from '@/api/model'
import KeyBlock from '@/components/KeyBlock.vue'
import { formatDate } from '@/util/format'

/**
 * A license's clusters: each cluster holding a seat with its id, when it was added and last renewed online, and its
 * newest key, marked when it is beyond the license's seats and gets no newer key; then the clusters released.
 */
const props = defineProps<{
  entry: LicenseWithKeys
  /** Offers a Release button on each cluster holding a seat. */
  releasable?: boolean
  /** The cluster being released, whose button shows it is busy. */
  releasing?: string | null
}>()

const emit = defineEmits<{
  release: [cluster: LicensedCluster]
}>()

const holding = computed(() => props.entry.clusters.filter(cluster => cluster.releasedAt === null))
const released = computed(() => props.entry.clusters.filter(cluster => cluster.releasedAt !== null))

// the keys are newest first, so a cluster's first is its newest
function keysOf(cluster: LicensedCluster): IssuedKey[] {
  return props.entry.keys.filter(key => key.licensedClusterId === cluster.id)
}
</script>

<template>
  <div class="flex flex-col gap-4">
    <div v-for="(cluster, index) in holding" :key="cluster.id" class="app-card-surface flex flex-col gap-3 rounded-xl border p-4">
      <div class="flex flex-wrap items-start justify-between gap-3">
        <div class="min-w-0 text-sm">
          <div class="flex flex-wrap items-center gap-2">
            <span class="app-heading-text font-medium">{{ cluster.name }}</span>
            <Tag v-if="index >= entry.license.seats" severity="warn" value="Beyond your seats" />
          </div>
          <div class="app-muted-text mt-1 break-all font-mono text-xs">{{ cluster.clusterId }}</div>
          <div class="app-muted-text mt-1">
            Added {{ formatDate(cluster.addedAt) }}
            · {{ cluster.lastRenewedAt ? `last renewed online ${formatDate(cluster.lastRenewedAt)}` : 'never renewed online' }}
          </div>
        </div>
        <Button v-if="releasable" label="Release" icon="pi pi-times" severity="secondary" outlined size="small"
                :loading="releasing === cluster.id" @click="emit('release', cluster)" />
      </div>
      <p v-if="index >= entry.license.seats" class="text-sm text-orange-600 dark:text-orange-400">
        The license has {{ entry.license.seats }} {{ entry.license.seats === 1 ? 'seat' : 'seats' }}, so this cluster gets no newer key.
        Release a cluster or add seats to keep it licensed.
      </p>
      <KeyBlock v-for="(key, keyIndex) in keysOf(cluster)" :key="key.jti" :issued-key="key" :collapsed="keyIndex > 0" />
    </div>
    <p v-if="!holding.length" class="app-muted-text text-sm">No cluster holds a seat.</p>
    <ul v-if="released.length" class="app-muted-text space-y-1 text-xs">
      <li v-for="cluster in released" :key="cluster.id">
        {{ cluster.name }} · <span class="font-mono">{{ cluster.clusterId }}</span> · released {{ formatDate(cluster.releasedAt) }}
      </li>
    </ul>
  </div>
</template>
