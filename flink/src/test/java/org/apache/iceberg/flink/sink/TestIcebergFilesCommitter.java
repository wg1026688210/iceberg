/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.flink.sink;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.JobID;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.runtime.operators.testutils.MockEnvironment;
import org.apache.flink.runtime.operators.testutils.MockEnvironmentBuilder;
import org.apache.flink.runtime.operators.testutils.MockInputSplitProvider;
import org.apache.flink.streaming.api.operators.AbstractStreamOperatorFactory;
import org.apache.flink.streaming.api.operators.BoundedOneInput;
import org.apache.flink.streaming.api.operators.OneInputStreamOperatorFactory;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.streaming.api.operators.StreamOperatorParameters;
import org.apache.flink.streaming.util.OneInputStreamOperatorTestHarness;
import org.apache.flink.table.data.RowData;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.GenericManifestFile;
import org.apache.iceberg.ManifestContent;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.Table;
import org.apache.iceberg.flink.SimpleDataUtil;
import org.apache.iceberg.flink.TableLoader;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.apache.iceberg.TableProperties.DEFAULT_FILE_FORMAT;
import static org.apache.iceberg.flink.sink.ManifestOutputFileFactory.FLINK_MANIFEST_LOCATION;

@RunWith(Parameterized.class)
public class TestIcebergFilesCommitter {
  private static final Configuration CONF = new Configuration();

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  private String tablePath;
  private Table table;
  private File flinkManifestFolder;

  private final FileFormat format;

  @Parameterized.Parameters(name = "format = {0}")
  public static Object[][] parameters() {
    return new Object[][] {
        new Object[] {"avro"},
        new Object[] {"orc"},
        new Object[] {"parquet"}
    };
  }

  public TestIcebergFilesCommitter(String format) {
    this.format = FileFormat.valueOf(format.toUpperCase(Locale.ENGLISH));
  }

  @Before
  public void before() throws IOException {
    File folder = tempFolder.newFolder();
    flinkManifestFolder = tempFolder.newFolder();
    String warehouse = folder.getAbsolutePath();

    tablePath = warehouse.concat("/test");
    Assert.assertTrue("Should create the table directory correctly.", new File(tablePath).mkdir());

    // Construct the iceberg table.
    Map<String, String> props = ImmutableMap.of(
        // file format.
        DEFAULT_FILE_FORMAT, format.name(),
        // temporary flink manifests location.
        FLINK_MANIFEST_LOCATION, flinkManifestFolder.getAbsolutePath()
    );
    table = SimpleDataUtil.createTable(tablePath, props, false);
  }

  @Test
  public void testCommitTxnWithoutDataFiles() throws Exception {
    long checkpointId = 0;
    long timestamp = 0;
    JobID jobId = new JobID();
    try (OneInputStreamOperatorTestHarness<DataFile, Void> harness = createStreamSink(jobId)) {
      harness.setup();
      harness.open();

      SimpleDataUtil.assertTableRows(tablePath, Lists.newArrayList());
      assertSnapshotSize(0);
      assertMaxCommittedCheckpointId(jobId, -1L);

      // It's better to advance the max-committed-checkpoint-id in iceberg snapshot, so that the future flink job
      // failover won't fail.
      for (int i = 1; i <= 3; i++) {
        harness.snapshot(++checkpointId, ++timestamp);
        assertFlinkManifests(0);

        harness.notifyOfCompletedCheckpoint(checkpointId);
        assertFlinkManifests(0);

        assertSnapshotSize(i);
        assertMaxCommittedCheckpointId(jobId, checkpointId);
      }
    }
  }

