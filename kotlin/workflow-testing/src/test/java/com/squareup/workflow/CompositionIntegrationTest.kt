/*
 * Copyright 2019 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.workflow

import com.squareup.workflow.testing.testFromStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CompositionIntegrationTest {

  @Test fun `composes parent with single child`() {
    val root = TreeWorkflow("root", TreeWorkflow("leaf"))

    // Setup initial state and change the state the workflow in the tree.
    root.testFromStart("initial input") { host ->
      host.withNextRendering {
        assertEquals("root:initial input", it.data)
        assertEquals("leaf:initial input[0]", it["leaf"].data)
      }
    }
  }

  @Test fun `composes parent with multiple children`() {
    val root = TreeWorkflow(
        "root",
        TreeWorkflow("leaf1"),
        TreeWorkflow("leaf2")
    )

    // Setup initial state and change the state the workflow in the tree.
    root.testFromStart("initial input") { host ->
      host.withNextRendering {
        assertEquals("root:initial input", it.data)
        assertEquals("leaf1:initial input[0]", it["leaf1"].data)
        assertEquals("leaf2:initial input[1]", it["leaf2"].data)
      }
    }
  }

  @Test fun `composes complex tree`() {
    val root = TreeWorkflow(
        "root",
        TreeWorkflow(
            "middle1",
            TreeWorkflow("leaf1"),
            TreeWorkflow("leaf2")
        ),
        TreeWorkflow(
            "middle2",
            TreeWorkflow("leaf3")
        )
    )

    root.testFromStart("initial input") { host ->
      host.withNextRendering {
        assertEquals("root:initial input", it.data)
        assertEquals("middle1:initial input[0]", it["middle1"].data)
        assertEquals("middle2:initial input[1]", it["middle2"].data)
        assertEquals("leaf1:initial input[0][0]", it["middle1", "leaf1"].data)
        assertEquals("leaf2:initial input[0][1]", it["middle1", "leaf2"].data)
        assertEquals("leaf3:initial input[1][0]", it["middle2", "leaf3"].data)
      }
    }
  }

  @Test fun `compose fails when duplicate child key`() {
    val root = TreeWorkflow(
        "root",
        TreeWorkflow("leaf"),
        TreeWorkflow("leaf")
    )

    // Setup initial state and change the state the workflow in the tree.
    root.testFromStart("initial input") {
      assertFailsWith<IllegalArgumentException> {
        it.awaitNextRendering()
      }
    }
  }
}
