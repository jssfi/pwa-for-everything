package fi.jss.pwa

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.widget.EditText
import android.widget.Switch
import fi.jss.pwa.core.R

/** The add/edit dialog for a site. Saves to [SiteStore] and refreshes a pinned shortcut, then calls [onSaved]. */
object SiteEditor {
    fun show(
        activity: Activity,
        existing: Site?,
        prefillUrl: String? = null,
        prefillName: String? = null,
        onSaved: (Site) -> Unit,
    ) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_site, null)
        val url = view.findViewById<EditText>(R.id.url)
        val name = view.findViewById<EditText>(R.id.name)
        val fullscreen = view.findViewById<Switch>(R.id.fullscreen)
        val keepOn = view.findViewById<Switch>(R.id.keep_screen_on)
        val desktop = view.findViewById<Switch>(R.id.desktop)
        val external = view.findViewById<Switch>(R.id.external)

        val base = existing ?: Site(name = "", url = "")
        url.setText(existing?.url ?: prefillUrl.orEmpty())
        name.setText(existing?.name ?: prefillName.orEmpty())
        fullscreen.isChecked = base.fullscreen
        keepOn.isChecked = base.keepScreenOn
        desktop.isChecked = base.desktopMode
        external.isChecked = base.openExternalInBrowser

        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (existing == null) R.string.add_site else R.string.edit_site)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            // Validate before closing, so a typo does not throw away the form.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val normalized = Site.normalizeUrl(url.text.toString())
                if (normalized == null) {
                    url.error = activity.getString(R.string.invalid_url)
                    return@setOnClickListener
                }
                val site = base.copy(
                    url = normalized,
                    name = name.text.toString().trim().ifEmpty { defaultName(normalized) },
                    fullscreen = fullscreen.isChecked,
                    keepScreenOn = keepOn.isChecked,
                    desktopMode = desktop.isChecked,
                    openExternalInBrowser = external.isChecked,
                )
                SiteStore.save(activity, site)
                Shortcuts.updatePinned(activity, site)
                dialog.dismiss()
                onSaved(site)
            }
        }
        dialog.show()
        if (url.text.isEmpty()) url.requestFocus()
    }

    /** "https://app.excalidraw.com" -> "Excalidraw": the label just left of the top-level domain. */
    private fun defaultName(url: String): String {
        val labels = Uri.parse(url).host.orEmpty().split('.').filter { it.isNotEmpty() }
        val label = if (labels.size >= 2) labels[labels.size - 2] else labels.firstOrNull().orEmpty()
        return label.replaceFirstChar { it.uppercase() }.ifEmpty { url }
    }
}
