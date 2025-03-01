/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.docs.server.suspend

import io.micronaut.http.*
import io.micronaut.http.annotation.*
import io.micronaut.http.context.ServerRequestContext
import io.micronaut.scheduling.TaskExecutors
import kotlinx.coroutines.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import jakarta.inject.Named

@Controller("/suspend")
class SuspendController(
    @Named(TaskExecutors.IO) private val executor: ExecutorService,
    private val suspendService: SuspendService,
    private val suspendRequestScopedService: SuspendRequestScopedService
) {

    private val coroutineDispatcher: CoroutineDispatcher

    init {
        coroutineDispatcher = executor.asCoroutineDispatcher()
    }

    // tag::suspend[]
    @Get("/simple", produces = [MediaType.TEXT_PLAIN])
    suspend fun simple(): String { // <1>
        return "Hello"
    }
    // end::suspend[]

    // tag::suspendDelayed[]
    @Get("/delayed", produces = [MediaType.TEXT_PLAIN])
    suspend fun delayed(): String { // <1>
        delay(1) // <2>
        return "Delayed"
    }
    // end::suspendDelayed[]

    // tag::suspendStatus[]
    @Status(HttpStatus.CREATED) // <1>
    @Get("/status")
    suspend fun status() {
    }
    // end::suspendStatus[]

    // tag::suspendStatusDelayed[]
    @Status(HttpStatus.CREATED)
    @Get("/statusDelayed")
    suspend fun statusDelayed() {
        delay(1)
    }
    // end::suspendStatusDelayed[]

    val count = AtomicInteger(0)

    @Get("/count")
    suspend fun count(): Int { // <1>
        return count.incrementAndGet()
    }

    @Get("/greet")
    suspend fun suspendingGreet(name: String, request: HttpRequest<String>): HttpResponse<out Any> {
        val json = "{\"message\":\"hello\"}"
        return HttpResponse.ok(json).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
    }

    @Get("/illegal")
    suspend fun illegal() {
        throw IllegalArgumentException()
    }

    @Get("/illegalWithContext")
    suspend fun illegalWithContext(): String = withContext(coroutineDispatcher) {
        throw IllegalArgumentException()
    }

    @Status(HttpStatus.BAD_REQUEST)
    @Error(exception = IllegalArgumentException::class)
    @Produces(MediaType.TEXT_PLAIN)
    suspend fun onIllegalArgument(e: IllegalArgumentException): String {
        return "illegal.argument"
    }

    @Get("/callSuspendServiceWithRetries")
    suspend fun callSuspendServiceWithRetries(): String {
        return suspendService.delayedCalculation1()
    }

    @Get("/callSuspendServiceWithRetriesBlocked")
    fun callSuspendServiceWithRetriesBlocked(): String {
        // Bypass ContinuationArgumentBinder
        return runBlocking {
            suspendService.delayedCalculation2()
        }
    }

    @Get("/callSuspendServiceWithRetriesWithoutDelay")
    suspend fun callSuspendServiceWithRetriesWithoutDelay(): String {
        return suspendService.calculation3()
    }

    @Get("/keepRequestScopeInsideCoroutine")
    suspend fun keepRequestScopeInsideCoroutine() = coroutineScope {
        val before = "${suspendRequestScopedService.requestId},${Thread.currentThread().id}"
        val after = async { "${suspendRequestScopedService.requestId},${Thread.currentThread().id}" }.await()
        "$before,$after"
    }

    @Get("/keepRequestScopeInsideCoroutineWithRetry")
    suspend fun keepRequestScopeInsideCoroutineWithRetry() = coroutineScope {
        val before = "${suspendRequestScopedService.requestId},${Thread.currentThread().id}"
        val after = async { suspendService.requestScopedCalculation() }.await()
        "$before,$after"
    }

    @Get("/keepRequestScopeAfterSuspend")
    suspend fun keepRequestScopeAfterSuspend(): String {
        val before = "${suspendRequestScopedService.requestId},${Thread.currentThread().id}"
        delay(10) // suspend
        val after = "${suspendRequestScopedService.requestId},${Thread.currentThread().id}"
        return "$before,$after"
    }

    @Get("/requestContext")
    suspend fun requestContext(): String {
        return suspendService.requestContext()
    }

    @Get("/requestContext2")
    suspend fun requestContext2(): String = supervisorScope {
        require(ServerRequestContext.currentRequest<Any>().isPresent) {
            "Initial request is not set"
        }
        val result = withContext(coroutineContext) {
            require(ServerRequestContext.currentRequest<Any>().isPresent) {
                "Request is not available in `withContext`"
            }
            "test"
        }
        require(ServerRequestContext.currentRequest<Any>().isPresent) {
            "Request is lost after `withContext`"
        }
        result
    }
}
