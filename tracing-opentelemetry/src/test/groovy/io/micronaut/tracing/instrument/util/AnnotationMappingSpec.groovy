package io.micronaut.tracing.instrument.util

import io.micronaut.context.ApplicationContext
import io.micronaut.core.annotation.Introspected
import io.micronaut.http.HttpRequest
import io.micronaut.http.annotation.*
import io.micronaut.http.client.annotation.Client
import io.micronaut.reactor.http.client.ReactorHttpClient
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.tracing.annotation.ContinueSpan
import io.micronaut.tracing.annotation.NewSpan
import io.micronaut.tracing.annotation.SpanTag
import io.opentelemetry.extension.annotations.SpanAttribute
import io.opentelemetry.extension.annotations.WithSpan
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import jakarta.inject.Inject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.function.Tuple2
import reactor.util.function.Tuples
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import static io.micronaut.scheduling.TaskExecutors.IO

class AnnotationMappingSpec extends Specification {

    private static final Logger LOG = LoggerFactory.getLogger(AnnotationMappingSpec)

    @Shared
    @AutoCleanup
    EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [
            'micronaut.application.name': 'test-app'
    ])

    @Shared
    @AutoCleanup
    ReactorHttpClient reactorHttpClient = ReactorHttpClient.create(embeddedServer.URL)

    void 'test map WithSpan annotation'() {
        def count = 1
        // 2x Method call 1x NewSpan, 1x WithSpan  = 2
        def spanNumbers = 2
        def testExporter = embeddedServer.getApplicationContext().getBean(InMemorySpanExporter.class)

        expect:
        List<Tuple2> result = Flux.range(1, count)
                .flatMap {
                    String tracingId = UUID.randomUUID()
                    HttpRequest<Object> request = HttpRequest
                            .POST("/annotations/enter", new SomeBody())
                            .header("X-TrackingId", tracingId)
                    return Mono.from(reactorHttpClient.retrieve(request)).map(response -> {
                        Tuples.of(tracingId, response)
                    })
                }
                .collectList()
                .block()
        for (Tuple2 t : result)
            assert t.getT1() == t.getT2()

        testExporter.getFinishedSpanItems().size() == count * spanNumbers

        !testExporter.getFinishedSpanItems().attributes.any(x->x.asMap().keySet().any(y-> y.key == "tracing-annotation-span-attribute"))
        !testExporter.getFinishedSpanItems().attributes.any(x->x.asMap().keySet().any(y-> y.key == "tracing-annotation-span-tag-no-withspan"))
        testExporter.getFinishedSpanItems().attributes.any(x->x.asMap().keySet().any(y-> y.key == "tracing-annotation-span-tag-with-withspan"))
        testExporter.getFinishedSpanItems().attributes.any(x->x.asMap().keySet().any(y-> y.key == "tracing-annotation-span-tag-continue-span"))
        // test if newspan has appended name
        testExporter.getFinishedSpanItems().name.any(x->x.contains("#test-withspan-mapping"))
        testExporter.getFinishedSpanItems().name.any(x->x.contains("#enter"))

        cleanup:
        testExporter.reset()

    }

    @Introspected
    static class SomeBody {
    }

    @Controller("/annotations")
    static class TestController {

        @Inject
        @Client("/")
        private ReactorHttpClient reactorHttpClient

        @ExecuteOn(IO)
        @Post("/enter")
        @NewSpan("enter")
        Mono<String> enter(@Header("X-TrackingId") String tracingId, @Body SomeBody body) {
            LOG.info("enter")
            return test(tracingId)
        }

        @ExecuteOn(IO)
        @Get("/test")
        Mono<String> test(@SpanAttribute("tracing-annotation-span-attribute") @Header("X-TrackingId") String tracingId) {
            LOG.info("test")
            return Mono.from(
                    reactorHttpClient.retrieve(HttpRequest
                            .GET("/annotations/test2")
                            .header("X-TrackingId", tracingId), String)
            )
        }

        @ExecuteOn(IO)
        @Get("/test2")
        Mono<String> test2(@SpanTag("tracing-annotation-span-tag-no-withspan") @Header("X-TrackingId") String tracingId) {
            LOG.info("test2")
            return methodWithSpan(tracingId)
        }

        @WithSpan("test-withspan-mapping")
        Mono<String> methodWithSpan(@SpanTag("tracing-annotation-span-tag-with-withspan") String tracingId){
            return Mono.from(methodContinueSpan(tracingId))
        }

        @ContinueSpan
        Mono<String> methodContinueSpan(@SpanTag("tracing-annotation-span-tag-continue-span") String tracingId) {
            return Mono.just(tracingId)
        }

    }

}
