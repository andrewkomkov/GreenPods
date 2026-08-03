package io.github.andrewkomkov.greenpods

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Grants runtime permissions before anything else in the test runs.
 *
 * A freshly installed build has none, so [MainActivity] asks for them in `onCreate` and
 * the system dialog takes over the screen — leaving the test looking at a window with
 * no Compose hierarchy in it. Granting has to happen *outside* the activity rule, which
 * `@Before` cannot do, hence a rule rather than a setup method.
 */
class GrantPermissionsRule(
    private vararg val permissions: String,
) : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val packageName = instrumentation.targetContext.packageName
                permissions.forEach { permission ->
                    // Already-granted or undeclared permissions throw; neither is a
                    // reason to fail the test that is about to run.
                    runCatching { instrumentation.uiAutomation.grantRuntimePermission(packageName, permission) }
                }
                base.evaluate()
            }
        }
}
