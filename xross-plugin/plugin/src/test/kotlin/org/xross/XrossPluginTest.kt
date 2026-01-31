package org.xross

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertNotNull

class XrossPluginTest {
    @Test fun `plugin registers xross tasks`() {
        val project = ProjectBuilder.builder().build()

        // プラグインを適用
        project.plugins.apply("org.xross")

        // 【重要】afterEvaluate ブロックを実行させるために必要
        (project as org.gradle.api.internal.project.DefaultProject).evaluate()

        assertNotNull(project.tasks.findByName("generateXrossBindings"))
    }
}
