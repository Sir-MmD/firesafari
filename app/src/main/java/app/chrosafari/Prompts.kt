package app.chrosafari

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.InputType
import android.text.format.DateFormat
import android.widget.EditText
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AlertPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AuthPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BasePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BeforeUnloadPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ButtonPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ColorPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.DateTimePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PopupPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptInstanceDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSession.PromptDelegate.RedirectPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.RepostConfirmPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.SharePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.TextPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt.Choice
import java.io.File
import java.util.Calendar
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Native UI for everything a page can ask the user: alert/confirm/prompt, <select>, date and
 * time inputs, file uploads, HTTP auth, popups, leave-page warnings and navigator.share().
 */
class Prompts(private val activity: WebAppActivity) : GeckoSession.PromptDelegate {

    override fun onAlertPrompt(session: GeckoSession, prompt: AlertPrompt) = dialog(prompt) {
        setTitle(titleOf(prompt))
        setMessage(prompt.message)
        setPositiveButton(android.R.string.ok, null)
    }

    override fun onButtonPrompt(session: GeckoSession, prompt: ButtonPrompt) = dialog(prompt) { respond ->
        setTitle(titleOf(prompt))
        setMessage(prompt.message)
        setPositiveButton(android.R.string.ok) { _, _ -> respond(prompt.confirm(ButtonPrompt.Type.POSITIVE)) }
        setNegativeButton(android.R.string.cancel) { _, _ -> respond(prompt.confirm(ButtonPrompt.Type.NEGATIVE)) }
    }

    override fun onTextPrompt(session: GeckoSession, prompt: TextPrompt) = dialog(prompt) { respond ->
        val input = EditText(activity).apply {
            setSingleLine()
            setText(prompt.defaultValue)
        }
        setTitle(titleOf(prompt))
        setMessage(prompt.message)
        setView(activity.padded(input))
        setPositiveButton(android.R.string.ok) { _, _ -> respond(prompt.confirm(input.text.toString())) }
        setNegativeButton(android.R.string.cancel, null)
    }

