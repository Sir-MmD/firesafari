package app.firesafari

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/** The list of sites. Each one opens as its own full-screen "installed web app". */
class MainActivity : Activity() {
    private lateinit var adapter: ArrayAdapter<Site>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setActionBar(findViewById(R.id.toolbar))

        // Edge to edge: keep the toolbar below the status bar, let the list scroll under the navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            insets
        }

        val list = findViewById<ListView>(R.id.list)
        list.emptyView = findViewById(R.id.empty)
        adapter = object : ArrayAdapter<Site>(this, android.R.layout.simple_list_item_2, android.R.id.text1) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val site = getItem(position)!!
                view.findViewById<TextView>(android.R.id.text1).text = site.name
                view.findViewById<TextView>(android.R.id.text2).text = site.url
                return view
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> open(adapter.getItem(position)!!) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            showSiteOptions(adapter.getItem(position)!!)
            true
        }
        ViewCompat.setOnApplyWindowInsetsListener(list) { view, insets ->
            view.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            insets
        }

        handleSharedLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedLink(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_ADD, 0, "Add site").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, MENU_USER_AGENT, 1, "iPhone user agent")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_ADD -> editSite(null)
            MENU_USER_AGENT -> editUserAgent()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun refresh() {
        adapter.clear()
        adapter.addAll(Sites.all(this))
    }

    private fun open(site: Site) = startActivity(Shortcuts.launchIntent(this, site))

    private fun handleSharedLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        setIntent(Intent(this, MainActivity::class.java))
        editSite(null, Regex("""https?://\S+""").find(text)?.value ?: text.trim())
    }

    private fun showSiteOptions(site: Site) {
        val actions = listOf<Pair<String, () -> Unit>>(
            "Open" to { open(site) },
            "Add to home screen" to { Shortcuts.pin(this, site) },
            "Edit" to { editSite(site) },
            "Clear cookies and site data" to { confirmClearData(site) },
            "Delete" to { confirmDelete(site) },
        )
        AlertDialog.Builder(this)
            .setTitle(site.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun editSite(site: Site?, prefillUrl: String? = null) {
        val name = EditText(this).apply {
            hint = "Name (optional)"
            setSingleLine()
            setText(site?.name)
        }
        val url = EditText(this).apply {
            hint = "Address, e.g. example.com"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(site?.url ?: prefillUrl)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (site == null) "Add site" else "Edit site")
            .setView(padded(name, url))
            .setPositiveButton("Save", null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            // Validate before closing, so a typo doesn't throw away what was typed.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = Sites.normalizeUrl(url.text.toString())
                if (address == null) {
                    url.error = "Enter a web address"
                    return@setOnClickListener
                }
                val label = name.text.toString().trim().ifEmpty { Uri.parse(address).host!!.removePrefix("www.") }
                val saved = if (site == null) {
                    Site.create(label, address)
                } else {
                    val sameHost = Uri.parse(site.url).host == Uri.parse(address).host
                    site.copy(name = label, url = address, iconUrl = site.iconUrl.takeIf { sameHost })
                }
                Sites.put(this, saved)
                if (site != null && site.name != saved.name) Shortcuts.rename(this, saved)
                refresh()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun confirmClearData(site: Site) {
        AlertDialog.Builder(this)
            .setTitle("Clear data for ${site.name}?")
            .setMessage("You will be signed out of this site.")
            .setPositiveButton("Clear") { _, _ ->
                closeSiteTask(site)
                Engine.runtime(this).storageController.clearDataForSessionContext(site.contextId)
                toast("Cleared")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(site: Site) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${site.name}?")
            .setMessage("Its cookies and site data are deleted too.")
            .setPositiveButton("Delete") { _, _ ->
                closeSiteTask(site)
                Engine.runtime(this).storageController.clearDataForSessionContext(site.contextId)
                Shortcuts.disable(this, site)
                Sites.remove(this, site.id)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Closes the site if it is open, so it can't keep using data that was just cleared. */
    private fun closeSiteTask(site: Site) {
        getSystemService(ActivityManager::class.java).appTasks
            .filter { it.taskInfo?.baseIntent?.data == site.launchUri }
            .forEach { it.finishAndRemoveTask() }
    }

    private fun editUserAgent() {
        val field = EditText(this).apply {
            setText(IosProfile.userAgent(context))
            minLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        AlertDialog.Builder(this)
            .setTitle("iPhone user agent")
            .setMessage("When this gets old, copy the current one from Safari on a real iPhone. The app restarts to apply it.")
            .setView(padded(field))
            .setPositiveButton("Save") { _, _ -> applyUserAgent(field.text.toString()) }
            .setNeutralButton("Reset") { _, _ -> applyUserAgent(null) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyUserAgent(userAgent: String?) {
        val before = IosProfile.userAgent(this)
        IosProfile.setUserAgent(this, userAgent)
        if (IosProfile.userAgent(this) == before) return
        // The engine reads its prefs once at startup, so restart the whole process.
        startActivity(Intent.makeRestartActivityTask(componentName))
        Runtime.getRuntime().exit(0)
    }

    private companion object {
        const val MENU_ADD = 1
        const val MENU_USER_AGENT = 2
    }
}
