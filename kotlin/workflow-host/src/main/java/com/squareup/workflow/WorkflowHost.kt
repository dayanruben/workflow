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

import com.squareup.workflow.WorkflowHost.Factory
import com.squareup.workflow.WorkflowHost.Update
import com.squareup.workflow.internal.WorkflowId
import com.squareup.workflow.internal.WorkflowNode
import com.squareup.workflow.internal.id
import kotlinx.coroutines.experimental.cancel
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.produce
import kotlinx.coroutines.experimental.isActive
import kotlinx.coroutines.experimental.selects.select
import org.jetbrains.annotations.TestOnly
import kotlin.coroutines.experimental.CoroutineContext
import kotlin.coroutines.experimental.EmptyCoroutineContext

/**
 * Provides a stream of [updates][Update] from a tree of [Workflow]s.
 *
 * Create these by injecting a [Factory] and calling [run][Factory.run].
 */
interface WorkflowHost<out OutputT : Any, out RenderingT : Any> {

  /**
   * Output from a [WorkflowHost]. Emitted from [WorkflowHost.updates] after every
   * [compose][Workflow.compose] pass.
   */
  data class Update<out OutputT : Any, out RenderingT : Any>(
    val rendering: RenderingT,
    val snapshot: Snapshot,
    val output: OutputT? = null
  )

  /**
   * Stream of [updates][Update] from the root workflow.
   *
   * This is *not* a broadcast channel, so it should only be read by a single consumer.
   */
  val updates: ReceiveChannel<Update<OutputT, RenderingT>>

  /**
   * Inject one of these to start root [Workflow]s.
   */
  class Factory(private val baseContext: CoroutineContext) {

    /**
     * Creates a [WorkflowHost] to run [workflow].
     *
     * The workflow's initial state is determined by passing [input] to [Workflow.initialState].
     *
     * @param workflow The workflow to start.
     * @param input Passed to [Workflow.initialState] to determine the root workflow's initial state.
     * If [InputT] is `Unit`, you can just omit this argument.
     * @param snapshot If not null, used to restore the workflow.
     * @param context The [CoroutineContext] used to run the workflow tree. Added to the [Factory]'s
     * context.
     */
    fun <InputT : Any, StateT : Any, OutputT : Any, RenderingT : Any> run(
      workflow: Workflow<InputT, StateT, OutputT, RenderingT>,
      input: InputT,
      snapshot: Snapshot? = null,
      context: CoroutineContext = EmptyCoroutineContext
    ): WorkflowHost<OutputT, RenderingT> = run(workflow.id(), workflow, input, snapshot, context)

    fun <S : Any, O : Any, R : Any> run(
      workflow: Workflow<Unit, S, O, R>,
      snapshot: Snapshot? = null,
      context: CoroutineContext = EmptyCoroutineContext
    ): WorkflowHost<O, R> = run(workflow.id(), workflow, Unit, snapshot, context)

    /**
     * Creates a [WorkflowHost] that runs [workflow] starting from [initialState].
     *
     * **Don't call this directly.**
     *
     * Instead, your module should have a test dependency on `pure-v2-testing` and you should call the
     * testing extension method defined there on your workflow itself.
     */
    @TestOnly
    fun <InputT : Any, StateT : Any, OutputT : Any, RenderingT : Any> runTestFromState(
      workflow: Workflow<InputT, StateT, OutputT, RenderingT>,
      input: InputT,
      initialState: StateT
    ): WorkflowHost<OutputT, RenderingT> {
      val workflowId = workflow.id()
      return object : WorkflowHost<OutputT, RenderingT> {
        val node = WorkflowNode(workflowId, workflow, input, null, baseContext, initialState)
        override val updates: ReceiveChannel<Update<OutputT, RenderingT>> = node.start(workflow, input)
      }
    }

    internal fun <InputT : Any, StateT : Any, OutputT : Any, RenderingT : Any> run(
      id: WorkflowId<InputT, StateT, OutputT, RenderingT>,
      workflow: Workflow<InputT, StateT, OutputT, RenderingT>,
      input: InputT,
      snapshot: Snapshot?,
      context: CoroutineContext
    ): WorkflowHost<OutputT, RenderingT> = object : WorkflowHost<OutputT, RenderingT> {
      val node = WorkflowNode(id, workflow, input, snapshot, baseContext + context)
      override val updates: ReceiveChannel<Update<OutputT, RenderingT>> = node.start(workflow, input)
    }
  }
}

/**
 * Starts the coroutine that runs the coroutine loop.
 */
internal fun <I : Any, S : Any, O : Any, R : Any> WorkflowNode<I, S, O, R>.start(
  workflow: Workflow<I, S, O, R>,
  input: I
): ReceiveChannel<Update<O, R>> = produce(capacity = 0) {
  try {
    var output: O? = null
    while (isActive) {
      val rendering = compose(workflow, input)
      val snapshot = snapshot(workflow)
      send(Update(rendering, snapshot, output))
      // Tick _might_ return an output, but if it returns null, it means the state or a child
      // probably changed, so we should re-compose/snapshot and emit again.
      output = select {
        tick(this) { it }
      }
    }
  } catch (e: Throwable) {
    // For some reason the exception gets masked if we don't explicitly pass it to cancel the
    // producer coroutine ourselves here.
    coroutineContext.cancel(e)
    throw e
  } finally {
    // There's a potential race condition if the producer coroutine is cancelled before it has a chance
    // to enter the try block, since we can't use CoroutineStart.ATOMIC. However, until we actually
    // see this cause problems, I'm not too worried about it.
    cancel()
  }
}
