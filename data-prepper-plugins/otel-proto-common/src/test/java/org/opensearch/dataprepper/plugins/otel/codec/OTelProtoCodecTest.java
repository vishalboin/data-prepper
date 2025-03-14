/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.dataprepper.plugins.otel.codec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.util.JsonFormat;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.ArrayValue;
import io.opentelemetry.proto.common.v1.InstrumentationLibrary;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.common.v1.KeyValueList;
import io.opentelemetry.proto.metrics.v1.Exemplar;
import io.opentelemetry.proto.metrics.v1.ExponentialHistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Status;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.opensearch.dataprepper.model.log.OpenTelemetryLog;
import org.opensearch.dataprepper.model.metric.Bucket;
import org.opensearch.dataprepper.model.trace.DefaultLink;
import org.opensearch.dataprepper.model.trace.DefaultSpanEvent;
import org.opensearch.dataprepper.model.trace.DefaultTraceGroupFields;
import org.opensearch.dataprepper.model.trace.JacksonSpan;
import org.opensearch.dataprepper.model.trace.Link;
import org.opensearch.dataprepper.model.trace.Span;
import org.opensearch.dataprepper.model.trace.SpanEvent;
import org.opensearch.dataprepper.model.trace.TraceGroupFields;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.entry;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class OTelProtoCodecTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Random RANDOM = new Random();
    private static final String TEST_REQUEST_TRACE_JSON_FILE = "test-request.json";
    private static final String TEST_REQUEST_INSTRUMENTATION_LIBRARY_TRACE_JSON_FILE = "test-request-instrumentation-library.json";
    private static final String TEST_REQUEST_BOTH_SPAN_TYPES_JSON_FILE = "test-request-both-span-types.json";
    private static final String TEST_REQUEST_NO_SPANS_JSON_FILE = "test-request-no-spans.json";
    private static final String TEST_SPAN_EVENT_JSON_FILE = "test-span-event.json";

    private static final String TEST_REQUEST_LOGS_JSON_FILE = "test-request-log.json";

    private static final String TEST_REQUEST_LOGS_IS_JSON_FILE = "test-request-log-is.json";


    private static final Long TIME = TimeUnit.MILLISECONDS.toNanos(ZonedDateTime.of(
            LocalDateTime.of(2020, 5, 24, 14, 1, 0),
            ZoneOffset.UTC).toInstant().toEpochMilli());

    private static final Double MAX_ERROR = 0.00001;

    private final OTelProtoCodec.OTelProtoDecoder decoderUnderTest = new OTelProtoCodec.OTelProtoDecoder();
    private final OTelProtoCodec.OTelProtoEncoder encoderUnderTest = new OTelProtoCodec.OTelProtoEncoder();
    private static byte[] getRandomBytes(int len) {
        byte[] bytes = new byte[len];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private Map<String, Object> returnMap(final String jsonStr) throws JsonProcessingException {
        return (Map<String, Object>) OBJECT_MAPPER.readValue(jsonStr, Map.class);
    }

    private List<Object> returnList(final String jsonStr) throws JsonProcessingException {
        return (List<Object>) OBJECT_MAPPER.readValue(jsonStr, List.class);
    }

    private ExportTraceServiceRequest buildExportTraceServiceRequestFromJsonFile(String requestJsonFileName) throws IOException {
        final ExportTraceServiceRequest.Builder builder = ExportTraceServiceRequest.newBuilder();
        JsonFormat.parser().merge(getFileAsJsonString(requestJsonFileName), builder);
        return builder.build();
    }

    private ExportLogsServiceRequest buildExportLogsServiceRequestFromJsonFile(String requestJsonFileName) throws IOException {
        final ExportLogsServiceRequest.Builder builder = ExportLogsServiceRequest.newBuilder();
        JsonFormat.parser().merge(getFileAsJsonString(requestJsonFileName), builder);
        return builder.build();
    }

    private String getFileAsJsonString(String requestJsonFileName) throws IOException {
        final StringBuilder jsonBuilder = new StringBuilder();
        try (final InputStream inputStream = Objects.requireNonNull(
                OTelProtoCodecTest.class.getClassLoader().getResourceAsStream(requestJsonFileName))) {
            final BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
            bufferedReader.lines().forEach(jsonBuilder::append);
        }
        return jsonBuilder.toString();
    }


    @Nested
    class OTelProtoDecoderTest {
        @Test
        public void testParseExportTraceServiceRequest() throws IOException {
            final ExportTraceServiceRequest exportTraceServiceRequest = buildExportTraceServiceRequestFromJsonFile(TEST_REQUEST_TRACE_JSON_FILE);
            final List<Span> spans = decoderUnderTest.parseExportTraceServiceRequest(exportTraceServiceRequest);
            validateSpans(spans);
        }

        @Test
        public void testParseExportTraceServiceRequest_InstrumentationLibrarySpans() throws IOException {
            final ExportTraceServiceRequest exportTraceServiceRequest = buildExportTraceServiceRequestFromJsonFile(TEST_REQUEST_INSTRUMENTATION_LIBRARY_TRACE_JSON_FILE);
            final List<Span> spans = decoderUnderTest.parseExportTraceServiceRequest(exportTraceServiceRequest);
            validateSpans(spans);
        }

        @Test
        public void testParseExportTraceServiceRequest_ScopeSpansTakesPrecedenceOverInstrumentationLibrarySpans() throws IOException {
            final ExportTraceServiceRequest exportTraceServiceRequest = buildExportTraceServiceRequestFromJsonFile(TEST_REQUEST_BOTH_SPAN_TYPES_JSON_FILE);
            final List<Span> spans = decoderUnderTest.parseExportTraceServiceRequest(exportTraceServiceRequest);
            validateSpans(spans);
        }

        @Test
        public void testParseExportTraceServiceRequest_NoSpans() throws IOException {
            final ExportTraceServiceRequest exportTraceServiceRequest = buildExportTraceServiceRequestFromJsonFile(TEST_REQUEST_NO_SPANS_JSON_FILE);
            final List<Span> spans = decoderUnderTest.parseExportTraceServiceRequest(exportTraceServiceRequest);
            assertThat(spans.size(), is(equalTo(0)));
        }

        private void validateSpans(final List<Span> spans) {
            assertThat(spans.size(), is(equalTo(3)));

            for (final Span span : spans) {
                if (span.getParentSpanId().isEmpty()) {
                    assertThat(span.getTraceGroup(), notNullValue());
                    assertThat(span.getTraceGroupFields().getEndTime(), notNullValue());
                    assertThat(span.getTraceGroupFields().getDurationInNanos(), notNullValue());
                    assertThat(span.getTraceGroupFields().getStatusCode(), notNullValue());
                } else {
                    assertThat(span.getTraceGroup(), nullValue());
                    assertThat(span.getTraceGroupFields().getEndTime(), nullValue());
                    assertThat(span.getTraceGroupFields().getDurationInNanos(), nullValue());
                    assertThat(span.getTraceGroupFields().getStatusCode(), nullValue());
                }
                Map<String, Object> attributes = span.getAttributes();
                assertThat(attributes.containsKey(OTelProtoCodec.RESOURCE_ATTRIBUTES_REPLACE_DOT_WITH_AT.apply("service.name")), is(true));
                assertThat(attributes.containsKey(OTelProtoCodec.INSTRUMENTATION_SCOPE_NAME), is(true));
                assertThat(attributes.containsKey(OTelProtoCodec.STATUS_CODE), is(true));
            }
        }
        @Test
        public void testGetSpanEvent() {
            final String testName = "test name";
            final long testTimeNanos = System.nanoTime();
            final String testTime = OTelProtoCodec.convertUnixNanosToISO8601(testTimeNanos);
            final String testKey = "test key";
            final String testValue = "test value";
            io.opentelemetry.proto.trace.v1.Span.Event testOTelProtoSpanEvent = io.opentelemetry.proto.trace.v1.Span.Event.newBuilder()
                    .setName(testName)
                    .setTimeUnixNano(testTimeNanos)
                    .setDroppedAttributesCount(0)
                    .addAttributes(KeyValue.newBuilder().setKey(testKey).setValue(AnyValue.newBuilder()
                            .setStringValue(testValue).build()).build())
                    .build();
            final SpanEvent result = decoderUnderTest.getSpanEvent(testOTelProtoSpanEvent);
            assertThat(result.getAttributes().size(), equalTo(1));
            assertThat(result.getDroppedAttributesCount(), equalTo(0));
            assertThat(result.getName(), equalTo(testName));
            assertThat(result.getTime(), equalTo(testTime));
        }

        @Test
        public void testGetSpanLink() {
            final byte[] testSpanIdBytes = getRandomBytes(16);
            final byte[] testTraceIdBytes = getRandomBytes(16);
            final String testSpanId = Hex.encodeHexString(testSpanIdBytes);
            final String testTraceId = Hex.encodeHexString(testTraceIdBytes);
            final String testTraceState = "test state";
            final String testKey = "test key";
            final String testValue = "test value";
            io.opentelemetry.proto.trace.v1.Span.Link testOTelProtoSpanLink = io.opentelemetry.proto.trace.v1.Span.Link.newBuilder()
                    .setSpanId(ByteString.copyFrom(testSpanIdBytes))
                    .setTraceId(ByteString.copyFrom(testTraceIdBytes))
                    .setTraceState(testTraceState)
                    .setDroppedAttributesCount(0)
                    .addAttributes(KeyValue.newBuilder().setKey(testKey).setValue(AnyValue.newBuilder()
                            .setStringValue(testValue).build()).build())
                    .build();
            final Link result = decoderUnderTest.getLink(testOTelProtoSpanLink);
            assertThat(result.getAttributes().size(), equalTo(1));
            assertThat(result.getDroppedAttributesCount(), equalTo(0));
            assertThat(result.getSpanId(), equalTo(testSpanId));
            assertThat(result.getTraceId(), equalTo(testTraceId));
            assertThat(result.getTraceState(), equalTo(testTraceState));
        }

        /**
         * Below object has a KeyValue with a key mapped to KeyValueList and is part of the span attributes
         *
         * @throws JsonProcessingException
         */
        @Test
        public void testKeyValueListAsSpanAttributes() throws JsonProcessingException {

            final KeyValue childAttr1 = KeyValue.newBuilder().setKey("statement").setValue(AnyValue.newBuilder()
                    .setIntValue(1_000).build()).build();
            final KeyValue childAttr2 = KeyValue.newBuilder().setKey("statement.params").setValue(AnyValue.newBuilder()
                    .setStringValue("us-east-1").build()).build();
            final KeyValue spanAttribute1 = KeyValue.newBuilder().setKey("db.details").setValue(AnyValue.newBuilder()
                    .setKvlistValue(KeyValueList.newBuilder().addAllValues(Arrays.asList(childAttr1, childAttr2)).build()).build()).build();
            final KeyValue spanAttribute2 = KeyValue.newBuilder().setKey("http.status").setValue(AnyValue.newBuilder()
                    .setStringValue("4xx").build()).build();

            final Map<String, Object> actual = decoderUnderTest.getSpanAttributes(io.opentelemetry.proto.trace.v1.Span.newBuilder()
                    .addAllAttributes(Arrays.asList(spanAttribute1, spanAttribute2)).build());
            assertThat(actual.get(OTelProtoCodec.SPAN_ATTRIBUTES_REPLACE_DOT_WITH_AT.apply(spanAttribute2.getKey())),
                    equalTo(spanAttribute2.getValue().getStringValue()));
            assertThat(actual.containsKey(OTelProtoCodec.SPAN_ATTRIBUTES_REPLACE_DOT_WITH_AT.apply(spanAttribute1.getKey())), is(true));
            final Map<String, Object> actualValue = returnMap((String) actual
                    .get(OTelProtoCodec.SPAN_ATTRIBUTES_REPLACE_DOT_WITH_AT.apply(spanAttribute1.getKey())));
            assertThat(((Number) actualValue.get(OTelProtoCodec.REPLACE_DOT_WITH_AT.apply(childAttr1.getKey()))).longValue(),
                    equalTo(childAttr1.getValue().getIntValue()));
            assertThat(actualValue.get(OTelProtoCodec.REPLACE_DOT_WITH_AT.apply(childAttr2.getKey())), equalTo(childAttr2.getValue().getStringValue()));
        }

        /**
         * Below object has a KeyValue with a key mapped to KeyValueList and is part of the resource attributes
         *
         * @throws JsonProcessingException
         */
        @Test
        public void testKeyValueListAsResourceAttributes() throws JsonProcessingException {
            final KeyValue childAttr1 = KeyValue.newBuilder().setKey("ec2.instances").setValue(AnyValue.newBuilder()
                    .setIntValue(20).build()).build();
            final KeyValue childAttr2 = KeyValue.newBuilder().setKey("ec2.instance.az").setValue(AnyValue.newBuilder()
                    .setStringValue("us-east-1").build()).build();
            final KeyValue spanAttribute1 = KeyValue.newBuilder().setKey("aws.details").setValue(AnyValue.newBuilder()
                    .setKvlistValue(KeyValueList.newBuilder().addAllValues(Arrays.asList(childAttr1, childAttr2)).build()).build()).build();
            final KeyValue spanAttribute2 = KeyValue.newBuilder().setKey("service.name").setValue(AnyValue.newBuilder()
                    .setStringValue("EaglesService").build()).build();

            final Map<String, Object> actual = decoderUnderTest.getResourceAttributes(Resource.newBuilder()
                    .addAllAttributes(Arrays.asList(spanAttribute1, spanAttribute2)).build());
            assertThat(actual.get(OTelProtoCodec.RESOURCE_ATTRIBUTES_REPLACE_DOT_WITH_AT.apply(spanAttribute2.getKey())),
                    equalTo(spanAttribute2.getValue().getStringValue()));
            assertThat(actual.containsKey(OTelProtoCodec.RESOURCE_ATTRIBUTES_REPLACE_DOT_WITH_AT.apply(spanAttribute1.getKey())), is(true));
            final Map<String, Object> actualValue = returnMap((String) actual
                    .get(OTelProtoCodec.RESOURCE_ATTRIBUTES_REPLACE_DOT_WITH_AT.apply(spanAttribute1.getKey())));
            assertThat(((Number) actualValue.get(OTelProtoCodec.REPLACE_DOT_WITH_AT.apply(childAttr1.getKey()))).longValue(), equalTo(childAttr1.getValue().getIntValue()));
            assertThat(actualValue.get(OTelProtoCodec.REPLACE_DOT_WITH_AT.apply(childAttr2.getKey())), equalTo(childAttr2.getValue().getStringValue()));

        }


        /**
         * Below object has a KeyValue with a key mapped to KeyValueList and is part of the span attributes
         *
         * @throws JsonProcessingException
         */
        @Test
        public void testArrayOfValueAsResourceAttributes() throws JsonProcessingException {
            final KeyValue childAttr1 = KeyValue.newBuilder().setKey("ec2.instances").setValue(AnyValue.newBuilder()
                    .setIntValue(20).build()).build();
            final KeyValue childAttr2 = KeyValue.newBuilder().setKey("ec2.instance.az").setValue(AnyValue.newBuilder()
                    .setStringValue("us-east-1").build()).build();
            final AnyValue anyValue1 = AnyValue.newBuilder().setStringValue(UUID.randomUUID().toString()).build();
            final AnyValue anyValue2 = AnyValue.newBuilder().setDoubleValue(2000.123).build();
            final AnyValue anyValue3 = AnyValue.newBuilder().setKvlistValue(KeyValueList.newBuilder().addAllValues(Arrays.asList(childAttr1, childAttr2))).build();
            final ArrayValue arrayValue = ArrayValue.newBuilder().addAllValues(Arrays.asList(anyValue1, anyValue2, anyValue3)).build();
            final KeyValue spanAttribute1 = KeyValue.newBuilder().setKey("aws.details").setValue(AnyValue.newBuilder()
                    .setArrayValue(arrayValue)).build();

            final Map<String, Object> actual = decoderUnderTest.getResourceAttributes(Resource.newBuilder()
                    .addAllAttributes(Collections.singletonList(spanAttribute1)).build());
            assertThat(actual.containsKey(OTelProtoCodec.RESOURCE_ATTRIBUTES_REPLACE_DOT_WITH_AT.apply(spanAttribute1.getKey())), is(true));
            final List<Object> actualValue = returnList((String) actual
                    .get(OTelProtoCodec.RESOURCE_ATTRIBUTES_REPLACE_DOT_WITH_AT.apply(spanAttribute1.getKey())));
            assertThat(actualValue.get(0), equalTo(anyValue1.getStringValue()));
            assertThat(((Double) actualValue.get(1)), equalTo(anyValue2.getDoubleValue()));
            final Map<String, Object> map = returnMap((String) actualValue.get(2));
            assertThat(((Number) map.get(OTelProtoCodec.REPLACE_DOT_WITH_AT.apply(childAttr1.getKey()))).longValue(), equalTo(childAttr1.getValue().getIntValue()));
            assertThat(map.get(OTelProtoCodec.REPLACE_DOT_WITH_AT.apply(childAttr2.getKey())), equalTo(childAttr2.getValue().getStringValue()));
            assertThat(((Number) map.get(OTelProtoCodec.REPLACE_DOT_WITH_AT.apply(childAttr1.getKey()))).longValue(), equalTo(childAttr1.getValue().getIntValue()));

        }


        @Test
        public void testInstrumentationLibraryAttributes() {
            final InstrumentationLibrary il1 = InstrumentationLibrary.newBuilder().setName("Jaeger").setVersion("0.6.0").build();
            final InstrumentationLibrary il2 = InstrumentationLibrary.newBuilder().setName("Jaeger").build();
            final InstrumentationLibrary il3 = InstrumentationLibrary.newBuilder().setVersion("0.6.0").build();
            final InstrumentationLibrary il4 = InstrumentationLibrary.newBuilder().build();

            assertThat(decoderUnderTest.getInstrumentationLibraryAttributes(il1).size(), equalTo(2));
            assertThat(decoderUnderTest.getInstrumentationLibraryAttributes(il1).get(OTelProtoCodec.INSTRUMENTATION_SCOPE_NAME), equalTo(il1.getName()));
            assertThat(decoderUnderTest.getInstrumentationLibraryAttributes(il1).get(OTelProtoCodec.INSTRUMENTATION_SCOPE_VERSION), equalTo(il1.getVersion()));

            assertThat(decoderUnderTest.getInstrumentationLibraryAttributes(il2).size(), equalTo(1));
            assertThat(decoderUnderTest.getInstrumentationLibraryAttributes(il2).get(OTelProtoCodec.INSTRUMENTATION_SCOPE_NAME), equalTo(il2.getName()));

            assertThat(decoderUnderTest.getInstrumentationLibraryAttributes(il3).size(), equalTo(1));
            assertThat(decoderUnderTest.getInstrumentationLibraryAttributes(il3).get(OTelProtoCodec.INSTRUMENTATION_SCOPE_VERSION), equalTo(il3.getVersion()));

            assertThat(decoderUnderTest.getInstrumentationLibraryAttributes(il4).isEmpty(), is(true));
        }

        @Test
        public void testStatusAttributes() {
            final Status st1 = Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_ERROR).setMessage("Some message").build();
            final Status st2 = Status.newBuilder().setMessage("error message").build();
            final Status st3 = Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_UNSET).build();
            final Status st4 = Status.newBuilder().build();

            assertThat(decoderUnderTest.getSpanStatusAttributes(st1).size(), equalTo(2));
            assertThat(Status.StatusCode.forNumber((Integer) decoderUnderTest.getSpanStatusAttributes(st1).get(OTelProtoCodec.STATUS_CODE)), equalTo(st1.getCode()));
            assertThat(decoderUnderTest.getSpanStatusAttributes(st1).get(OTelProtoCodec.STATUS_MESSAGE), equalTo(st1.getMessage()));

            assertThat(decoderUnderTest.getSpanStatusAttributes(st2).size(), equalTo(2));
            assertThat(Status.StatusCode.forNumber((Integer) decoderUnderTest.getSpanStatusAttributes(st2).get(OTelProtoCodec.STATUS_CODE)), equalTo(st2.getCode()));

            assertThat(decoderUnderTest.getSpanStatusAttributes(st3).size(), equalTo(1));
            assertThat(Status.StatusCode.forNumber((Integer) decoderUnderTest.getSpanStatusAttributes(st3).get(OTelProtoCodec.STATUS_CODE)), equalTo(st3.getCode()));

            assertThat(decoderUnderTest.getSpanStatusAttributes(st4).size(), equalTo(1));
            assertThat(Status.StatusCode.forNumber((Integer) decoderUnderTest.getSpanStatusAttributes(st4).get(OTelProtoCodec.STATUS_CODE)), equalTo(st4.getCode()));

        }

        @Test
        public void testISO8601() {
            final long NANO_MULTIPLIER = 1_000 * 1_000 * 1_000;
            final io.opentelemetry.proto.trace.v1.Span startTimeUnixNano = io.opentelemetry.proto.trace.v1.Span.newBuilder()
                    .setStartTimeUnixNano(651242400000000321L).build();
            final io.opentelemetry.proto.trace.v1.Span endTimeUnixNano = io.opentelemetry.proto.trace.v1.Span.newBuilder()
                    .setEndTimeUnixNano(1598013600000000321L).build();
            final io.opentelemetry.proto.trace.v1.Span emptyTimeSpan = io.opentelemetry.proto.trace.v1.Span.newBuilder().build();

            final String startTime = decoderUnderTest.getStartTimeISO8601(startTimeUnixNano);
            assertThat(Instant.parse(startTime).getEpochSecond() * NANO_MULTIPLIER + Instant.parse(startTime).getNano(), equalTo(startTimeUnixNano.getStartTimeUnixNano()));
            final String endTime = decoderUnderTest.getEndTimeISO8601(endTimeUnixNano);
            assertThat(Instant.parse(endTime).getEpochSecond() * NANO_MULTIPLIER + Instant.parse(endTime).getNano(), equalTo(endTimeUnixNano.getEndTimeUnixNano()));
            final String emptyTime = decoderUnderTest.getStartTimeISO8601(endTimeUnixNano);
            assertThat(Instant.parse(emptyTime).getEpochSecond() * NANO_MULTIPLIER + Instant.parse(emptyTime).getNano(), equalTo(emptyTimeSpan.getStartTimeUnixNano()));

        }

        @Test
        public void testTraceGroup() {
            final io.opentelemetry.proto.trace.v1.Span span1 = io.opentelemetry.proto.trace.v1.Span.newBuilder()
                    .setParentSpanId(ByteString.copyFrom("PArentIdExists", StandardCharsets.UTF_8)).build();
            assertThat(decoderUnderTest.getTraceGroup(span1), nullValue());
            final String testTraceGroup = "testTraceGroup";
            final io.opentelemetry.proto.trace.v1.Span span2 = io.opentelemetry.proto.trace.v1.Span.newBuilder()
                    .setName(testTraceGroup).build();
            assertThat(decoderUnderTest.getTraceGroup(span2), equalTo(testTraceGroup));
        }

        @Test
        public void testTraceGroupFields() {
            final io.opentelemetry.proto.trace.v1.Span span1 = io.opentelemetry.proto.trace.v1.Span.newBuilder()
                    .setParentSpanId(ByteString.copyFrom("PArentIdExists", StandardCharsets.UTF_8)).build();
            final TraceGroupFields traceGroupFields1 = decoderUnderTest.getTraceGroupFields(span1);
            assertThat(traceGroupFields1.getEndTime(), nullValue());
            assertThat(traceGroupFields1.getDurationInNanos(), nullValue());
            assertThat(traceGroupFields1.getStatusCode(), nullValue());
            final long testStartTimeUnixNano = 100;
            final long testEndTimeUnixNano = 100;
            final int testStatusCode = Status.StatusCode.STATUS_CODE_OK.getNumber();
            final io.opentelemetry.proto.trace.v1.Span span2 = io.opentelemetry.proto.trace.v1.Span.newBuilder()
                    .setStartTimeUnixNano(testStartTimeUnixNano)
                    .setEndTimeUnixNano(testEndTimeUnixNano)
                    .setStatus(Status.newBuilder().setCodeValue(testStatusCode))
                    .build();
            final TraceGroupFields expectedTraceGroupFields = DefaultTraceGroupFields.builder()
                    .withStatusCode(testStatusCode)
                    .withEndTime(decoderUnderTest.getEndTimeISO8601(span2))
                    .withDurationInNanos(testEndTimeUnixNano - testStartTimeUnixNano)
                    .build();
            assertThat(decoderUnderTest.getTraceGroupFields(span2), equalTo(expectedTraceGroupFields));
        }

        @Test
        public void testParseExportLogsServiceRequest_ScopedLogs() throws IOException {
            final ExportLogsServiceRequest exportLogsServiceRequest = buildExportLogsServiceRequestFromJsonFile(TEST_REQUEST_LOGS_JSON_FILE);
            List<OpenTelemetryLog> logs = decoderUnderTest.parseExportLogsServiceRequest(exportLogsServiceRequest);

            assertThat(logs.size() , is(equalTo(1)));
            validateLog(logs.get(0));
        }

        @Test
        public void testParseExportLogsServiceRequest_InstrumentationLibraryLogs() throws IOException {
            final ExportLogsServiceRequest exportLogsServiceRequest = buildExportLogsServiceRequestFromJsonFile(TEST_REQUEST_LOGS_IS_JSON_FILE);
            List<OpenTelemetryLog> logs = decoderUnderTest.parseExportLogsServiceRequest(exportLogsServiceRequest);

            assertThat(logs.size() , is(equalTo(1)));
            validateLog(logs.get(0));
        }

        private void validateLog(OpenTelemetryLog logRecord) {
            assertThat(logRecord.getServiceName(), is("service"));
            assertThat(logRecord.getTime(), is("2020-05-24T14:00:00Z"));
            assertThat(logRecord.getObservedTime(), is("2020-05-24T14:00:02Z"));
            assertThat(logRecord.getBody(), is("Log value"));
            assertThat(logRecord.getDroppedAttributesCount(), is(3));
            assertThat(logRecord.getSchemaUrl(), is("schemaurl"));
            assertThat(logRecord.getSeverityNumber(), is(5));
            assertThat(logRecord.getTraceId(), is("ba1a1c23b4093b63"));
            assertThat(logRecord.getSpanId(), is("2cc83ac90ebc469c"));
            Map<String, Object> mergedAttributes = logRecord.getAttributes();
            assertThat(mergedAttributes.keySet().size(), is(2));
            assertThat(mergedAttributes.get("log.attributes.statement@params"), is("us-east-1"));
            assertThat(mergedAttributes.get("resource.attributes.service@name"), is("service"));
        }

        @Test
        public void testParseExportLogsServiceRequest_InstrumentationLibrarySpans() throws IOException {
            final ExportTraceServiceRequest exportTraceServiceRequest = buildExportTraceServiceRequestFromJsonFile(TEST_REQUEST_INSTRUMENTATION_LIBRARY_TRACE_JSON_FILE);
            final List<Span> spans = decoderUnderTest.parseExportTraceServiceRequest(exportTraceServiceRequest);
            validateSpans(spans);
        }

    }

    @Nested
    class OTelProtoEncoderTest {
        @Test
        public void testNullToAnyValue() throws UnsupportedEncodingException {
            final AnyValue result = encoderUnderTest.objectToAnyValue(null);
            assertThat(result.getValueCase(), equalTo(AnyValue.ValueCase.VALUE_NOT_SET));
        }

        @Test
        public void testIntegerToAnyValue() throws UnsupportedEncodingException {
            final Integer testInteger = 1;
            final AnyValue result = encoderUnderTest.objectToAnyValue(testInteger);
            assertThat(result.getValueCase(), equalTo(AnyValue.ValueCase.INT_VALUE));
            assertThat(result.getIntValue(), equalTo(testInteger.longValue()));
        }

        @Test
        public void testLongToAnyValue() throws UnsupportedEncodingException {
            final Long testLong = 1L;
            final AnyValue result = encoderUnderTest.objectToAnyValue(testLong);
            assertThat(result.getValueCase(), equalTo(AnyValue.ValueCase.INT_VALUE));
            assertThat(result.getIntValue(), equalTo(testLong));
        }

        @Test
        public void testBooleanToAnyValue() throws UnsupportedEncodingException {
            final Boolean testBoolean = false;
            final AnyValue result = encoderUnderTest.objectToAnyValue(testBoolean);
            assertThat(result.getValueCase(), equalTo(AnyValue.ValueCase.BOOL_VALUE));
            assertThat(result.getBoolValue(), is(testBoolean));
        }

        @Test
        public void testStringToAnyValue() throws UnsupportedEncodingException {
            final String testString = "test string";
            final AnyValue result = encoderUnderTest.objectToAnyValue(testString);
            assertThat(result.getValueCase(), equalTo(AnyValue.ValueCase.STRING_VALUE));
            assertThat(result.getStringValue(), equalTo(testString));
        }

        @Test
        public void testUnsupportedTypeToAnyValue() {
            assertThrows(UnsupportedEncodingException.class,
                    () -> encoderUnderTest.objectToAnyValue(new UnsupportedEncodingClass()));
        }

        @Test
        public void testSpanAttributesToKeyValueList() throws UnsupportedEncodingException {
            final String testKeyRelevant = "relevantKey";
            final String testKeyIrrelevant = "irrelevantKey";
            final Map<String, Object> testAllAttributes = Map.of(
                    OTelProtoCodec.OTelProtoEncoder.SPAN_ATTRIBUTES_PREFIX + testKeyRelevant, 1,
                    testKeyIrrelevant, 2);
            final List<KeyValue> result = encoderUnderTest.getSpanAttributes(testAllAttributes);
            assertThat(result.size(), equalTo(1));
            assertThat(result.get(0).getKey(), equalTo(testKeyRelevant));
            assertThat(result.get(0).getValue().getIntValue(), equalTo(1L));
        }

        @Test
        public void testResourceAttributesToKeyValueList() throws UnsupportedEncodingException {
            final String testKeyRelevant = "relevantKey";
            final String testKeyIrrelevant = "irrelevantKey";
            final Map<String, Object> testAllAttributes = Map.of(
                    OTelProtoCodec.OTelProtoEncoder.RESOURCE_ATTRIBUTES_PREFIX + testKeyRelevant, 1,
                    OTelProtoCodec.OTelProtoEncoder.RESOURCE_ATTRIBUTES_PREFIX + OTelProtoCodec.OTelProtoEncoder.SERVICE_NAME_ATTRIBUTE, "A",
                    testKeyIrrelevant, 2);
            final List<KeyValue> result = encoderUnderTest.getResourceAttributes(testAllAttributes);
            assertThat(result.size(), equalTo(1));
            assertThat(result.get(0).getKey(), equalTo(testKeyRelevant));
            assertThat(result.get(0).getValue().getIntValue(), equalTo(1L));
        }

        @Test
        public void testEncodeSpanStatusComplete() {
            final String testStatusMessage = "test message";
            final int testStatusCode = Status.StatusCode.STATUS_CODE_OK.getNumber();
            final String testKeyIrrelevant = "irrelevantKey";
            final Map<String, Object> testAllAttributes = Map.of(
                    OTelProtoCodec.STATUS_CODE, testStatusCode,
                    OTelProtoCodec.STATUS_MESSAGE, testStatusMessage,
                    testKeyIrrelevant, 2);
            final Status status = encoderUnderTest.constructSpanStatus(testAllAttributes);
            assertThat(status.getCodeValue(), equalTo(testStatusCode));
            assertThat(status.getMessage(), equalTo(testStatusMessage));
        }

        @Test
        public void testEncodeSpanStatusMissingStatusMessage() {
            final int testStatusCode = Status.StatusCode.STATUS_CODE_OK.getNumber();
            final String testKeyIrrelevant = "irrelevantKey";
            final Map<String, Object> testAllAttributes = Map.of(
                    OTelProtoCodec.STATUS_CODE, testStatusCode,
                    testKeyIrrelevant, 2);
            final Status status = encoderUnderTest.constructSpanStatus(testAllAttributes);
            assertThat(status.getCodeValue(), equalTo(testStatusCode));
        }

        @Test
        public void testEncodeSpanStatusMissingStatusCode() {
            final String testStatusMessage = "test message";
            final String testKeyIrrelevant = "irrelevantKey";
            final Map<String, Object> testAllAttributes = Map.of(
                    OTelProtoCodec.STATUS_MESSAGE, testStatusMessage,
                    testKeyIrrelevant, 2);
            final Status status = encoderUnderTest.constructSpanStatus(testAllAttributes);
            assertThat(status.getMessage(), equalTo(testStatusMessage));
        }

        @Test
        public void testEncodeSpanStatusMissingAll() {
            final String testKeyIrrelevant = "irrelevantKey";
            final Map<String, Object> testAllAttributes = Map.of(testKeyIrrelevant, 2);
            final Status status = encoderUnderTest.constructSpanStatus(testAllAttributes);
            assertThat(status, instanceOf(Status.class));
        }

        @Test
        public void testEncodeInstrumentationLibraryComplete() {
            final String testName = "test name";
            final String testVersion = "1.1";
            final String testKeyIrrelevant = "irrelevantKey";
            final Map<String, Object> testAllAttributes = Map.of(
                    OTelProtoCodec.INSTRUMENTATION_SCOPE_NAME, testName,
                    OTelProtoCodec.INSTRUMENTATION_SCOPE_VERSION, testVersion,
                    testKeyIrrelevant, 2);
            final InstrumentationScope instrumentationScope = encoderUnderTest.constructInstrumentationScope(testAllAttributes);
            assertThat(instrumentationScope.getName(), equalTo(testName));
            assertThat(instrumentationScope.getVersion(), equalTo(testVersion));
        }

        @Test
        public void testEncodeInstrumentationLibraryMissingName() {
            final String testVersion = "1.1";
            final String testKeyIrrelevant = "irrelevantKey";
            final Map<String, Object> testAllAttributes = Map.of(
                    OTelProtoCodec.INSTRUMENTATION_SCOPE_VERSION, testVersion,
                    testKeyIrrelevant, 2);
            final InstrumentationScope instrumentationScope = encoderUnderTest.constructInstrumentationScope(testAllAttributes);
            assertThat(instrumentationScope.getVersion(), equalTo(testVersion));
        }

        @Test
        public void testEncodeInstrumentationLibraryMissingVersion() {
            final String testName = "test name";
            final String testKeyIrrelevant = "irrelevantKey";
            final Map<String, Object> testAllAttributes = Map.of(
                    OTelProtoCodec.INSTRUMENTATION_SCOPE_NAME, testName,
                    testKeyIrrelevant, 2);
            final InstrumentationScope instrumentationScope = encoderUnderTest.constructInstrumentationScope(testAllAttributes);
            assertThat(instrumentationScope.getName(), equalTo(testName));
        }

        @Test
        public void testEncodeInstrumentationLibraryMissingAll() {
            final String testKeyIrrelevant = "irrelevantKey";
            final Map<String, Object> testAllAttributes = Map.of(testKeyIrrelevant, 2);
            final InstrumentationScope instrumentationScope = encoderUnderTest.constructInstrumentationScope(testAllAttributes);
            assertThat(instrumentationScope, instanceOf(InstrumentationScope.class));
        }

        @Test
        public void testEncodeResourceComplete() throws UnsupportedEncodingException {
            final String testServiceName = "test name";
            final String testKeyRelevant = "relevantKey";
            final String testKeyIrrelevant = "irrelevantKey";
            final Map<String, Object> testAllAttributes = Map.of(
                    OTelProtoCodec.OTelProtoEncoder.RESOURCE_ATTRIBUTES_PREFIX + testKeyRelevant, 1,
                    OTelProtoCodec.OTelProtoEncoder.RESOURCE_ATTRIBUTES_PREFIX + OTelProtoCodec.OTelProtoEncoder.SERVICE_NAME_ATTRIBUTE, "A",
                    testKeyIrrelevant, 2);
            final Resource resource = encoderUnderTest.constructResource(testServiceName, testAllAttributes);
            assertThat(resource.getAttributesCount(), equalTo(2));
            assertThat(
                    resource.getAttributesList().stream()
                            .anyMatch(kv -> kv.getKey().equals(OTelProtoCodec.SERVICE_NAME) && kv.getValue().getStringValue().equals(testServiceName)),
                    is(true));
            assertThat(resource.getAttributesList().stream().noneMatch(kv -> kv.getKey().equals(OTelProtoCodec.OTelProtoEncoder.SERVICE_NAME_ATTRIBUTE)), is(true));
        }

        @Test
        public void testEncodeResourceMissingServiceName() throws UnsupportedEncodingException {
            final String testKeyRelevant = "relevantKey";
            final String testKeyIrrelevant = "irrelevantKey";
            final Map<String, Object> testAllAttributes = Map.of(
                    OTelProtoCodec.OTelProtoEncoder.RESOURCE_ATTRIBUTES_PREFIX + testKeyRelevant, 1,
                    testKeyIrrelevant, 2);
            final Resource resource = encoderUnderTest.constructResource(null, testAllAttributes);
            assertThat(resource.getAttributesCount(), equalTo(1));
            assertThat(resource.getAttributesList().stream().noneMatch(kv -> kv.getKey().equals(OTelProtoCodec.SERVICE_NAME)), is(true));
        }

        @Test
        public void testEncodeSpanEvent() throws UnsupportedEncodingException {
            final String testName = "test name";
            final long testTimeNanos = System.nanoTime();
            final String testTime = OTelProtoCodec.convertUnixNanosToISO8601(testTimeNanos);
            final String testKey = "test key";
            final String testValue = "test value";
            final SpanEvent testSpanEvent = DefaultSpanEvent.builder()
                    .withName(testName)
                    .withDroppedAttributesCount(0)
                    .withTime(testTime)
                    .withAttributes(Map.of(testKey, testValue))
                    .build();
            final io.opentelemetry.proto.trace.v1.Span.Event result = encoderUnderTest.convertSpanEvent(testSpanEvent);
            assertThat(result.getAttributesCount(), equalTo(1));
            assertThat(result.getDroppedAttributesCount(), equalTo(0));
            assertThat(result.getName(), equalTo(testName));
            assertThat(result.getTimeUnixNano(), equalTo(testTimeNanos));
        }

        @Test
        public void testEncodeSpanLink() throws DecoderException, UnsupportedEncodingException {
            final byte[] testSpanIdBytes = getRandomBytes(16);
            final byte[] testTraceIdBytes = getRandomBytes(16);
            final String testSpanId = Hex.encodeHexString(testSpanIdBytes);
            final String testTraceId = Hex.encodeHexString(testTraceIdBytes);
            final String testTraceState = "test state";
            final String testKey = "test key";
            final String testValue = "test value";
            final Link testSpanLink = DefaultLink.builder()
                    .withSpanId(testSpanId)
                    .withTraceId(testTraceId)
                    .withTraceState(testTraceState)
                    .withDroppedAttributesCount(0)
                    .withAttributes(Map.of(testKey, testValue))
                    .build();
            final io.opentelemetry.proto.trace.v1.Span.Link result = encoderUnderTest.convertSpanLink(testSpanLink);
            assertThat(result.getAttributesCount(), equalTo(1));
            assertThat(result.getDroppedAttributesCount(), equalTo(0));
            assertThat(result.getSpanId().toByteArray(), equalTo(testSpanIdBytes));
            assertThat(result.getTraceId().toByteArray(), equalTo(testTraceIdBytes));
            assertThat(result.getTraceState(), equalTo(testTraceState));
        }

        @Test
        public void testEncodeResourceSpans() throws DecoderException, UnsupportedEncodingException {
            final Span testSpan = buildSpanFromJsonFile(TEST_SPAN_EVENT_JSON_FILE);
            final ResourceSpans rs = encoderUnderTest.convertToResourceSpans(testSpan);
            assertThat(rs.getResource(), equalTo(Resource.getDefaultInstance()));
            assertThat(rs.getScopeSpansCount(), equalTo(1));
            final ScopeSpans scopeSpans = rs.getScopeSpans(0);
            assertThat(scopeSpans.getScope(), equalTo(InstrumentationScope.getDefaultInstance()));
            assertThat(scopeSpans.getSpansCount(), equalTo(1));
            final io.opentelemetry.proto.trace.v1.Span otelProtoSpan = scopeSpans.getSpans(0);
            assertThat(otelProtoSpan.getTraceId(), equalTo(ByteString.copyFrom(Hex.decodeHex(testSpan.getTraceId()))));
            assertThat(otelProtoSpan.getSpanId(), equalTo(ByteString.copyFrom(Hex.decodeHex(testSpan.getSpanId()))));
            assertThat(otelProtoSpan.getParentSpanId(), equalTo(ByteString.copyFrom(Hex.decodeHex(testSpan.getParentSpanId()))));
            assertThat(otelProtoSpan.getName(), equalTo(testSpan.getName()));
            assertThat(otelProtoSpan.getKind(), equalTo(io.opentelemetry.proto.trace.v1.Span.SpanKind.valueOf(testSpan.getKind())));
            assertThat(otelProtoSpan.getTraceState(), equalTo(testSpan.getTraceState()));
            assertThat(otelProtoSpan.getEventsCount(), equalTo(0));
            assertThat(otelProtoSpan.getDroppedEventsCount(), equalTo(0));
            assertThat(otelProtoSpan.getLinksCount(), equalTo(0));
            assertThat(otelProtoSpan.getDroppedLinksCount(), equalTo(0));
            assertThat(otelProtoSpan.getAttributesCount(), equalTo(0));
            assertThat(otelProtoSpan.getDroppedAttributesCount(), equalTo(0));
        }

        private Span buildSpanFromJsonFile(final String jsonFileName) {
            JacksonSpan.Builder spanBuilder = JacksonSpan.builder();
            try (final InputStream inputStream = Objects.requireNonNull(
                    OTelProtoCodecTest.class.getClassLoader().getResourceAsStream(jsonFileName))) {
                final Map<String, Object> spanMap = OBJECT_MAPPER.readValue(inputStream, new TypeReference<Map<String, Object>>() {
                });
                final String traceId = (String) spanMap.get("traceId");
                final String spanId = (String) spanMap.get("spanId");
                final String parentSpanId = (String) spanMap.get("parentSpanId");
                final String traceState = (String) spanMap.get("traceState");
                final String name = (String) spanMap.get("name");
                final String kind = (String) spanMap.get("kind");
                final Long durationInNanos = ((Number) spanMap.get("durationInNanos")).longValue();
                final String startTime = (String) spanMap.get("startTime");
                final String endTime = (String) spanMap.get("endTime");
                spanBuilder = spanBuilder
                        .withTraceId(traceId)
                        .withSpanId(spanId)
                        .withParentSpanId(parentSpanId)
                        .withTraceState(traceState)
                        .withName(name)
                        .withKind(kind)
                        .withDurationInNanos(durationInNanos)
                        .withStartTime(startTime)
                        .withEndTime(endTime)
                        .withTraceGroup(null);
                DefaultTraceGroupFields.Builder traceGroupFieldsBuilder = DefaultTraceGroupFields.builder();
                if (parentSpanId.isEmpty()) {
                    final Integer statusCode = (Integer) ((Map<String, Object>) spanMap.get("traceGroupFields")).get("statusCode");
                    traceGroupFieldsBuilder = traceGroupFieldsBuilder
                            .withStatusCode(statusCode)
                            .withEndTime(endTime)
                            .withDurationInNanos(durationInNanos);
                    final String traceGroup = (String) spanMap.get("traceGroup");
                    spanBuilder = spanBuilder.withTraceGroup(traceGroup);
                }
                spanBuilder.withTraceGroupFields(traceGroupFieldsBuilder.build());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return spanBuilder.build();
        }

        private class UnsupportedEncodingClass {
        }
    }

    @Test
    public void testTimeCodec() {
        final long testNanos = System.nanoTime();
        final String timeISO8601 = OTelProtoCodec.convertUnixNanosToISO8601(testNanos);
        final long nanoCodecResult = OTelProtoCodec.timeISO8601ToNanos(OTelProtoCodec.convertUnixNanosToISO8601(testNanos));
        assertThat(nanoCodecResult, equalTo(testNanos));
        final String stringCodecResult = OTelProtoCodec.convertUnixNanosToISO8601(OTelProtoCodec.timeISO8601ToNanos(timeISO8601));
        assertThat(stringCodecResult, equalTo(timeISO8601));
    }

    @Test
    public void testOTelProtoCodecConsistency() throws IOException, DecoderException {
        final ExportTraceServiceRequest request = buildExportTraceServiceRequestFromJsonFile(TEST_REQUEST_TRACE_JSON_FILE);
        final List<Span> spansFirstDec = decoderUnderTest.parseExportTraceServiceRequest(request);
        final List<ResourceSpans> resourceSpansList = new ArrayList<>();
        for (final Span span : spansFirstDec) {
            resourceSpansList.add(encoderUnderTest.convertToResourceSpans(span));
        }
        final List<Span> spansSecondDec = resourceSpansList.stream()
                .flatMap(rs -> decoderUnderTest.parseResourceSpans(rs).stream()).collect(Collectors.toList());
        assertThat(spansFirstDec.size(), equalTo(spansSecondDec.size()));
        for (int i = 0; i < spansFirstDec.size(); i++) {
            assertThat(spansFirstDec.get(i).toJsonString(), equalTo(spansSecondDec.get(i).toJsonString()));
        }
    }

    @Test
    void getValueAsDouble() {
        Assertions.assertNull(OTelProtoCodec.getValueAsDouble(NumberDataPoint.newBuilder().build()));
    }

    @Test
    public void testCreateBucketsEmpty() {
        MatcherAssert.assertThat(OTelProtoCodec.createBuckets(new ArrayList<>(), new ArrayList<>()).size(), Matchers.equalTo(0));
    }

    @Test
    public void testCreateBuckets() {
        List<Long> bucketsCountList = List.of(1L, 2L, 3L, 4L);
        List<Double> explicitBOundsList = List.of(5D, 10D, 25D);
        List<Bucket> buckets = OTelProtoCodec.createBuckets(bucketsCountList, explicitBOundsList);
        MatcherAssert.assertThat(buckets.size(), Matchers.equalTo(4));
        Bucket b1 = buckets.get(0);
        MatcherAssert.assertThat(b1.getCount(), Matchers.equalTo(1L));
        MatcherAssert.assertThat(b1.getMin(), Matchers.equalTo((double) -Float.MAX_VALUE));
        MatcherAssert.assertThat(b1.getMax(), Matchers.equalTo(5D));

        Bucket b2 = buckets.get(1);
        MatcherAssert.assertThat(b2.getCount(), Matchers.equalTo(2L));
        MatcherAssert.assertThat(b2.getMin(), Matchers.equalTo(5D));
        MatcherAssert.assertThat(b2.getMax(), Matchers.equalTo(10D));

        Bucket b3 = buckets.get(2);
        MatcherAssert.assertThat(b3.getCount(), Matchers.equalTo(3L));
        MatcherAssert.assertThat(b3.getMin(), Matchers.equalTo(10D));
        MatcherAssert.assertThat(b3.getMax(), Matchers.equalTo(25D));

        Bucket b4 = buckets.get(3);
        MatcherAssert.assertThat(b4.getCount(), Matchers.equalTo(4L));
        MatcherAssert.assertThat(b4.getMin(), Matchers.equalTo(25D));
        MatcherAssert.assertThat(b4.getMax(), Matchers.equalTo((double) Float.MAX_VALUE));
    }

    @Test
    public void testCreateBuckets_illegal_argument() {
        List<Long> bucketsCountList = List.of(1L, 2L, 3L, 4L);
        List<Double> boundsList = Collections.emptyList();
        Assertions.assertThrows(IllegalArgumentException.class, () -> OTelProtoCodec.createBuckets(bucketsCountList, boundsList));
    }

    @Test
    public void testConvertAnyValueBool() {
        Object o = OTelProtoCodec.convertAnyValue(AnyValue.newBuilder().setBoolValue(true).build());
        MatcherAssert.assertThat(o instanceof Boolean, Matchers.equalTo(true));
        MatcherAssert.assertThat(((boolean) o), Matchers.equalTo(true));
    }

    @Test
    public void testUnsupportedTypeToAnyValue() {
        Assertions.assertThrows(RuntimeException.class,
                () -> OTelProtoCodec.convertAnyValue(AnyValue.newBuilder().setBytesValue(ByteString.EMPTY).build()));
    }

    @Test
    void convertExemplars() {
        Exemplar e1 = Exemplar.newBuilder()
                .addFilteredAttributes(KeyValue.newBuilder()
                        .setKey("key")
                        .setValue(AnyValue.newBuilder().setBoolValue(true).build()).build())
                .setAsDouble(3)
                .setSpanId(ByteString.copyFrom(getRandomBytes(8)))
                .setTimeUnixNano(TIME)
                .setTraceId(ByteString.copyFrom(getRandomBytes(8)))
                .build();


        Exemplar e2 = Exemplar.newBuilder()
                .addFilteredAttributes(KeyValue.newBuilder()
                        .setKey("key2")
                        .setValue(AnyValue.newBuilder()
                                .setArrayValue(ArrayValue.newBuilder().addValues(AnyValue.newBuilder().setStringValue("test").build()).build())
                                .build()).build())
                .setAsInt(42)
                .setSpanId(ByteString.copyFrom(getRandomBytes(8)))
                .setTimeUnixNano(TIME)
                .setTraceId(ByteString.copyFrom(getRandomBytes(8)))
                .build();

        List<io.opentelemetry.proto.metrics.v1.Exemplar> exemplars = Arrays.asList(e1, e2);
        List<org.opensearch.dataprepper.model.metric.Exemplar> convertedExemplars = OTelProtoCodec.convertExemplars(exemplars);
        MatcherAssert.assertThat(convertedExemplars.size(), Matchers.equalTo(2));

        org.opensearch.dataprepper.model.metric.Exemplar conv1 = convertedExemplars.get(0);
        MatcherAssert.assertThat(conv1.getSpanId(), Matchers.equalTo(Hex.encodeHexString(e1.getSpanId().toByteArray())));
        MatcherAssert.assertThat(conv1.getTime(), Matchers.equalTo("2020-05-24T14:01:00Z"));
        MatcherAssert.assertThat(conv1.getTraceId(), Matchers.equalTo(Hex.encodeHexString(e1.getTraceId().toByteArray())));
        MatcherAssert.assertThat(conv1.getValue(), Matchers.equalTo(3.0));
        org.assertj.core.api.Assertions.assertThat(conv1.getAttributes()).contains(entry("exemplar.attributes.key", true));

        org.opensearch.dataprepper.model.metric.Exemplar conv2 = convertedExemplars.get(1);
        MatcherAssert.assertThat(conv2.getSpanId(), Matchers.equalTo(Hex.encodeHexString(e2.getSpanId().toByteArray())));
        MatcherAssert.assertThat(conv2.getTime(), Matchers.equalTo("2020-05-24T14:01:00Z"));
        MatcherAssert.assertThat(conv2.getTraceId(), Matchers.equalTo(Hex.encodeHexString(e2.getTraceId().toByteArray())));
        MatcherAssert.assertThat(conv2.getValue(), Matchers.equalTo(42.0));
        org.assertj.core.api.Assertions.assertThat(conv2.getAttributes()).contains(entry("exemplar.attributes.key2", "[\"test\"]"));

    }


    /**
     * See: <a href="https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/metrics/datamodel.md#exponential-buckets">The example table with scale 3</a>
     */
    @Test
    public void testExponentialHistogram() {
        List<Bucket> b = OTelProtoCodec.createExponentialBuckets(
                ExponentialHistogramDataPoint.Buckets.newBuilder()
                        .addBucketCounts(4)
                        .addBucketCounts(2)
                        .addBucketCounts(3)
                        .addBucketCounts(2)
                        .addBucketCounts(1)
                        .addBucketCounts(4)
                        .addBucketCounts(6)
                        .addBucketCounts(4)
                        .setOffset(0)
                        .build(), 3);

        MatcherAssert.assertThat(b.size(), Matchers.equalTo(8));

        Bucket b1 = b.get(0);
        MatcherAssert.assertThat(b1.getCount(), Matchers.equalTo(4L));
        MatcherAssert.assertThat(b1.getMin(), Matchers.equalTo(1D));
        MatcherAssert.assertThat(b1.getMax(), Matchers.closeTo(1.09051, MAX_ERROR));

        Bucket b2 = b.get(1);
        MatcherAssert.assertThat(b2.getCount(), Matchers.equalTo(2L));
        MatcherAssert.assertThat(b2.getMin(), Matchers.closeTo(1.09051, MAX_ERROR));
        MatcherAssert.assertThat(b2.getMax(), Matchers.closeTo(1.18921, MAX_ERROR));

        Bucket b3 = b.get(2);
        MatcherAssert.assertThat(b3.getCount(), Matchers.equalTo(3L));
        MatcherAssert.assertThat(b3.getMin(), Matchers.closeTo(1.18921, MAX_ERROR));
        MatcherAssert.assertThat(b3.getMax(), Matchers.closeTo(1.29684, MAX_ERROR));

        Bucket b4 = b.get(3);
        MatcherAssert.assertThat(b4.getCount(), Matchers.equalTo(2L));
        MatcherAssert.assertThat(b4.getMin(), Matchers.closeTo(1.29684, MAX_ERROR));
        MatcherAssert.assertThat(b4.getMax(), Matchers.closeTo(1.41421, MAX_ERROR));

        Bucket b5 = b.get(4);
        MatcherAssert.assertThat(b5.getCount(), Matchers.equalTo(1L));
        MatcherAssert.assertThat(b5.getMin(), Matchers.closeTo(1.41421, MAX_ERROR));
        MatcherAssert.assertThat(b5.getMax(), Matchers.closeTo(1.54221, MAX_ERROR));

        Bucket b6 = b.get(5);
        MatcherAssert.assertThat(b6.getCount(), Matchers.equalTo(4L));
        MatcherAssert.assertThat(b6.getMin(), Matchers.closeTo(1.54221, MAX_ERROR));
        MatcherAssert.assertThat(b6.getMax(), Matchers.closeTo(1.68179, MAX_ERROR));

        Bucket b7 = b.get(6);
        MatcherAssert.assertThat(b7.getCount(), Matchers.equalTo(6L));
        MatcherAssert.assertThat(b7.getMin(), Matchers.closeTo(1.68179, MAX_ERROR));
        MatcherAssert.assertThat(b7.getMax(), Matchers.closeTo(1.83401, MAX_ERROR));

        Bucket b8 = b.get(7);
        MatcherAssert.assertThat(b8.getCount(), Matchers.equalTo(4L));
        MatcherAssert.assertThat(b8.getMin(), Matchers.closeTo(1.83401, MAX_ERROR));
        MatcherAssert.assertThat(b8.getMax(), Matchers.closeTo(2, MAX_ERROR));
    }

    @Test
    public void testExponentialHistogramWithOffset() {
        List<Bucket> b = OTelProtoCodec.createExponentialBuckets(
                ExponentialHistogramDataPoint.Buckets.newBuilder()
                        .addBucketCounts(4)
                        .addBucketCounts(2)
                        .addBucketCounts(3)
                        .addBucketCounts(2)
                        .addBucketCounts(1)
                        .addBucketCounts(4)
                        .setOffset(2)
                        .build(), 3);

        MatcherAssert.assertThat(b.size(), Matchers.equalTo(6));

        Bucket b1 = b.get(0);
        MatcherAssert.assertThat(b1.getCount(), Matchers.equalTo(4L));
        MatcherAssert.assertThat(b1.getMin(), Matchers.closeTo(1.18920, MAX_ERROR));
        MatcherAssert.assertThat(b1.getMax(), Matchers.closeTo(1.29684, MAX_ERROR));

        Bucket b2 = b.get(1);
        MatcherAssert.assertThat(b2.getCount(), Matchers.equalTo(2L));
        MatcherAssert.assertThat(b2.getMin(), Matchers.closeTo(1.29684, MAX_ERROR));
        MatcherAssert.assertThat(b2.getMax(), Matchers.closeTo(1.41421, MAX_ERROR));

        Bucket b3 = b.get(2);
        MatcherAssert.assertThat(b3.getCount(), Matchers.equalTo(3L));
        MatcherAssert.assertThat(b3.getMin(), Matchers.closeTo(1.41421, MAX_ERROR));
        MatcherAssert.assertThat(b3.getMax(), Matchers.closeTo(1.54221, MAX_ERROR));

        Bucket b4 = b.get(3);
        MatcherAssert.assertThat(b4.getCount(), Matchers.equalTo(2L));
        MatcherAssert.assertThat(b4.getMin(), Matchers.closeTo(1.54221, MAX_ERROR));
        MatcherAssert.assertThat(b4.getMax(), Matchers.closeTo(1.68179, MAX_ERROR));

        Bucket b5 = b.get(4);
        MatcherAssert.assertThat(b5.getCount(), Matchers.equalTo(1L));
        MatcherAssert.assertThat(b5.getMin(), Matchers.closeTo(1.68179, MAX_ERROR));
        MatcherAssert.assertThat(b5.getMax(), Matchers.closeTo(1.83401, MAX_ERROR));

        Bucket b6 = b.get(5);
        MatcherAssert.assertThat(b6.getCount(), Matchers.equalTo(4L));
        MatcherAssert.assertThat(b6.getMin(), Matchers.closeTo(1.83401, MAX_ERROR));
        MatcherAssert.assertThat(b6.getMax(), Matchers.closeTo(2, MAX_ERROR));
    }

    @Test
    public void testExponentialHistogramWithLargeOffset() {
        List<Bucket> b = OTelProtoCodec.createExponentialBuckets(
                ExponentialHistogramDataPoint.Buckets.newBuilder()
                        .addBucketCounts(4)
                        .addBucketCounts(2)
                        .addBucketCounts(3)
                        .addBucketCounts(2)
                        .setOffset(20)
                        .build(), 2);

        MatcherAssert.assertThat(b.size(), Matchers.equalTo(4));

        Bucket b1 = b.get(0);
        MatcherAssert.assertThat(b1.getCount(), Matchers.equalTo(4L));
        MatcherAssert.assertThat(b1.getMin(), Matchers.closeTo(32.0, MAX_ERROR));
        MatcherAssert.assertThat(b1.getMax(), Matchers.closeTo(38.05462, MAX_ERROR));

        Bucket b2 = b.get(1);
        MatcherAssert.assertThat(b2.getCount(), Matchers.equalTo(2L));
        MatcherAssert.assertThat(b2.getMin(), Matchers.closeTo(38.05462, MAX_ERROR));
        MatcherAssert.assertThat(b2.getMax(), Matchers.closeTo(45.254833, MAX_ERROR));

        Bucket b3 = b.get(2);
        MatcherAssert.assertThat(b3.getCount(), Matchers.equalTo(3L));
        MatcherAssert.assertThat(b3.getMin(), Matchers.closeTo(45.254833, MAX_ERROR));
        MatcherAssert.assertThat(b3.getMax(), Matchers.closeTo(53.81737, MAX_ERROR));

        Bucket b4 = b.get(3);
        MatcherAssert.assertThat(b4.getCount(), Matchers.equalTo(2L));
        MatcherAssert.assertThat(b4.getMin(), Matchers.closeTo(53.81737, MAX_ERROR));
        MatcherAssert.assertThat(b4.getMax(), Matchers.closeTo(63.99999, MAX_ERROR));
    }

    @Test
    public void testExponentialHistogramWithNegativeOffset() {
        List<Bucket> b = OTelProtoCodec.createExponentialBuckets(
                ExponentialHistogramDataPoint.Buckets.newBuilder()
                        .addBucketCounts(4)
                        .addBucketCounts(2)
                        .addBucketCounts(3)
                        .addBucketCounts(2)
                        .setOffset(-5)
                        .build(), 2);

        MatcherAssert.assertThat(b.size(), Matchers.equalTo(4));

        Bucket b1 = b.get(0);
        MatcherAssert.assertThat(b1.getCount(), Matchers.equalTo(4L));
        MatcherAssert.assertThat(b1.getMin(), Matchers.closeTo(0.42044820762685736, MAX_ERROR));
        MatcherAssert.assertThat(b1.getMax(), Matchers.closeTo(0.35355339059327384, MAX_ERROR));

        Bucket b2 = b.get(1);
        MatcherAssert.assertThat(b2.getCount(), Matchers.equalTo(2L));
        MatcherAssert.assertThat(b2.getMin(), Matchers.closeTo(0.35355339059327384, MAX_ERROR));
        MatcherAssert.assertThat(b2.getMax(), Matchers.closeTo(0.2973017787506803, MAX_ERROR));

        Bucket b3 = b.get(2);
        MatcherAssert.assertThat(b3.getCount(), Matchers.equalTo(3L));
        MatcherAssert.assertThat(b3.getMin(), Matchers.closeTo(0.2973017787506803, MAX_ERROR));
        MatcherAssert.assertThat(b3.getMax(), Matchers.closeTo(0.2500000000, MAX_ERROR));

        Bucket b4 = b.get(3);
        MatcherAssert.assertThat(b4.getCount(), Matchers.equalTo(2L));
        MatcherAssert.assertThat(b4.getMin(), Matchers.closeTo(0.2500000000, MAX_ERROR));
        MatcherAssert.assertThat(b4.getMax(), Matchers.closeTo(0.2102241038134287, MAX_ERROR));
    }

    @Test
    public void testExponentialHistogramWithNegativeScale() {
        List<Bucket> b = OTelProtoCodec.createExponentialBuckets(
                ExponentialHistogramDataPoint.Buckets.newBuilder()
                        .addBucketCounts(4)
                        .addBucketCounts(2)
                        .addBucketCounts(3)
                        .addBucketCounts(2)
                        .setOffset(0)
                        .build(), -2);

        MatcherAssert.assertThat(b.size(), Matchers.equalTo(4));

        Bucket b1 = b.get(0);
        MatcherAssert.assertThat(b1.getCount(), Matchers.equalTo(4L));
        MatcherAssert.assertThat(b1.getMin(), Matchers.closeTo(1, MAX_ERROR));
        MatcherAssert.assertThat(b1.getMax(), Matchers.closeTo(16, MAX_ERROR));

        Bucket b2 = b.get(1);
        MatcherAssert.assertThat(b2.getCount(), Matchers.equalTo(2L));
        MatcherAssert.assertThat(b2.getMin(), Matchers.closeTo(16, MAX_ERROR));
        MatcherAssert.assertThat(b2.getMax(), Matchers.closeTo(256, MAX_ERROR));

        Bucket b3 = b.get(2);
        MatcherAssert.assertThat(b3.getCount(), Matchers.equalTo(3L));
        MatcherAssert.assertThat(b3.getMin(), Matchers.closeTo(256, MAX_ERROR));
        MatcherAssert.assertThat(b3.getMax(), Matchers.closeTo(4096, MAX_ERROR));

        Bucket b4 = b.get(3);
        MatcherAssert.assertThat(b4.getCount(), Matchers.equalTo(2L));
        MatcherAssert.assertThat(b4.getMin(), Matchers.closeTo(4096, MAX_ERROR));
        MatcherAssert.assertThat(b4.getMax(), Matchers.closeTo(65536, MAX_ERROR));
    }

    @Test
    public void testExponentialHistogramWithMaxOffsetOutOfRange() {
        List<Bucket> b = OTelProtoCodec.createExponentialBuckets(
                ExponentialHistogramDataPoint.Buckets.newBuilder()
                        .addBucketCounts(4)
                        .addBucketCounts(2)
                        .addBucketCounts(3)
                        .addBucketCounts(2)
                        .setOffset(1025)
                        .build(), -2);

        MatcherAssert.assertThat(b.size(), Matchers.equalTo(0));
    }

    @Test
    public void testExponentialHistogramWithMaxNegativeOffsetOutOfRange() {
        List<Bucket> b = OTelProtoCodec.createExponentialBuckets(
                ExponentialHistogramDataPoint.Buckets.newBuilder()
                        .addBucketCounts(4)
                        .addBucketCounts(2)
                        .addBucketCounts(3)
                        .addBucketCounts(2)
                        .setOffset(-1025)
                        .build(), -2);

        MatcherAssert.assertThat(b.size(), Matchers.equalTo(0));
    }

    @Test
    public void testBoundsKeyEquals() {
        OTelProtoCodec.BoundsKey k1 = new OTelProtoCodec.BoundsKey(2, OTelProtoCodec.BoundsKey.Sign.POSITIVE);
        OTelProtoCodec.BoundsKey k2 = new OTelProtoCodec.BoundsKey(2, OTelProtoCodec.BoundsKey.Sign.POSITIVE);
        assertEquals(k1, k2);
    }

    @Test
    public void testBoundsKeyNotEqualsScale() {
        OTelProtoCodec.BoundsKey k1 = new OTelProtoCodec.BoundsKey(2, OTelProtoCodec.BoundsKey.Sign.POSITIVE);
        OTelProtoCodec.BoundsKey k2 = new OTelProtoCodec.BoundsKey(-2, OTelProtoCodec.BoundsKey.Sign.POSITIVE);
        assertNotEquals(k1, k2);
    }

    @Test
    public void testBoundsKeyNotEqualsSign() {
        OTelProtoCodec.BoundsKey k1 = new OTelProtoCodec.BoundsKey(2, OTelProtoCodec.BoundsKey.Sign.POSITIVE);
        OTelProtoCodec.BoundsKey k2 = new OTelProtoCodec.BoundsKey(2, OTelProtoCodec.BoundsKey.Sign.NEGATIVE);
        assertNotEquals(k1, k2);
    }

}