  @Test
  public void testCommitTxn() throws Exception {
    // Test with 3 continues checkpoints:
    //   1. snapshotState for checkpoint#1
    //   2. notifyCheckpointComplete for checkpoint#1
    //   3. snapshotState for checkpoint#2
    //   4. notifyCheckpointComplete for checkpoint#2
    //   5. snapshotState for checkpoint#3
    //   6. notifyCheckpointComplete for checkpoint#3
    long timestamp = 0;

    JobID jobID = new JobID();
    try (OneInputStreamOperatorTestHarness<DataFile, Void> harness = createStreamSink(jobID)) {
      harness.setup();
      harness.open();
      assertSnapshotSize(0);

      List<RowData> rows = Lists.newArrayListWithExpectedSize(3);
      for (int i = 1; i <= 3; i++) {
        RowData rowData = SimpleDataUtil.createRowData(i, "hello" + i);
        DataFile dataFile = writeDataFile("data-" + i, ImmutableList.of(rowData));
        harness.processElement(dataFile, ++timestamp);
        rows.add(rowData);

        harness.snapshot(i, ++timestamp);
        assertFlinkManifests(1);

        harness.notifyOfCompletedCheckpoint(i);
        assertFlinkManifests(0);

        SimpleDataUtil.assertTableRows(tablePath, ImmutableList.copyOf(rows));
        assertSnapshotSize(i);
        assertMaxCommittedCheckpointId(jobID, i);
      }
    }
  }

  @Test
  public void testOrderedEventsBetweenCheckpoints() throws Exception {
    // It's possible that two checkpoints happen in the following orders:
    //   1. snapshotState for checkpoint#1;
    //   2. snapshotState for checkpoint#2;
    //   3. notifyCheckpointComplete for checkpoint#1;
    //   4. notifyCheckpointComplete for checkpoint#2;
    long timestamp = 0;

    JobID jobId = new JobID();
    try (OneInputStreamOperatorTestHarness<DataFile, Void> harness = createStreamSink(jobId)) {
      harness.setup();
      harness.open();

      assertMaxCommittedCheckpointId(jobId, -1L);

      RowData row1 = SimpleDataUtil.createRowData(1, "hello");
      DataFile dataFile1 = writeDataFile("data-1", ImmutableList.of(row1));

      harness.processElement(dataFile1, ++timestamp);
      assertMaxCommittedCheckpointId(jobId, -1L);

      // 1. snapshotState for checkpoint#1
      long firstCheckpointId = 1;
      harness.snapshot(firstCheckpointId, ++timestamp);
      assertFlinkManifests(1);

      RowData row2 = SimpleDataUtil.createRowData(2, "world");
      DataFile dataFile2 = writeDataFile("data-2", ImmutableList.of(row2));
      harness.processElement(dataFile2, ++timestamp);
      assertMaxCommittedCheckpointId(jobId, -1L);

      // 2. snapshotState for checkpoint#2
      long secondCheckpointId = 2;
      harness.snapshot(secondCheckpointId, ++timestamp);
      assertFlinkManifests(2);

      // 3. notifyCheckpointComplete for checkpoint#1
      harness.notifyOfCompletedCheckpoint(firstCheckpointId);
      SimpleDataUtil.assertTableRows(tablePath, ImmutableList.of(row1));
      assertMaxCommittedCheckpointId(jobId, firstCheckpointId);
      assertFlinkManifests(1);

      // 4. notifyCheckpointComplete for checkpoint#2
      harness.notifyOfCompletedCheckpoint(secondCheckpointId);
      SimpleDataUtil.assertTableRows(tablePath, ImmutableList.of(row1, row2));
      assertMaxCommittedCheckpointId(jobId, secondCheckpointId);
      assertFlinkManifests(0);
    }
  }

