/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.dataprepper.plugins.sink.s3;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroup;
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.hadoop.util.HadoopInputFile;
import org.apache.parquet.io.ColumnIOFactory;
import org.apache.parquet.io.MessageColumnIO;
import org.apache.parquet.io.RecordReader;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.Type;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.dataprepper.metrics.PluginMetrics;
import org.opensearch.dataprepper.model.codec.OutputCodec;
import org.opensearch.dataprepper.model.event.DefaultEventMetadata;
import org.opensearch.dataprepper.model.event.Event;
import org.opensearch.dataprepper.model.event.EventHandle;
import org.opensearch.dataprepper.model.event.EventMetadata;
import org.opensearch.dataprepper.model.event.EventType;
import org.opensearch.dataprepper.model.event.JacksonEvent;
import org.opensearch.dataprepper.model.log.JacksonLog;
import org.opensearch.dataprepper.model.record.Record;
import org.opensearch.dataprepper.model.types.ByteCount;
import org.opensearch.dataprepper.plugins.codec.parquet.ParquetOutputCodec;
import org.opensearch.dataprepper.plugins.codec.parquet.ParquetOutputCodecConfig;
import org.opensearch.dataprepper.plugins.codec.json.NdjsonOutputCodec;
import org.opensearch.dataprepper.plugins.codec.json.NdjsonOutputConfig;
import org.opensearch.dataprepper.plugins.sink.s3.accumulator.BufferFactory;
import org.opensearch.dataprepper.plugins.sink.s3.accumulator.InMemoryBufferFactory;
import org.opensearch.dataprepper.plugins.sink.s3.accumulator.ObjectKey;
import org.opensearch.dataprepper.plugins.sink.s3.configuration.AwsAuthenticationOptions;
import org.opensearch.dataprepper.plugins.sink.s3.configuration.ObjectKeyOptions;
import org.opensearch.dataprepper.plugins.sink.s3.configuration.ThresholdOptions;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsResponse;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3SinkServiceIT {

    private static final String PATH_PREFIX = UUID.randomUUID().toString() + "/%{yyyy}/%{MM}/%{dd}/";
    private static final int numberOfRecords = 2;
    private S3Client s3Client;
    private String bucketName;
    private String s3region;
    private ParquetOutputCodecConfig parquetOutputCodecConfig;
    private BufferFactory bufferFactory;
    private static final String FILE_NAME = "parquet-data";
    private static final String FILE_SUFFIX = ".parquet";
    @Mock
    private S3SinkConfig s3SinkConfig;
    @Mock
    private ThresholdOptions thresholdOptions;
    @Mock
    private AwsAuthenticationOptions awsAuthenticationOptions;
    @Mock
    private ObjectKeyOptions objectKeyOptions;
    @Mock
    private PluginMetrics pluginMetrics;
    @Mock
    private Counter snapshotSuccessCounter;
    @Mock
    private Counter snapshotFailedCounter;
    @Mock
    private Counter numberOfRecordsSuccessCounter;
    @Mock
    private Counter numberOfRecordsFailedCounter;
    @Mock
    private DistributionSummary s3ObjectSizeSummary;

    private OutputCodec codec;

    @Mock
    NdjsonOutputConfig ndjsonOutputConfig;


    @BeforeEach
    public void setUp() {
        s3region = System.getProperty("tests.s3ink.region");

        s3Client = S3Client.builder().region(Region.of(s3region)).build();
        bucketName = System.getProperty("tests.s3sink.bucket");
        bufferFactory = new InMemoryBufferFactory();

        when(objectKeyOptions.getNamePattern()).thenReturn("elb-log-%{yyyy-MM-dd'T'hh-mm-ss}");
        when(objectKeyOptions.getPathPrefix()).thenReturn(PATH_PREFIX);
        when(s3SinkConfig.getBucketName()).thenReturn(bucketName);
        when(s3SinkConfig.getObjectKeyOptions()).thenReturn(objectKeyOptions);
        when(thresholdOptions.getEventCount()).thenReturn(2);
        when(thresholdOptions.getMaximumSize()).thenReturn(ByteCount.parse("2mb"));
        when(thresholdOptions.getEventCollectTimeOut()).thenReturn(Duration.parse("PT3M"));
        when(s3SinkConfig.getThresholdOptions()).thenReturn(thresholdOptions);

        lenient().when(pluginMetrics.counter(S3SinkService.OBJECTS_SUCCEEDED)).thenReturn(snapshotSuccessCounter);
        lenient().when(pluginMetrics.counter(S3SinkService.OBJECTS_FAILED)).thenReturn(snapshotFailedCounter);
        lenient().when(pluginMetrics.counter(S3SinkService.NUMBER_OF_RECORDS_FLUSHED_TO_S3_SUCCESS)).
                thenReturn(numberOfRecordsSuccessCounter);
        lenient().when(pluginMetrics.counter(S3SinkService.NUMBER_OF_RECORDS_FLUSHED_TO_S3_FAILED)).
                thenReturn(numberOfRecordsFailedCounter);
        lenient().when(pluginMetrics.summary(S3SinkService.S3_OBJECTS_SIZE)).thenReturn(s3ObjectSizeSummary);
    }

    @Test
    void verify_flushed_object_count_into_s3_bucket() {
        configureNewLineCodec();
        int s3ObjectCountBeforeIngest = gets3ObjectCount();
        S3SinkService s3SinkService = createObjectUnderTest();
        s3SinkService.output(setEventQueue());
        int s3ObjectCountAfterIngest = gets3ObjectCount();
        assertThat(s3ObjectCountAfterIngest, equalTo(s3ObjectCountBeforeIngest + 1));
    }

    void configureNewLineCodec() {
        codec = new NdjsonOutputCodec(ndjsonOutputConfig);
        when(ndjsonOutputConfig.getExcludeKeys()).thenReturn(new ArrayList<>());
    }

    @Test
    void verify_flushed_records_into_s3_bucketNewLine() {
        configureNewLineCodec();
        S3SinkService s3SinkService = createObjectUnderTest();
        Collection<Record<Event>> recordsData = setEventQueue();

        s3SinkService.output(recordsData);
        String objectData = getS3Object();

        int count = 0;
        String[] objectDataArr = objectData.split("\r\n");
        for (Record<Event> recordData : recordsData) {
            String objectRecord = recordData.getData().toJsonString();
            assertThat(objectDataArr[count], CoreMatchers.containsString(objectRecord));
            count++;
        }
    }

    private S3SinkService createObjectUnderTest() {
        return new S3SinkService(s3SinkConfig, bufferFactory, codec, s3Client, "Tag", pluginMetrics);
    }

    private int gets3ObjectCount() {
        int s3ObjectCount = 0;
        ListObjectsRequest listObjects = ListObjectsRequest.builder()
                .bucket(bucketName).prefix(getPathPrefix())
                .build();
        ListObjectsResponse res = s3Client.listObjects(listObjects);
        List<S3Object> objects = res.contents();
        s3ObjectCount = objects.size();

        return s3ObjectCount;
    }

    private String getS3Object() {

        ListObjectsRequest listObjects = ListObjectsRequest.builder()
                .bucket(bucketName)
                .prefix(getPathPrefix())
                .build();
        ListObjectsResponse res = s3Client.listObjects(listObjects);
        List<S3Object> objects = res.contents();

        S3Object object = objects.get(objects.size() - 1);

        String objectKey = object.key();
        GetObjectRequest objectRequest = GetObjectRequest
                .builder()
                .key(objectKey)
                .bucket(bucketName).build();

        ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(objectRequest);
        byte[] data = objectBytes.asByteArray();
        return new String(data);
    }

    private String getPathPrefix() {
        return ObjectKey.buildingPathPrefix(s3SinkConfig);
    }

    private Collection<Record<Event>> setEventQueue() {
        final Collection<Record<Event>> jsonObjects = new LinkedList<>();
        for (int i = 0; i < 2; i++)
            jsonObjects.add(createRecord());
        return jsonObjects;
    }

    private static Record<Event> createRecord() {
        final Set<String> testTags = Set.of("tag1");
        final EventMetadata defaultEventMetadata = DefaultEventMetadata.builder().
                withEventType(EventType.LOG.toString()).
                withTags(testTags).build();
        Map<String, Object> json = generateJson(testTags);
        final JacksonEvent event = JacksonLog.builder().withData(json).withEventMetadata(defaultEventMetadata).build();
        event.setEventHandle(mock(EventHandle.class));
        return new Record<>(event);
    }

    private static Map<String, Object> generateJson(Set<String> testTags) {
        final Map<String, Object> jsonObject = new LinkedHashMap<>();
        for (int i = 0; i < 2; i++) {
            jsonObject.put(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        }
        jsonObject.put(UUID.randomUUID().toString(), Arrays.asList(UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), UUID.randomUUID().toString()));
        jsonObject.put("Tag", testTags.toArray());
        return jsonObject;
    }
    private static Record getRecord(int index) {
        List<HashMap> recordList = generateRecords(numberOfRecords);
        final Event event = JacksonLog.builder().withData(recordList.get(index)).build();
        return new Record<>(event);
    }

    private static List<HashMap> generateRecords(int numberOfRecords) {

        List<HashMap> recordList = new ArrayList<>();

        for (int rows = 0; rows < numberOfRecords; rows++) {

            HashMap<String, String> eventData = new HashMap<>();

            eventData.put("name", "Person" + rows);
            eventData.put("age", Integer.toString(rows));
            recordList.add((eventData));

        }
        return recordList;
    }
    @Test
    void verify_flushed_records_into_s3_bucket_Parquet() throws IOException {
        configureParquetCodec();
        S3SinkService s3SinkService = createObjectUnderTest();
        Collection<Record<Event>> recordsData = getRecordList();

        s3SinkService.output(recordsData);

        List<HashMap<String, Object>> actualRecords = createParquetRecordsList(new ByteArrayInputStream(getS3Object().getBytes()));
        int index = 0;
        for (final HashMap<String, Object> actualMap : actualRecords) {
            assertThat(actualMap, notNullValue());
            Map expectedMap = generateRecords(numberOfRecords).get(index);
            assertThat(expectedMap, Matchers.equalTo(actualMap));
            index++;
        }
    }

    private void configureParquetCodec() {
        parquetOutputCodecConfig = new ParquetOutputCodecConfig();
        parquetOutputCodecConfig.setSchema(parseSchema().toString());
        parquetOutputCodecConfig.setBucket(bucketName);
        parquetOutputCodecConfig.setRegion(s3region);
        parquetOutputCodecConfig.setPathPrefix(PATH_PREFIX);
        codec = new ParquetOutputCodec(parquetOutputCodecConfig);
        when(parquetOutputCodecConfig.getExcludeKeys()).thenReturn(new ArrayList<>());

    }
    private Collection<Record<Event>> getRecordList() {
        final Collection<Record<Event>> recordList = new ArrayList<>();
        for (int i = 0; i < numberOfRecords; i++)
            recordList.add(getRecord(i));
        return recordList;
    }
    private static Schema parseSchema() {
        return SchemaBuilder.record("Person")
                .fields()
                .name("name").type().stringType().noDefault()
                .name("age").type().intType().noDefault()
                .endRecord();
    }
    private List<HashMap<String, Object>> createParquetRecordsList(final InputStream inputStream) throws IOException {

        final File tempFile = File.createTempFile(FILE_NAME, FILE_SUFFIX);
        Files.copy(inputStream, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        List<HashMap<String, Object>> actualRecordList = new ArrayList<>();
        try (ParquetFileReader parquetFileReader = new ParquetFileReader(HadoopInputFile.fromPath(new Path(tempFile.toURI()), new Configuration()), ParquetReadOptions.builder().build())) {
            final ParquetMetadata footer = parquetFileReader.getFooter();
            final MessageType schema = createdParquetSchema(footer);
            PageReadStore pages;

            while ((pages = parquetFileReader.readNextRowGroup()) != null) {
                final long rows = pages.getRowCount();
                final MessageColumnIO columnIO = new ColumnIOFactory().getColumnIO(schema);
                final RecordReader<Group> recordReader = columnIO.getRecordReader(pages, new GroupRecordConverter(schema));
                for (int row = 0; row < rows; row++) {
                    final Map<String, Object> eventData = new HashMap<>();
                    int fieldIndex = 0;
                    final SimpleGroup simpleGroup = (SimpleGroup) recordReader.read();
                    for (Type field : schema.getFields()) {
                        try {
                            eventData.put(field.getName(), simpleGroup.getValueToString(fieldIndex, 0));
                        } catch (Exception parquetException) {
                            parquetException.printStackTrace();
                        }
                        fieldIndex++;
                    }
                    actualRecordList.add((HashMap) eventData);
                }
            }
        } catch (Exception parquetException) {
            parquetException.printStackTrace();
        } finally {
            Files.delete(tempFile.toPath());
        }
        return actualRecordList;
    }

    private MessageType createdParquetSchema(ParquetMetadata parquetMetadata) {
        return parquetMetadata.getFileMetaData().getSchema();
    }
}