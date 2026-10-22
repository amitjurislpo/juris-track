package com.jurislpo.juristrack.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.function.ThrowingConsumer;

/**
 * The golden cases generated from the old Node code ({@code src/test/resources/golden/time-engine.json}; the frontend repo has an
 * identical copy in {@code tests/fixtures/time-engine.json}). Each case is {@code { input, output }}; instants are ISO-8601 UTC strings.
 */
final class GoldenFixture {

    private static final JsonNode ROOT = load();

    private GoldenFixture() {
    }

    private static JsonNode load() {
        try (InputStream stream = GoldenFixture.class.getResourceAsStream("/golden/time-engine.json")) {
            if (stream == null) {
                throw new IllegalStateException("golden/time-engine.json is missing from the test resources");
            }
            return new ObjectMapper().readTree(stream);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonNode root() {
        return ROOT;
    }

    /** The cases of one old function, for example {@code cases("calc", "intervalSeconds")}; never empty. */
    static List<JsonNode> cases(String module, String function) {
        JsonNode cases = ROOT.path(module).path(function);
        assertTrue(cases.isArray() && !cases.isEmpty(), "no golden cases for " + module + "." + function);
        List<JsonNode> list = new ArrayList<>();
        cases.forEach(list::add);
        return list;
    }

    /** One dynamic test per golden case, named after the function, the case number and its input. */
    static Stream<DynamicTest> eachCase(String module, String function, ThrowingConsumer<JsonNode> check) {
        List<JsonNode> cases = cases(module, function);
        return IntStream.range(0, cases.size()).mapToObj(index -> DynamicTest.dynamicTest(
                function + " #" + (index + 1) + " " + cases.get(index).path("input"),
                () -> check.accept(cases.get(index))));
    }

    /** A text value, or {@code null} when the JSON value is null, missing or not text. */
    static String text(JsonNode node) {
        return node == null || !node.isTextual() ? null : node.asText();
    }

    /** An instant, or {@code null} when the JSON value is null or missing. */
    static Instant instant(JsonNode node) {
        String text = text(node);
        return text == null ? null : Instant.parse(text);
    }

    static LocalDate date(JsonNode node) {
        return LocalDate.parse(node.asText());
    }

    static TimeInterval interval(JsonNode node) {
        return new TimeInterval(instant(node.path("startedAt")), instant(node.path("endedAt")));
    }

    static List<TimeInterval> intervals(JsonNode node) {
        return list(node, GoldenFixture::interval);
    }

    static <T> List<T> list(JsonNode arrayNode, Function<JsonNode, T> mapper) {
        List<T> list = new ArrayList<>();
        arrayNode.forEach(element -> list.add(mapper.apply(element)));
        return list;
    }
}
