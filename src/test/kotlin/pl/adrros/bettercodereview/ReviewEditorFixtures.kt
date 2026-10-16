package pl.adrros.bettercodereview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.testFramework.EditorTestUtil
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.replaceService
import org.jetbrains.plugins.github.api.GHRepositoryCoordinates
import org.jetbrains.plugins.github.api.GHRepositoryPath
import org.jetbrains.plugins.github.api.GithubServerPath
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import pl.adrros.bettercodereview.review.ReviewCommentEditors
import pl.adrros.bettercodereview.review.ReviewTarget
import pl.adrros.bettercodereview.review.ReviewTargets

object ReviewEditorFixtures {
    /**
     * Opens an editor shaped like the bundled review comment editor: an in-memory `Dummy.md` file.
     * Plain text stands in for Markdown, which the test IDE does not bundle, exactly as the real
     * editor falls back when the Markdown plugin is disabled.
     */
    fun CodeInsightTestFixture.openReviewCommentEditor(textWithCaret: String) {
        val caretOffset = textWithCaret.indexOf(EditorTestUtil.CARET_TAG)
        val text = textWithCaret.replace(EditorTestUtil.CARET_TAG, "")
        val file = LightVirtualFile(ReviewCommentEditors.COMMENT_FILE_NAME, PlainTextFileType.INSTANCE, text)
        configureFromExistingVirtualFile(file)
        if (caretOffset >= 0) editor.caretModel.moveToOffset(caretOffset)
    }

    val TEST_TARGET = ReviewTarget(
        GithubAccount("octo", GithubServerPath.DEFAULT_SERVER),
        GHRepositoryCoordinates(GithubServerPath.DEFAULT_SERVER, GHRepositoryPath("octo", "repo")),
    )

    /** Pretends a GitHub pull request is connected, which is what makes image pasting apply. */
    fun connectTestTarget(disposable: Disposable, target: ReviewTarget? = TEST_TARGET) {
        ApplicationManager.getApplication().replaceService(ReviewTargets::class.java, object : ReviewTargets {
            override fun current(project: Project): ReviewTarget? = target
        }, disposable)
    }
}
