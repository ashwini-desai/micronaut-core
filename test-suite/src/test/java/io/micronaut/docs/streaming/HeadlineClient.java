/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.docs.streaming;

// tag::imports[]
import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.annotation.Client;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import static io.micronaut.http.MediaType.APPLICATION_JSON_STREAM;
// end::imports[]

// tag::class[]
@Client("/streaming")
public interface HeadlineClient {

    @Get(value = "/headlines", processes = APPLICATION_JSON_STREAM) // <1>
    Publisher<Headline> streamHeadlines(); // <2>
// end::class[]

    @Get(value = "/headlines", processes = APPLICATION_JSON_STREAM) // <1>
    Publisher<Headline> streamFlux();
}
