package fi.jss.pwa

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager

/** Home screen shortcuts that open a site in [WebAppActivity] (the launcher app's "shortcut" mode). */
object Shortcuts {
    fun launchIntent(context: Context, site: Site): Intent =
        Intent(Intent.ACTION_VIEW, site.launchUri, context, WebAppActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)

    fun build(context: Context, site: Site): ShortcutInfo =
        ShortcutInfo.Builder(context, site.id)
            .setShortLabel(site.name)
            .setLongLabel(site.name)
            .setIcon(SiteIcons.adaptive(context, site))
            .setIntent(launchIntent(context, site))
            .build()

    /** Returns false when the launcher cannot pin shortcuts. */
    fun requestPin(context: Context, site: Site): Boolean {
        val sm = context.getSystemService(ShortcutManager::class.java)
        if (!sm.isRequestPinShortcutSupported) return false
        sm.requestPinShortcut(build(context, site), null)
        return true
    }

    /** Refreshes the label and icon of an already pinned shortcut. A no-op if it was never pinned. */
    fun updatePinned(context: Context, site: Site) {
        val sm = context.getSystemService(ShortcutManager::class.java)
        if (sm.pinnedShortcuts.any { it.id == site.id }) {
            sm.enableShortcuts(listOf(site.id))
            sm.updateShortcuts(listOf(build(context, site)))
        }
    }

    fun disable(context: Context, site: Site, message: CharSequence) {
        context.getSystemService(ShortcutManager::class.java).disableShortcuts(listOf(site.id), message)
    }
}
