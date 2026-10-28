package fi.jss.pwa

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Patterns
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var grid: GridView
    private lateinit var empty: View
    private var sites: List<Site> = emptyList()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ -> startActivity(Shortcuts.launchIntent(this, sites[pos])) }
        grid.setOnItemLongClickListener { _, _, pos, _ -> showSiteMenu(sites[pos]); true }
        findViewById<View>(R.id.add).setOnClickListener { showEditor(null) }
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
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

    private fun showEditor(existing: Site?, prefillUrl: String? = null, prefillName: String? = null) =
        SiteEditor.show(this, existing, prefillUrl, prefillName) { refresh() }

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
        val pkg = SiteApkBuilder.packageName(site)
        val actions = mutableListOf<Pair<Int, () -> Unit>>()
        actions += R.string.add_to_home to {
            if (!Shortcuts.requestPin(this, site)) Toast.makeText(this, R.string.pin_unsupported, Toast.LENGTH_LONG).show()
        }
        if (SiteAppInstaller.isInstalled(this, pkg)) {
            actions += R.string.open_app to { packageManager.getLaunchIntentForPackage(pkg)?.let(::startActivity) }
            actions += R.string.update_app to { buildAndInstall(site) }
            actions += R.string.uninstall_app to { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))) }
        } else {
            actions += R.string.install_app to { confirmInstall(site) }
        }
        actions += R.string.edit to { showEditor(site) }
        actions += R.string.delete to { confirmDelete(site) }

        AlertDialog.Builder(this)
            .setTitle(site.name)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun confirmDelete(site: Site) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm, site.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                SiteStore.delete(this, site.id)
                Shortcuts.disable(this, site, getString(R.string.site_missing))
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- per-site apps

    private fun confirmInstall(site: Site) {
        if (SiteIcons.load(this, site.id) == null) {
            // The icon is only known after the site has been opened once.
            AlertDialog.Builder(this)
                .setTitle(R.string.no_icon_title)
                .setMessage(getString(R.string.no_icon_message, site.name))
                .setPositiveButton(R.string.open_site) { _, _ -> startActivity(Shortcuts.launchIntent(this, site)) }
                .setNeutralButton(R.string.install_anyway) { _, _ -> buildAndInstall(site) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(site.name)
            .setMessage(getString(R.string.install_explain, site.name))
            .setPositiveButton(R.string.install) { _, _ -> buildAndInstall(site) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun buildAndInstall(site: Site) {
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.install_permission_title)
                .setMessage(R.string.install_permission_message)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        val progress = AlertDialog.Builder(this)
            .setMessage(getString(R.string.building_app, site.name))
            .setCancelable(false)
            .show()
        thread {
            val error = try {
                val apk = SiteApkBuilder.build(this, site)
                SiteAppInstaller.install(this, apk, SiteApkBuilder.packageName(site), site.name)
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            main.post {
                progress.dismiss()
                if (error != null) Toast.makeText(this, getString(R.string.build_failed, error), Toast.LENGTH_LONG).show()
            }
        }
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
}