  @Test
  public void testDisorderedEventsBetweenCheckpoints() throws Exception {
    // It's possible that the two checkpoints happen in the following orders:
    //   1. snapshotState for checkpoint#1;
    //   2. snapshotState for checkpoint#2;
    //   3. notifyCheckpointComplete for checkpoint#2;
    //   4. notifyCheckpointComplete for checkpoint#1;
    long timestamp = 0;

    JobID jobId = new JobID();
    try (OneInputStreamOperatorTestHarness<DataFile, Void> harness = createStreamSink(jobId)) {
      harness.setup();
      harness.open();

      assertMaxCommittedCheckpointId(jobId, -1L);

      RowData row1 = SimpleDataUtil.createRowData(1, "hello");
      DataFile dataFile1 = writeDataFile("data-1", ImmutableList.of(row1));

      harness.processElement(dataFile1, ++timestamp);
      assertMaxCommittedCheckpointId(jobId, -1L);

      // 1. snapshotState for checkpoint#1
      long firstCheckpointId = 1;
      harness.snapshot(firstCheckpointId, ++timestamp);
      assertFlinkManifests(1);

      RowData row2 = SimpleDataUtil.createRowData(2, "world");
      DataFile dataFile2 = writeDataFile("data-2", ImmutableList.of(row2));
      harness.processElement(dataFile2, ++timestamp);
      assertMaxCommittedCheckpointId(jobId, -1L);

      // 2. snapshotState for checkpoint#2
      long secondCheckpointId = 2;
      harness.snapshot(secondCheckpointId, ++timestamp);
      assertFlinkManifests(2);

      // 3. notifyCheckpointComplete for checkpoint#2
      harness.notifyOfCompletedCheckpoint(secondCheckpointId);
      SimpleDataUtil.assertTableRows(tablePath, ImmutableList.of(row1, row2));
      assertMaxCommittedCheckpointId(jobId, secondCheckpointId);
      assertFlinkManifests(0);

      // 4. notifyCheckpointComplete for checkpoint#1
      harness.notifyOfCompletedCheckpoint(firstCheckpointId);
      SimpleDataUtil.assertTableRows(tablePath, ImmutableList.of(row1, row2));
      assertMaxCommittedCheckpointId(jobId, secondCheckpointId);
      assertFlinkManifests(0);
    }
  }

  @Test
  public void testRecoveryFromValidSnapshot() throws Exception {
    long checkpointId = 0;
    long timestamp = 0;
    List<RowData> expectedRows = Lists.newArrayList();
    OperatorSubtaskState snapshot;

    JobID jobId = new JobID();
    try (OneInputStreamOperatorTestHarness<DataFile, Void> harness = createStreamSink(jobId)) {
      harness.setup();
      harness.open();

      assertSnapshotSize(0);
      assertMaxCommittedCheckpointId(jobId, -1L);

      RowData row = SimpleDataUtil.createRowData(1, "hello");
      expectedRows.add(row);
      DataFile dataFile1 = writeDataFile("data-1", ImmutableList.of(row));

      harness.processElement(dataFile1, ++timestamp);
      snapshot = harness.snapshot(++checkpointId, ++timestamp);
      assertFlinkManifests(1);

      harness.notifyOfCompletedCheckpoint(checkpointId);
      assertFlinkManifests(0);

      SimpleDataUtil.assertTableRows(tablePath, ImmutableList.of(row));
      assertSnapshotSize(1);
      assertMaxCommittedCheckpointId(jobId, checkpointId);
    }

    // Restore from the given snapshot
    try (OneInputStreamOperatorTestHarness<DataFile, Void> harness = createStreamSink(jobId)) {
      harness.setup();
      harness.initializeState(snapshot);
      harness.open();

      SimpleDataUtil.assertTableRows(tablePath, expectedRows);
      assertSnapshotSize(1);
      assertMaxCommittedCheckpointId(jobId, checkpointId);

      RowData row = SimpleDataUtil.createRowData(2, "world");
      expectedRows.add(row);
      DataFile dataFile = writeDataFile("data-2", ImmutableList.of(row));
      harness.processElement(dataFile, ++timestamp);

      harness.snapshot(++checkpointId, ++timestamp);
      assertFlinkManifests(1);

      harness.notifyOfCompletedCheckpoint(checkpointId);
      assertFlinkManifests(0);

      SimpleDataUtil.assertTableRows(tablePath, expectedRows);
      assertSnapshotSize(2);
      assertMaxCommittedCheckpointId(jobId, checkpointId);
    }
  }

