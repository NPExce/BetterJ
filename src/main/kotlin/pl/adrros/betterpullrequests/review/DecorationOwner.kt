package pl.adrros.betterpullrequests.review

import javax.swing.JComponent

/**
 * Marks components this plugin version injected. After an install without restart the previous version's
 * components are still in the UI with the same client-property keys; the marker object is identical only
 * within one classloader, so a component from an unloaded version is recognised and replaced.
 */
object DecorationOwner {
    private const val KEY = "BetterPullRequests.owner"

    fun mark(component: JComponent) {
        component.putClientProperty(KEY, this)
    }

    fun owns(component: JComponent): Boolean = component.getClientProperty(KEY) === this
}
