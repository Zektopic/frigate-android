package com.zektopic.frigate.data

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

/** One-off rewrites of a stored config whose meaning changed between app versions. */
object ConfigMigrations {

    /**
     * Drops `motion.threshold` values written under the old meaning.
     *
     * Earlier builds read `motion.threshold` as "percent of pixels that changed" and
     * the camera wizard wrote values of 1-50 into it. The key now means what it means
     * in Frigate: a per-pixel luma difference from 1 to 255, default 30. Left alone, a
     * wizard-written `threshold: 2` would flag sensor noise as motion on every frame.
     * Removing the key falls back to the default; values the wizard could not have
     * written (above 50) are assumed to be intentional and kept.
     *
     * @return the rewritten YAML, or null when nothing needed to change.
     */
    @Suppress("UNCHECKED_CAST")
    fun dropLegacyMotionThresholds(yamlText: String): String? {
        val root = try {
            Yaml().load<Any>(yamlText) as? MutableMap<String, Any?>
        } catch (e: Exception) {
            null
        } ?: return null

        var changed = false
        fun strip(holder: MutableMap<String, Any?>) {
            val motion = holder["motion"] as? MutableMap<String, Any?> ?: return
            val threshold = (motion["threshold"] as? Number)?.toInt() ?: return
            if (threshold > LEGACY_WIZARD_MAX) return
            motion.remove("threshold")
            if (motion.isEmpty()) holder.remove("motion")
            changed = true
        }

        strip(root)
        (root["cameras"] as? Map<String, Any?>)?.values?.forEach { camera ->
            (camera as? MutableMap<String, Any?>)?.let(::strip)
        }
        if (!changed) return null

        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            indent = 2
            isPrettyFlow = true
        }
        return Yaml(options).dump(root)
    }

    private const val LEGACY_WIZARD_MAX = 50
}