    override fun onAuthPrompt(session: GeckoSession, prompt: AuthPrompt) = dialog(prompt) { respond ->
        val onlyPassword = prompt.authOptions.flags and AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0
        val username = EditText(activity).apply {
            hint = "Username"
            setSingleLine()
            setText(prompt.authOptions.username)
        }
        val password = EditText(activity).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prompt.authOptions.password)
        }
        setTitle(titleOf(prompt))
        setMessage(prompt.message)
        setView(if (onlyPassword) activity.padded(password) else activity.padded(username, password))
        setPositiveButton(android.R.string.ok) { _, _ ->
            respond(
                if (onlyPassword) prompt.confirm(password.text.toString())
                else prompt.confirm(username.text.toString(), password.text.toString())
            )
        }
        setNegativeButton(android.R.string.cancel, null)
    }

    override fun onChoicePrompt(session: GeckoSession, prompt: ChoicePrompt): GeckoResult<PromptResponse> {
        val choices = selectable(prompt.choices)
        val labels = choices.map { it.label }.toTypedArray()
        return dialog(prompt) { respond ->
            prompt.title?.takeIf { it.isNotBlank() }?.let { setTitle(it) }
            if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
                val checked = choices.map { it.selected }.toBooleanArray()
                setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                setPositiveButton(android.R.string.ok) { _, _ ->
                    respond(prompt.confirm(choices.filterIndexed { i, _ -> checked[i] }.toTypedArray()))
                }
                setNegativeButton(android.R.string.cancel, null)
            } else {
                setSingleChoiceItems(labels, choices.indexOfFirst { it.selected }) { dialog, which ->
                    respond(prompt.confirm(choices[which]))
                    dialog.dismiss()
                }
            }
        }
    }

    override fun onColorPrompt(session: GeckoSession, prompt: ColorPrompt) = dialog(prompt) { respond ->
        val input = EditText(activity).apply {
            setSingleLine()
            setText(prompt.defaultValue ?: "#000000")
        }
        setTitle("Color (#rrggbb)")
        setView(activity.padded(input))
        setPositiveButton(android.R.string.ok) { _, _ ->
            val value = input.text.toString().trim()
            if (Regex("^#[0-9a-fA-F]{6}$").matches(value)) respond(prompt.confirm(value.lowercase(Locale.ROOT)))
        }
        setNegativeButton(android.R.string.cancel, null)
    }

    override fun onDateTimePrompt(session: GeckoSession, prompt: DateTimePrompt): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        var done = false
        val finish = { response: PromptResponse ->
            if (!done) {
                done = true
                result.complete(response)
            }
        }
        val initial = parseDateTime(prompt.type, prompt.defaultValue)

        fun withClearAndCancel(dialog: AlertDialog) = dialog.apply {
            setButton(DialogInterface.BUTTON_NEUTRAL, "Clear") { _, _ -> finish(prompt.confirm("")) }
            setOnCancelListener { finish(prompt.dismiss()) }
        }

        fun pickTime(onPicked: (Int, Int) -> Unit) = withClearAndCancel(
            TimePickerDialog(
                activity, { _, hour, minute -> onPicked(hour, minute) },
                initial.get(Calendar.HOUR_OF_DAY), initial.get(Calendar.MINUTE), DateFormat.is24HourFormat(activity),
            )
        ).show()

        fun pickDate(onPicked: (Int, Int, Int) -> Unit) = withClearAndCancel(
            DatePickerDialog(
                activity, { _, year, month, day -> onPicked(year, month + 1, day) },
                initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH),
            )
        ).show()

        when (prompt.type) {
            DateTimePrompt.Type.TIME -> pickTime { h, m -> finish(prompt.confirm(format("%02d:%02d", h, m))) }
            DateTimePrompt.Type.DATETIME_LOCAL -> pickDate { y, mo, d ->
                pickTime { h, m -> finish(prompt.confirm(format("%04d-%02d-%02dT%02d:%02d", y, mo, d, h, m))) }
            }
            DateTimePrompt.Type.MONTH -> pickDate { y, mo, _ -> finish(prompt.confirm(format("%04d-%02d", y, mo))) }
            DateTimePrompt.Type.WEEK -> pickDate { y, mo, d ->
                val week = isoCalendar().apply { set(y, mo - 1, d) }
                finish(prompt.confirm(format("%04d-W%02d", week.weekYear, week.get(Calendar.WEEK_OF_YEAR))))
            }
            else -> pickDate { y, mo, d -> finish(prompt.confirm(format("%04d-%02d-%02d", y, mo, d))) }
        }
        return result
    }

    override fun onFilePrompt(session: GeckoSession, prompt: FilePrompt): GeckoResult<PromptResponse> {
        if (prompt.type == FilePrompt.Type.FOLDER) return GeckoResult.fromValue(prompt.dismiss())
        val mimeTypes = prompt.mimeTypes.orEmpty().filter { '/' in it }.ifEmpty { listOf("*/*") }
        val pick = Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mimeTypes.singleOrNull() ?: "*/*")
        if (mimeTypes.size > 1) pick.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
        if (prompt.type == FilePrompt.Type.MULTIPLE) pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        val chooser = Intent.createChooser(pick, null)

        // Offer the camera too when the page accepts images (and camera access was already granted).
        val wantsImages = mimeTypes.any { it == "*/*" || it.startsWith("image/") }
        val capture = if (wantsImages) activity.newCaptureFile() else null
        capture?.let { (_, uri) ->
            val camera = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera))
        }

        val result = GeckoResult<PromptResponse>()
        activity.pickFiles(chooser) { picked ->
            val data = picked.data
            val clip = data?.clipData
            val picks: List<Uri> = when {
                picked.resultCode != Activity.RESULT_OK -> emptyList()
                clip != null -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                data?.data != null -> listOf(data.data!!)
                else -> emptyList()
            }
            val photo = capture?.first?.takeIf { picked.resultCode == Activity.RESULT_OK && picks.isEmpty() && it.length() > 0 }
            // Copying can take a while for big files; do it off the UI thread.
            thread {
                val files = photo?.let { listOf(it) } ?: picks.mapNotNull { copyForUpload(it) }
                val uris = files.map { Uri.fromFile(it) }
                activity.runOnUiThread {
                    result.complete(
                        when {
                            uris.isEmpty() -> prompt.dismiss()
                            prompt.type == FilePrompt.Type.MULTIPLE -> prompt.confirm(activity, uris.toTypedArray())
                            else -> prompt.confirm(activity, uris.first())
                        }
                    )
                }
            }
        }
        return result
    }

    /** The engine only reads files by path, so copy a picked document into the site's uploads folder. */
    private fun copyForUpload(uri: Uri): File? = runCatching {
        val resolver = activity.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?.replace('/', '_')?.ifBlank { null } ?: "upload"
        // A folder per pick keeps the original file name without clashing with earlier picks.
        val dir = File(activity.uploadsDir, System.nanoTime().toString()).apply { mkdirs() }
        File(dir, name).also { file ->
            resolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        }
    }.getOrNull()

    override fun onPopupPrompt(session: GeckoSession, prompt: PopupPrompt) = dialog(prompt) { respond ->
        setTitle("${activity.siteHost()} wants to open a new window")
        setMessage(prompt.targetUri)
        setPositiveButton("Open") { _, _ -> respond(prompt.confirm(AllowOrDeny.ALLOW)) }
        setNegativeButton("Block") { _, _ -> respond(prompt.confirm(AllowOrDeny.DENY)) }
    }

    override fun onRedirectPrompt(session: GeckoSession, prompt: RedirectPrompt) = dialog(prompt) { respond ->
        setTitle("An embedded frame wants to leave this page")
        setMessage(prompt.targetUri)
        setPositiveButton("Allow") { _, _ -> respond(prompt.confirm(AllowOrDeny.ALLOW)) }
        setNegativeButton("Block") { _, _ -> respond(prompt.confirm(AllowOrDeny.DENY)) }
    }

    override fun onBeforeUnloadPrompt(session: GeckoSession, prompt: BeforeUnloadPrompt) = dialog(prompt) { respond ->
        setTitle("Leave this page?")
        setMessage("Changes you made may not be saved.")
        setPositiveButton("Leave") { _, _ -> respond(prompt.confirm(AllowOrDeny.ALLOW)) }
        setNegativeButton("Stay") { _, _ -> respond(prompt.confirm(AllowOrDeny.DENY)) }
    }

    override fun onRepostConfirmPrompt(session: GeckoSession, prompt: RepostConfirmPrompt) = dialog(prompt) { respond ->
        setTitle("Send the form again?")
        setMessage("Reloading this page resends the information you entered.")
        setPositiveButton("Resend") { _, _ -> respond(prompt.confirm(AllowOrDeny.ALLOW)) }
        setNegativeButton(android.R.string.cancel) { _, _ -> respond(prompt.confirm(AllowOrDeny.DENY)) }
    }

    override fun onSharePrompt(session: GeckoSession, prompt: SharePrompt): GeckoResult<PromptResponse> {
        val text = listOfNotNull(prompt.text, prompt.uri).joinToString("\n")
        val response = try {
            activity.shareText(text, prompt.title)
            prompt.confirm(SharePrompt.Result.SUCCESS)
        } catch (e: ActivityNotFoundException) {
            prompt.confirm(SharePrompt.Result.FAILURE)
        }
        return GeckoResult.fromValue(response)
    }

    // --- Helpers -----------------------------------------------------------------------------

    /**
     * Shows an AlertDialog for [prompt]. Buttons call `respond`; closing the dialog any other way
     * dismisses the prompt. If the page withdraws the prompt, the dialog closes too.
     */
    private fun dialog(
        prompt: BasePrompt,
        configure: AlertDialog.Builder.(respond: (PromptResponse) -> Unit) -> Unit,
    ): GeckoResult<PromptResponse> {
        if (activity.isFinishing || activity.isDestroyed) return GeckoResult.fromValue(prompt.dismiss())
        val result = GeckoResult<PromptResponse>()
        var response: PromptResponse? = null
        val dialog = AlertDialog.Builder(activity).apply { configure { response = it } }.create()
        dialog.setOnDismissListener { result.complete(response ?: prompt.dismiss()) }
        prompt.setDelegate(object : PromptInstanceDelegate {
            override fun onPromptDismiss(prompt: BasePrompt) = dialog.dismiss()
        })
        dialog.show()
        return result
    }

    private fun titleOf(prompt: BasePrompt) = prompt.title?.takeIf { it.isNotBlank() } ?: activity.siteHost()

    /** <select> options, with <optgroup> contents flattened and separators/disabled options dropped. */
    private fun selectable(choices: Array<Choice>): List<Choice> = choices.flatMap { choice ->
        val items = choice.items
        when {
            choice.separator || choice.disabled -> emptyList()
            items != null -> selectable(items)
            else -> listOf(choice)
        }
    }

    private fun format(pattern: String, vararg args: Int) = String.format(Locale.ROOT, pattern, *args.toTypedArray())

    private fun isoCalendar() = Calendar.getInstance().apply {
        firstDayOfWeek = Calendar.MONDAY
        minimalDaysInFirstWeek = 4
    }

    /** Reads an <input> value (2026-10-11, 14:30, 2026-10-11T14:30, 2026-10, 2026-W41) into a Calendar. */
    private fun parseDateTime(type: Int, value: String?): Calendar {
        val calendar = Calendar.getInstance()
        if (value.isNullOrEmpty()) return calendar
        if (type == DateTimePrompt.Type.WEEK) {
            Regex("""^(\d{4,})-W(\d{2})$""").find(value)?.destructured?.let { (year, week) ->
                return isoCalendar().apply { setWeekDate(year.toInt(), week.toInt(), Calendar.MONDAY) }
            }
            return calendar
        }
        Regex("""^(\d{4,})-(\d{2})(?:-(\d{2}))?""").find(value)?.destructured?.let { (year, month, day) ->
            calendar.set(year.toInt(), month.toInt() - 1, day.toIntOrNull() ?: 1)
        }
        Regex("""(?:^|T)(\d{2}):(\d{2})""").find(value)?.destructured?.let { (hour, minute) ->
            calendar.set(Calendar.HOUR_OF_DAY, hour.toInt())
            calendar.set(Calendar.MINUTE, minute.toInt())
        }
        return calendar
    }
}
