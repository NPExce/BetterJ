package pl.adrros.bettercodereview.colors

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Color

/**
 * Tints the `package` line of a file in a coloured package with that package's colour, so the editor shows which
 * part of the project the file belongs to. Works for any language that declares its package on a line of its own
 * (Java, Kotlin, Scala, Groovy, Go); only the main editors get the tint.
 */
object PackageLines {
    private val KEY = Key.create<RangeHighlighter>("BetterCodeReview.packageLine")
    private val PACKAGE = Regex("""(?m)^[ \t]*package[ \t]+[A-Za-z_`]""")

    /** The package declaration sits near the top, after at most a licence header. */
    private const val SCAN_CHARS = 20_000

    /** Above the caret row, so the tint stays when the caret is on that line. */
    private const val LAYER = HighlighterLayer.CARET_ROW + 1

    /** The 0-based line of the package declaration, or null. */
    fun packageLine(document: Document): Int? {
        val text = document.immutableCharSequence
        val match = PACKAGE.find(text.subSequence(0, minOf(text.length, SCAN_CHARS))) ?: return null
        return document.getLineNumber(match.range.first)
    }

    fun highlighterOf(editor: Editor): RangeHighlighter? = editor.getUserData(KEY)

    /** The tint for a file, or null; headless tests, whose files live outside the project's base directory, replace it. */
    var colorFor: (Project, VirtualFile) -> Color? = { project, file ->
        project.service<PackageColors>().takeIf { it.tintsPackageLine }?.colorOf(file)
    }

    /** Only editors of the editor area: diffs, previews and consoles stay as they are. */
    fun refresh(editor: Editor) {
        if (editor.editorKind == EditorKind.MAIN_EDITOR) tint(editor)
    }

    internal fun tint(editor: Editor) {
        if (editor.isDisposed) return
        val project = editor.project ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document)
        val color = file?.let { colorFor(project, it) }
        val line = color?.let { packageLine(editor.document) }
        val existing = editor.getUserData(KEY)
        if (existing != null && existing.isValid && line != null &&
            editor.document.getLineNumber(existing.startOffset) == line && existing.getTextAttributes(null)?.backgroundColor == color
        ) {
            return
        }
        existing?.let { editor.markupModel.removeHighlighter(it) }
        editor.putUserData(KEY, null)
        if (line != null) {
            editor.putUserData(KEY, editor.markupModel.addLineHighlighter(line, LAYER, TextAttributes().apply { backgroundColor = color }))
        }
    }

    fun refreshAll(project: Project) {
        EditorFactory.getInstance().allEditors.filter { it.project == project }.forEach(::refresh)
    }

    /** On plugin unload, so a newer version starts from clean editors. */
    fun removeAll() {
        for (editor in EditorFactory.getInstance().allEditors) {
            editor.getUserData(KEY)?.let { editor.markupModel.removeHighlighter(it) }
            editor.putUserData(KEY, null)
        }
    }

    class EditorListener : EditorFactoryListener {
        override fun editorCreated(event: EditorFactoryEvent) = refresh(event.editor)
    }

    /** An edit near the top may add, move or remove the package line; edits further down leave it where it is. */
    class EditListener : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            if (event.offset > SCAN_CHARS) return
            EditorFactory.getInstance().getEditors(event.document).forEach(::refresh)
        }
    }
}
