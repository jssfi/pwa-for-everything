package fi.jss.pwa

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var grid: GridView
    private lateinit var empty: View
    private var sites: List<Site> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ -> open(this, sites[pos]) }
        grid.setOnItemLongClickListener { _, _, pos, _ -> showSiteMenu(sites[pos]); true }
        findViewById<View>(R.id.add).setOnClickListener { showEditor(null) }
        handleShare(intent)
        intent.getStringExtra(EXTRA_EDIT_ID)?.let { id -> SiteStore.get(this, id)?.let { showEditor(it) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
        intent.getStringExtra(EXTRA_EDIT_ID)?.let { id -> SiteStore.get(this, id)?.let { showEditor(it) } }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        sites = SiteStore.all(this)
        empty.visibility = if (sites.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    /** A page shared from a browser: pre-fill the editor with its URL and title. */
    private fun handleShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val m = Patterns.WEB_URL.matcher(text)
        val url = if (m.find()) m.group() else text
        val title = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        showEditor(null, prefillUrl = url, prefillName = title)
        intent.action = null
    }

    private fun showSiteMenu(site: Site) {
        val items = arrayOf(getString(R.string.add_to_home), getString(R.string.edit), getString(R.string.delete))
        AlertDialog.Builder(this)
            .setTitle(site.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pinShortcut(site)
                    1 -> showEditor(site)
                    2 -> confirmDelete(site)
                }
            }
            .show()
    }

    private fun confirmDelete(site: Site) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm, site.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                SiteStore.delete(this, site.id)
                getSystemService(ShortcutManager::class.java)
                    .disableShortcuts(listOf(site.id), getString(R.string.site_missing))
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEditor(existing: Site?, prefillUrl: String? = null, prefillName: String? = null) {
        val view = layoutInflater.inflate(R.layout.dialog_site, null)
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

        val dialog = AlertDialog.Builder(this)
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
                    url.error = getString(R.string.invalid_url)
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
                SiteStore.save(this, site)
                updatePinnedShortcut(this, site)
                refresh()
                dialog.dismiss()
            }
        }
        dialog.show()
        if (url.text.isEmpty()) url.requestFocus()
    }

    /** "https://app.excalidraw.com" -> "Excalidraw": the label just left of the top-level domain. */
    private fun defaultName(url: String): String {
        val labels = android.net.Uri.parse(url).host.orEmpty().split('.').filter { it.isNotEmpty() }
        val label = if (labels.size >= 2) labels[labels.size - 2] else labels.firstOrNull().orEmpty()
        return label.replaceFirstChar { it.uppercase() }.ifEmpty { url }
    }

    private fun pinShortcut(site: Site) {
        val sm = getSystemService(ShortcutManager::class.java)
        if (!sm.isRequestPinShortcutSupported) {
            Toast.makeText(this, R.string.pin_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        sm.requestPinShortcut(shortcutFor(this, site), null)
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = sites.size
        override fun getItem(position: Int) = sites[position]
        override fun getItemId(position: Int) = sites[position].id.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_site, parent, false)
            val site = sites[position]
            val px = (64 * resources.displayMetrics.density).toInt()
            v.findViewById<ImageView>(R.id.icon).setImageBitmap(SiteIcons.display(this@MainActivity, site, px))
            v.findViewById<TextView>(R.id.label).text = site.name
            v.findViewById<TextView>(R.id.host).text = site.host
            return v
        }
    }

    companion object {
        const val EXTRA_EDIT_ID = "edit_id"

        fun launchIntent(context: Context, site: Site): Intent =
            Intent(Intent.ACTION_VIEW, site.launchUri, context, WebAppActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)

        fun open(context: Context, site: Site) = context.startActivity(launchIntent(context, site))

        fun shortcutFor(context: Context, site: Site): ShortcutInfo =
            ShortcutInfo.Builder(context, site.id)
                .setShortLabel(site.name)
                .setLongLabel(site.name)
                .setIcon(SiteIcons.adaptive(context, site))
                .setIntent(launchIntent(context, site))
                .build()

        /** Refreshes the label and icon of an already pinned shortcut. A no-op if it was never pinned. */
        fun updatePinnedShortcut(context: Context, site: Site) {
            val sm = context.getSystemService(ShortcutManager::class.java)
            if (sm.pinnedShortcuts.any { it.id == site.id }) {
                sm.enableShortcuts(listOf(site.id))
                sm.updateShortcuts(listOf(shortcutFor(context, site)))
            }
        }
    }
}