  @Test
  public void testRecoveryFromSnapshotWithoutCompletedNotification() throws Exception {
    // We've two steps in checkpoint: 1. snapshotState(ckp); 2. notifyCheckpointComplete(ckp). It's possible that we
    // flink job will restore from a checkpoint with only step#1 finished.
    long checkpointId = 0;
    long timestamp = 0;
    OperatorSubtaskState snapshot;
    List<RowData> expectedRows = Lists.newArrayList();
    JobID jobId = new JobID();
    try (OneInputStreamOperatorTestHarness<DataFile, Void> harness = createStreamSink(jobId)) {
      harness.setup();
      harness.open();

      assertSnapshotSize(0);
      assertMaxCommittedCheckpointId(jobId, -1L);

      RowData row = SimpleDataUtil.createRowData(1, "hello");
      expectedRows.add(row);
      DataFile dataFile = writeDataFile("data-1", ImmutableList.of(row));
      harness.processElement(dataFile, ++timestamp);

      snapshot = harness.snapshot(++checkpointId, ++timestamp);
      SimpleDataUtil.assertTableRows(tablePath, ImmutableList.of());
      assertMaxCommittedCheckpointId(jobId, -1L);
      assertFlinkManifests(1);
    }

    try (OneInputStreamOperatorTestHarness<DataFile, Void> harness = createStreamSink(jobId)) {
      harness.setup();
      harness.initializeState(snapshot);
      harness.open();

      // All flink manifests should be cleaned because it has committed the unfinished iceberg transaction.
      assertFlinkManifests(0);

      SimpleDataUtil.assertTableRows(tablePath, expectedRows);
      assertMaxCommittedCheckpointId(jobId, checkpointId);

      harness.snapshot(++checkpointId, ++timestamp);
      // Did not write any new record, so it won't generate new manifest.
      assertFlinkManifests(0);

      harness.notifyOfCompletedCheckpoint(checkpointId);
      assertFlinkManifests(0);

      SimpleDataUtil.assertTableRows(tablePath, expectedRows);
      assertSnapshotSize(2);
      assertMaxCommittedCheckpointId(jobId, checkpointId);

      RowData row = SimpleDataUtil.createRowData(2, "world");
      expectedRows.add(row);
      DataFile dataFile = writeDataFile("data-2", ImmutableList.of(row));
      harness.processElement(dataFile, ++timestamp);

      snapshot = harness.snapshot(++checkpointId, ++timestamp);
      assertFlinkManifests(1);
    }

    // Redeploying flink job from external checkpoint.
    JobID newJobId = new JobID();
    try (OneInputStreamOperatorTestHarness<DataFile, Void> harness = createStreamSink(newJobId)) {
      harness.setup();
      harness.initializeState(snapshot);
      harness.open();

      // All flink manifests should be cleaned because it has committed the unfinished iceberg transaction.
      assertFlinkManifests(0);

      assertMaxCommittedCheckpointId(newJobId, -1);
      assertMaxCommittedCheckpointId(jobId, checkpointId);
      SimpleDataUtil.assertTableRows(tablePath, expectedRows);
      assertSnapshotSize(3);

      RowData row = SimpleDataUtil.createRowData(3, "foo");
      expectedRows.add(row);
      DataFile dataFile = writeDataFile("data-3", ImmutableList.of(row));
      harness.processElement(dataFile, ++timestamp);

      harness.snapshot(++checkpointId, ++timestamp);
      assertFlinkManifests(1);

      harness.notifyOfCompletedCheckpoint(checkpointId);
      assertFlinkManifests(0);

      SimpleDataUtil.assertTableRows(tablePath, expectedRows);
      assertSnapshotSize(4);
      assertMaxCommittedCheckpointId(newJobId, checkpointId);
    }
  }

  @Test
  public void testStartAnotherJobToWriteSameTable() throws Exception {
    long checkpointId = 0;
    long timestamp = 0;
    List<RowData> rows = Lists.newArrayList();
    List<RowData> tableRows = Lists.newArrayList();

    JobID oldJobId = new JobID();
    try (OneInputStreamOperatorTestHarness<DataFile, Void> harness = createStreamSink(oldJobId)) {
      harness.setup();
      harness.open();

      assertSnapshotSize(0);
      assertMaxCommittedCheckpointId(oldJobId, -1L);

      for (int i = 1; i <= 3; i++) {
        rows.add(SimpleDataUtil.createRowData(i, "hello" + i));
        tableRows.addAll(rows);

        DataFile dataFile = writeDataFile(String.format("data-%d", i), rows);
        harness.processElement(dataFile, ++timestamp);
        harness.snapshot(++checkpointId, ++timestamp);
        assertFlinkManifests(1);

        harness.notifyOfCompletedCheckpoint(checkpointId);
        assertFlinkManifests(0);

        SimpleDataUtil.assertTableRows(tablePath, tableRows);
        assertSnapshotSize(i);
        assertMaxCommittedCheckpointId(oldJobId, checkpointId);
      }
    }

    // The new started job will start with checkpoint = 1 again.
    checkpointId = 0;
    timestamp = 0;
    JobID newJobId = new JobID();
    try (OneInputStreamOperatorTestHarness<DataFile, Void> harness = createStreamSink(newJobId)) {
      harness.setup();
      harness.open();

      assertSnapshotSize(3);
      assertMaxCommittedCheckpointId(oldJobId, 3);
      assertMaxCommittedCheckpointId(newJobId, -1);

      rows.add(SimpleDataUtil.createRowData(2, "world"));
      tableRows.addAll(rows);

      DataFile dataFile = writeDataFile("data-new-1", rows);
      harness.processElement(dataFile, ++timestamp);
      harness.snapshot(++checkpointId, ++timestamp);
      assertFlinkManifests(1);

      harness.notifyOfCompletedCheckpoint(checkpointId);
      assertFlinkManifests(0);
      SimpleDataUtil.assertTableRows(tablePath, tableRows);
      assertSnapshotSize(4);
      assertMaxCommittedCheckpointId(newJobId, checkpointId);
    }
  }

  @Test
  public void testMultipleJobsWriteSameTable() throws Exception {
    long timestamp = 0;
    List<RowData> tableRows = Lists.newArrayList();

    JobID[] jobs = new JobID[] {new JobID(), new JobID(), new JobID()};
    for (int i = 0; i < 20; i++) {
      int jobIndex = i % 3;
      int checkpointId = i / 3;
      JobID jobId = jobs[jobIndex];
      try (OneInputStreamOperatorTestHarness<DataFile, Void> harness = createStreamSink(jobId)) {
        harness.setup();
        harness.open();

        assertSnapshotSize(i);
        assertMaxCommittedCheckpointId(jobId, checkpointId == 0 ? -1 : checkpointId);

        List<RowData> rows = Lists.newArrayList(SimpleDataUtil.createRowData(i, "word-" + i));
        tableRows.addAll(rows);

        DataFile dataFile = writeDataFile(String.format("data-%d", i), rows);
        harness.processElement(dataFile, ++timestamp);
        harness.snapshot(checkpointId + 1, ++timestamp);
        assertFlinkManifests(1);

        harness.notifyOfCompletedCheckpoint(checkpointId + 1);
        assertFlinkManifests(0);
        SimpleDataUtil.assertTableRows(tablePath, tableRows);
        assertSnapshotSize(i + 1);
        assertMaxCommittedCheckpointId(jobId, checkpointId + 1);
      }
    }
  }

  @Test
  public void testBoundedStream() throws Exception {
    JobID jobId = new JobID();
    try (OneInputStreamOperatorTestHarness<DataFile, Void> harness = createStreamSink(jobId)) {
      harness.setup();
      harness.open();

      assertFlinkManifests(0);
      assertSnapshotSize(0);
      assertMaxCommittedCheckpointId(jobId, -1L);

      List<RowData> tableRows = Lists.newArrayList(SimpleDataUtil.createRowData(1, "word-1"));

      DataFile dataFile = writeDataFile("data-1", tableRows);
      harness.processElement(dataFile, 1);
      ((BoundedOneInput) harness.getOneInputOperator()).endInput();

      assertFlinkManifests(0);
      SimpleDataUtil.assertTableRows(tablePath, tableRows);
      assertSnapshotSize(1);
      assertMaxCommittedCheckpointId(jobId, Long.MAX_VALUE);
    }
  }

  @Test
  public void testFlinkManifests() throws Exception {
    long timestamp = 0;
    final long checkpoint = 10;

    JobID jobId = new JobID();
    try (OneInputStreamOperatorTestHarness<DataFile, Void> harness = createStreamSink(jobId)) {
      harness.setup();
      harness.open();

      assertMaxCommittedCheckpointId(jobId, -1L);

      RowData row1 = SimpleDataUtil.createRowData(1, "hello");
      DataFile dataFile1 = writeDataFile("data-1", ImmutableList.of(row1));

      harness.processElement(dataFile1, ++timestamp);
      assertMaxCommittedCheckpointId(jobId, -1L);

      // 1. snapshotState for checkpoint#1
      harness.snapshot(checkpoint, ++timestamp);
      List<Path> manifestPaths = assertFlinkManifests(1);
      Path manifestPath = manifestPaths.get(0);
      Assert.assertEquals("File name should have the expected pattern.",
          String.format("%s-%05d-%d-%d-%05d.avro", jobId, 0, 0, checkpoint, 1), manifestPath.getFileName().toString());

      // 2. Read the data files from manifests and assert.
      List<DataFile> dataFiles = FlinkManifestUtil.readDataFiles(createTestingManifestFile(manifestPath), table.io());
      Assert.assertEquals(1, dataFiles.size());
      TestFlinkManifest.checkDataFile(dataFile1, dataFiles.get(0));

      // 3. notifyCheckpointComplete for checkpoint#1
      harness.notifyOfCompletedCheckpoint(checkpoint);
      SimpleDataUtil.assertTableRows(tablePath, ImmutableList.of(row1));
      assertMaxCommittedCheckpointId(jobId, checkpoint);
      assertFlinkManifests(0);
    }
  }

  private ManifestFile createTestingManifestFile(Path manifestPath) {
    return new GenericManifestFile(manifestPath.toAbsolutePath().toString(), manifestPath.toFile().length(), 0,
        ManifestContent.DATA, 0, 0, 0L, 0, 0, 0, 0, 0, 0, null);
  }

  private List<Path> assertFlinkManifests(int expectedCount) throws IOException {
    List<Path> manifests = Files.list(flinkManifestFolder.toPath())
        .filter(p -> !p.toString().endsWith(".crc"))
        .collect(Collectors.toList());
    Assert.assertEquals(String.format("Expected %s flink manifests, but the list is: %s", expectedCount, manifests),
        expectedCount, manifests.size());
    return manifests;
  }

  private DataFile writeDataFile(String filename, List<RowData> rows) throws IOException {
    return SimpleDataUtil.writeFile(table.schema(), table.spec(), CONF, tablePath, format.addExtension(filename), rows);
  }

  private void assertMaxCommittedCheckpointId(JobID jobID, long expectedId) {
    table.refresh();
    long actualId = IcebergFilesCommitter.getMaxCommittedCheckpointId(table, jobID.toString());
    Assert.assertEquals(expectedId, actualId);
  }

  private void assertSnapshotSize(int expectedSnapshotSize) {
    table.refresh();
    Assert.assertEquals(expectedSnapshotSize, Lists.newArrayList(table.snapshots()).size());
  }

  private OneInputStreamOperatorTestHarness<DataFile, Void> createStreamSink(JobID jobID)
      throws Exception {
    TestOperatorFactory factory = TestOperatorFactory.of(tablePath);
    return new OneInputStreamOperatorTestHarness<>(factory, createEnvironment(jobID));
  }

  private static MockEnvironment createEnvironment(JobID jobID) {
    return new MockEnvironmentBuilder()
        .setTaskName("test task")
        .setManagedMemorySize(32 * 1024)
        .setInputSplitProvider(new MockInputSplitProvider())
        .setBufferSize(256)
        .setTaskConfiguration(new org.apache.flink.configuration.Configuration())
        .setExecutionConfig(new ExecutionConfig())
        .setMaxParallelism(16)
        .setJobID(jobID)
        .build();
  }

  private static class TestOperatorFactory extends AbstractStreamOperatorFactory<Void>
      implements OneInputStreamOperatorFactory<DataFile, Void> {
    private final String tablePath;

    private TestOperatorFactory(String tablePath) {
      this.tablePath = tablePath;
    }

    private static TestOperatorFactory of(String tablePath) {
      return new TestOperatorFactory(tablePath);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends StreamOperator<Void>> T createStreamOperator(StreamOperatorParameters<Void> param) {
      IcebergFilesCommitter committer = new IcebergFilesCommitter(TableLoader.fromHadoopTable(tablePath), false);
      committer.setup(param.getContainingTask(), param.getStreamConfig(), param.getOutput());
      return (T) committer;
    }

    @Override
    public Class<? extends StreamOperator> getStreamOperatorClass(ClassLoader classLoader) {
      return IcebergFilesCommitter.class;
    }
  }
}
