/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.txn.compactor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.hive.common.ValidTxnList;
import org.apache.hadoop.hive.common.ValidWriteIdList;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.CompactionRequest;
import org.apache.hadoop.hive.metastore.api.CompactionResponse;
import org.apache.hadoop.hive.metastore.api.CompactionType;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.ShowCompactRequest;
import org.apache.hadoop.hive.metastore.api.ShowCompactResponse;
import org.apache.hadoop.hive.metastore.api.ShowCompactResponseElement;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.txn.CompactionInfo;
import org.apache.hadoop.hive.metastore.txn.TxnStore;
import org.apache.hadoop.hive.metastore.txn.TxnUtils;
import org.apache.hadoop.hive.ql.DriverFactory;
import org.apache.hadoop.hive.ql.ErrorMsg;
import org.apache.hadoop.hive.ql.exec.vector.LongColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.apache.hadoop.hive.ql.hooks.HiveProtoLoggingHook;
import org.apache.hadoop.hive.ql.hooks.HiveProtoLoggingHook.ExecutionMode;
import org.apache.hadoop.hive.ql.hooks.TestHiveProtoLoggingHook;
import org.apache.hadoop.hive.ql.hooks.proto.HiveHookEvents;
import org.apache.hadoop.hive.ql.io.AcidDirectory;
import org.apache.hadoop.hive.ql.io.AcidUtils;
import org.apache.hadoop.hive.ql.metadata.Hive;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.processors.CommandProcessorException;
import org.apache.hive.streaming.HiveStreamingConnection;
import org.apache.hive.streaming.StreamingConnection;
import org.apache.hive.streaming.StrictDelimitedInputWriter;
import org.apache.orc.OrcFile;
import org.apache.orc.OrcProto;
import org.apache.orc.Reader;
import org.apache.orc.RecordReader;
import org.apache.orc.StripeInformation;
import org.apache.orc.TypeDescription;
import org.apache.orc.impl.RecordReaderImpl;
import org.apache.tez.dag.history.logging.proto.ProtoMessageReader;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.internal.util.reflection.FieldSetter;

import static org.apache.hadoop.hive.ql.TxnCommandsBaseForTests.runWorker;
import static org.apache.hadoop.hive.ql.txn.compactor.TestCompactor.execSelectAndDumpData;
import static org.apache.hadoop.hive.ql.txn.compactor.TestCompactor.executeStatementOnDriver;
import static org.apache.hadoop.hive.ql.txn.compactor.CompactorTestUtil.executeStatementOnDriverAndReturnResults;
import static org.mockito.Mockito.*;

@SuppressWarnings("deprecation")
public class TestCrudCompactorOnTez extends CompactorOnTezTest {

  @Test
  public void testCompactionShouldNotFailOnPartitionsWithBooleanField() throws Exception {
    conf.setBoolVar(HiveConf.ConfVars.COMPACTOR_CRUD_QUERY_BASED, true);

    final String dbName = "default";
    final String tableName = "compaction_test";
    executeStatementOnDriver("drop table if exists " + tableName, driver);
    executeStatementOnDriver("CREATE TABLE " + tableName + "(id string, value string) PARTITIONED BY (bval boolean) CLUSTERED BY(id) " +
            "INTO 10 BUCKETS STORED AS ORC TBLPROPERTIES('transactional'='true')", driver);

    executeStatementOnDriver("INSERT INTO TABLE " + tableName + " values ('1','one',true),('2','two', true)," +
            "('4','four', false),('5','five', true),('6','six', false),('7','seven', false),('8','eight', false)," +
            "('11','eleven', true),('12','twelve', false),('13','thirteen', false),('14','fourteen', false)," +
            "('17','seventeen', true),('18','eighteen', false),('19','nineteen', false),('20','twenty', true)", driver);

    executeStatementOnDriver("insert into " + tableName + " values ('21', 'value21', false),('84', 'value84', false)", driver);
    executeStatementOnDriver("insert into " + tableName + " values ('22', 'value22', false),('34', 'value34', true)", driver);
    executeStatementOnDriver("insert into " + tableName + " values ('75', 'value75', true),('99', 'value99', true)", driver);

    TxnStore txnHandler = TxnUtils.getTxnStore(conf);

    //Try to do a major compaction directly
    CompactionRequest rqst = new CompactionRequest(dbName, tableName, CompactionType.MAJOR);
    rqst.setPartitionname("bval=true");
    txnHandler.compact(rqst);

    runWorker(conf);

    //Check if the compaction succeed
    ShowCompactResponse rsp = txnHandler.showCompact(new ShowCompactRequest());
    List<ShowCompactResponseElement> compacts = rsp.getCompacts();
    Assert.assertEquals("Expecting 1 rows and found " + compacts.size(), 1, compacts.size());
    Assert.assertEquals("Expecting compaction state 'ready for cleaning' and found:" + compacts.get(0).getState(),
            "ready for cleaning", compacts.get(0).getState());
  }

  @Test
  public void secondCompactionShouldBeRefusedBeforeEnqueueing() throws Exception {
    conf.setBoolVar(HiveConf.ConfVars.COMPACTOR_CRUD_QUERY_BASED, true);

    final String dbName = "default";
    final String tableName = "compaction_test";
    executeStatementOnDriver("drop table if exists " + tableName, driver);
    executeStatementOnDriver("CREATE TABLE " + tableName + "(id string, value string) CLUSTERED BY(id) " +
        "INTO 10 BUCKETS STORED AS ORC TBLPROPERTIES('transactional'='true')", driver);

    executeStatementOnDriver("INSERT INTO TABLE " + tableName + " values ('1','one'),('2','two'),('3','three')," +
        "('4','four'),('5','five'),('6','six'),('7','seven'),('8','eight'),('9','nine'),('10','ten')," +
        "('11','eleven'),('12','twelve'),('13','thirteen'),('14','fourteen'),('15','fifteen'),('16','sixteen')," +
        "('17','seventeen'),('18','eighteen'),('19','nineteen'),('20','twenty')", driver);

    executeStatementOnDriver("insert into " + tableName + " values ('21', 'value21'),('84', 'value84')," +
        "('66', 'value66'),('54', 'value54')", driver);
    executeStatementOnDriver("insert into " + tableName + " values ('22', 'value22'),('34', 'value34')," +
        "('35', 'value35')", driver);
    executeStatementOnDriver("insert into " + tableName + " values ('75', 'value75'),('99', 'value99')", driver);


    TxnStore txnHandler = TxnUtils.getTxnStore(conf);

    //Do a compaction directly and wait for it to finish
    CompactionRequest rqst = new CompactionRequest(dbName, tableName, CompactionType.MAJOR);
    CompactionResponse resp = txnHandler.compact(rqst);
    runWorker(conf);

    //Try to do a second compaction on the same table before the cleaner runs.
    try {
      driver.run("ALTER TABLE " + tableName + " COMPACT 'major'");
    } catch (CommandProcessorException e) {
      String errorMessage = ErrorMsg.COMPACTION_REFUSED.format(dbName, tableName, "",
          "Compaction is already scheduled with state='ready for cleaning' and id=" + resp.getId());
      Assert.assertEquals(errorMessage, e.getCauseMessage());
      Assert.assertEquals(ErrorMsg.COMPACTION_REFUSED.getErrorCode(), e.getErrorCode());
    }

    //Check if the first compaction is in 'ready for cleaning'
    ShowCompactResponse rsp = txnHandler.showCompact(new ShowCompactRequest());
    List<ShowCompactResponseElement> compacts = rsp.getCompacts();
    Assert.assertEquals(1, compacts.size());
    Assert.assertEquals("ready for cleaning", compacts.get(0).getState());
  }

  @Test
  public void testMinorCompactionShouldBeRefusedOnTablesWithOriginalFiles() throws Exception {
    conf.setBoolVar(HiveConf.ConfVars.COMPACTOR_CRUD_QUERY_BASED, true);
    // Set delta numbuer threshold to 2 to avoid skipping compaction because of too few deltas
    conf.setIntVar(HiveConf.ConfVars.HIVE_COMPACTOR_DELTA_NUM_THRESHOLD, 2);
    // Set delta percentage to a high value to suppress selecting major compression based on that
    conf.setFloatVar(HiveConf.ConfVars.HIVE_COMPACTOR_DELTA_PCT_THRESHOLD, 1000f);

    final String dbName = "default";
    final String tableName = "compaction_test";
    executeStatementOnDriver("drop table if exists " + tableName, driver);
    executeStatementOnDriver("CREATE TABLE " + tableName + "(id string, value string) CLUSTERED BY(id) " +
            "INTO 10 BUCKETS STORED AS ORC TBLPROPERTIES('transactional'='false')", driver);

    executeStatementOnDriver("INSERT INTO TABLE " + tableName + " values ('1','one'),('2','two'),('3','three')," +
            "('4','four'),('5','five'),('6','six'),('7','seven'),('8','eight'),('9','nine'),('10','ten')," +
            "('11','eleven'),('12','twelve'),('13','thirteen'),('14','fourteen'),('15','fifteen'),('16','sixteen')," +
            "('17','seventeen'),('18','eighteen'),('19','nineteen'),('20','twenty')", driver);

    executeStatementOnDriver("alter table " + tableName + " set TBLPROPERTIES('transactional'='true')", driver);

    executeStatementOnDriver("insert into " + tableName + " values ('21', 'value21'),('84', 'value84')," +
            "('66', 'value66'),('54', 'value54')", driver);
    executeStatementOnDriver("insert into " + tableName + " values ('22', 'value22'),('34', 'value34')," +
            "('35', 'value35')", driver);
    executeStatementOnDriver("insert into " + tableName + " values ('75', 'value75'),('99', 'value99')", driver);

    execSelectAndDumpData("select * from " + tableName, driver, "Dumping data for " +
            tableName + " after load:");

    TxnStore txnHandler = TxnUtils.getTxnStore(conf);

    //Prevent initiator from submitting the compaction requests
    TxnStore mockedHandler = spy(txnHandler);
    doThrow(new RuntimeException("")).when(mockedHandler).compact(nullable(CompactionRequest.class));
    Initiator initiator = new Initiator();
    initiator.setConf(conf);
    initiator.init(new AtomicBoolean(true));
    FieldSetter.setField(initiator, MetaStoreCompactorThread.class.getDeclaredField("txnHandler"), mockedHandler);

    //Run initiator and capture compaction requests
    initiator.run();

    //Check captured compaction request and if the type for the table was MAJOR
    ArgumentCaptor<CompactionRequest> requests = ArgumentCaptor.forClass(CompactionRequest.class);
    verify(mockedHandler).compact(requests.capture());
    Assert.assertTrue(requests.getAllValues().stream().anyMatch(r -> r.getTablename().equals(tableName) && r.getType().equals(CompactionType.MAJOR)));

    //Try to do a minor compaction directly
    CompactionRequest rqst = new CompactionRequest(dbName, tableName, CompactionType.MINOR);
    txnHandler.compact(rqst);

    runWorker(conf);

    //Check if both compactions were failed with the expected error message
    ShowCompactResponse rsp = txnHandler.showCompact(new ShowCompactRequest());
    List<ShowCompactResponseElement> compacts = rsp.getCompacts();
    if (2 != compacts.size()) {
      Assert.fail("Expecting 2 rows and found " + compacts.size() + " files " + compacts);
    }
    Assert.assertEquals("refused", compacts.get(0).getState());
    Assert.assertTrue(compacts.get(0).getErrorMessage()
            .startsWith("Query based Minor compaction is not possible for full acid tables having raw format (non-acid) data in them."));
    Assert.assertEquals("did not initiate", compacts.get(1).getState());
    Assert.assertTrue(compacts.get(1).getErrorMessage()
            .startsWith("Caught exception while trying to determine if we should compact "));
  }

  @Test
  public void testMinorCompactionShouldBeRefusedOnTablesWithRawData() throws Exception {
    conf.setBoolVar(HiveConf.ConfVars.COMPACTOR_CRUD_QUERY_BASED, true);
    // Set delta numbuer threshold to 2 to avoid skipping compaction because of too few deltas
    conf.setIntVar(HiveConf.ConfVars.HIVE_COMPACTOR_DELTA_NUM_THRESHOLD, 2);
    // Set delta percentage to a high value to suppress selecting major compression based on that
    conf.setFloatVar(HiveConf.ConfVars.HIVE_COMPACTOR_DELTA_PCT_THRESHOLD, 1000f);

    TxnStore txnHandler = TxnUtils.getTxnStore(conf);

    final String dbName = "default";
    final String origTableName = "compaction_test";
    final String testTableName = "imported";
    executeStatementOnDriver("drop table if exists " + origTableName, driver);
    executeStatementOnDriver("drop table if exists " + testTableName, driver);
    executeStatementOnDriver("CREATE TABLE " + origTableName + "(id string, value string) CLUSTERED BY(id) " +
            "INTO 10 BUCKETS STORED AS ORC TBLPROPERTIES('transactional'='true')", driver);

    executeStatementOnDriver("INSERT INTO TABLE " + origTableName + " values ('1','one'),('2','two'),('3','three')," +
            "('4','four'),('5','five'),('6','six'),('7','seven'),('8','eight'),('9','nine'),('10','ten')," +
            "('11','eleven'),('12','twelve'),('13','thirteen'),('14','fourteen'),('15','fifteen'),('16','sixteen')," +
            "('17','seventeen'),('18','eighteen'),('19','nineteen'),('20','twenty')", driver);

    execSelectAndDumpData("select * from " + origTableName, driver, "Dumping data for " +
            origTableName + " after load:");

    executeStatementOnDriver("export table " + origTableName + " to '/tmp/temp_acid'", driver);
    executeStatementOnDriver("import table " + testTableName + " from '/tmp/temp_acid'", driver);
    executeStatementOnDriver("insert into " + testTableName + " values ('21', 'value21'),('84', 'value84')," +
            "('66', 'value66'),('54', 'value54')", driver);
    executeStatementOnDriver("insert into " + testTableName + " values ('22', 'value22'),('34', 'value34')," +
            "('35', 'value35')", driver);
    executeStatementOnDriver("insert into " + testTableName + " values ('75', 'value75'),('99', 'value99')", driver);

    //Prevent initiator from submitting the compaction requests
    TxnStore mockedHandler = spy(txnHandler);
    doThrow(new RuntimeException("")).when(mockedHandler).compact(nullable(CompactionRequest.class));
    Initiator initiator = new Initiator();
    initiator.setConf(conf);
    initiator.init(new AtomicBoolean(true));
    FieldSetter.setField(initiator, MetaStoreCompactorThread.class.getDeclaredField("txnHandler"), mockedHandler);

    //Run initiator and capture compaction requests
    initiator.run();

    //Check captured compaction request and if the type for the table was MAJOR
    ArgumentCaptor<CompactionRequest> requests = ArgumentCaptor.forClass(CompactionRequest.class);
    verify(mockedHandler).compact(requests.capture());
    Assert.assertTrue(requests.getAllValues().stream().anyMatch(r -> r.getTablename().equals(testTableName) && r.getType().equals(CompactionType.MAJOR)));

    //Try to do a minor compaction directly
    CompactionRequest rqst = new CompactionRequest(dbName, testTableName, CompactionType.MINOR);
    txnHandler.compact(rqst);

    runWorker(conf);

    //Check if both compactions were failed with the expected error message
    ShowCompactResponse rsp = txnHandler.showCompact(new ShowCompactRequest());
    List<ShowCompactResponseElement> compacts = rsp.getCompacts();
    if (2 != compacts.size()) {
      Assert.fail("Expecting 2 rows and found " + compacts.size() + " files " + compacts);
    }
    Assert.assertEquals("refused", compacts.get(0).getState());
    Assert.assertTrue(compacts.get(0).getErrorMessage().startsWith("Query based Minor compaction is not possible for full acid tables having raw format (non-acid) data in them."));
    Assert.assertEquals("did not initiate", compacts.get(1).getState());
    Assert.assertTrue(compacts.get(1).getErrorMessage().startsWith("Caught exception while trying to determine if we should compact"));
  }

  /**
   * After each major compaction, stats need to be updated on the table
   * 1. create an ORC backed table (Orc is currently required by ACID)
   * 2. populate with data
   * 3. compute stats
   * 4. Trigger major compaction (which should update stats)
   * 5. check that stats have been updated
   */
  @Test
  public void testStatsAfterQueryCompactionOnTez() throws Exception {
    //as of (8/27/2014) Hive 0.14, ACID/Orc requires HiveInputFormat
    String dbName = "default";
    String tblName = "compaction_test";
    executeStatementOnDriver("drop table if exists " + tblName, driver);
    executeStatementOnDriver("CREATE TABLE " + tblName + "(a INT, b STRING) " +
            " CLUSTERED BY(a) INTO 4 BUCKETS" + //currently ACID requires table to be bucketed
            " STORED AS ORC  TBLPROPERTIES ('transactional'='true')", driver);
    executeStatementOnDriver("INSERT INTO TABLE " + tblName +
            " values(55, 'London')", driver);
    executeStatementOnDriver("INSERT INTO TABLE " + tblName +
            " values(56, 'Paris')", driver);
    execSelectAndDumpData("select * from " + tblName, driver, "Dumping data for " +
            tblName + " after load:");

    TxnStore txnHandler = TxnUtils.getTxnStore(conf);
    Table table = msClient.getTable(dbName, tblName);

    //compute stats before compaction
    CompactionInfo ci = new CompactionInfo(dbName, tblName, null, CompactionType.MAJOR);
    new StatsUpdater().gatherStats(ci, conf,
            System.getProperty("user.name"), CompactorUtil.getCompactorJobQueueName(conf, ci, table));

    //Check basic stats are collected
    Map<String, String> parameters = Hive.get().getTable(tblName).getParameters();
    Assert.assertEquals("The number of files is differing from the expected", "2", parameters.get("numFiles"));
    Assert.assertEquals("The number of rows is differing from the expected", "2", parameters.get("numRows"));
    Assert.assertEquals("The total table size is differing from the expected", "1434", parameters.get("totalSize"));

    //Do a major compaction
    CompactorTestUtil.runCompaction(conf, dbName, tblName, CompactionType.MAJOR, true);

    ShowCompactResponse rsp = txnHandler.showCompact(new ShowCompactRequest());
    List<ShowCompactResponseElement> compacts = rsp.getCompacts();
    if (1 != compacts.size()) {
      Assert.fail("Expecting 1 file and found " + compacts.size() + " files " + compacts);
    }
    Assert.assertEquals("ready for cleaning", compacts.get(0).getState());

    //Check basic stats are updated
    parameters = Hive.get().getTable(tblName).getParameters();
    Assert.assertEquals("The number of files is differing from the expected", "1", parameters.get("numFiles"));
    Assert.assertEquals("The number of rows is differing from the expected", "2", parameters.get("numRows"));
    Assert.assertEquals("The total table size is differing from the expected", "727", parameters.get("totalSize"));
  }

  @Test
  public void testMajorCompactionNotPartitionedWithoutBuckets() throws Exception {
    boolean originalEnableVersionFile = conf.getBoolVar(HiveConf.ConfVars.HIVE_WRITE_ACID_VERSION_FILE);
    conf.setBoolVar(HiveConf.ConfVars.HIVE_WRITE_ACID_VERSION_FILE, true);

    conf.setVar(HiveConf.ConfVars.COMPACTOR_JOB_QUEUE, CUSTOM_COMPACTION_QUEUE);
    conf.setVar(HiveConf.ConfVars.HIVE_PROTO_EVENTS_BASE_PATH, tmpFolder);

    String dbName = "default";
    String tblName = "testMajorCompaction";
    String dbTableName = dbName + "." + tblName;
    TestDataProvider testDataProvider = new TestDataProvider();
    testDataProvider.createFullAcidTable(tblName, false, false);
    testDataProvider.insertTestData(tblName);
    // Find the location of the table
    IMetaStoreClient msClient = new HiveMetaStoreClient(conf);
    Table table = msClient.getTable(dbName, tblName);
    FileSystem fs = FileSystem.get(conf);
    // Verify deltas (delta_0000001_0000001_0000, delta_0000002_0000002_0000) are present
    Assert.assertEquals("Delta directories does not match before compaction",
        Arrays.asList("delta_0000001_0000001_0000", "delta_0000002_0000002_0000",
            "delta_0000004_0000004_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null));
    // Verify that delete delta (delete_delta_0000003_0000003_0000) is present
    Assert.assertEquals("Delete directories does not match",
        Arrays.asList("delete_delta_0000003_0000003_0000", "delete_delta_0000005_0000005_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null));

    List<String> expectedRsBucket0 = new ArrayList<>(Arrays.asList(
        "{\"writeid\":1,\"bucketid\":536870912,\"rowid\":4}\t2\t3",
        "{\"writeid\":1,\"bucketid\":536870912,\"rowid\":5}\t2\t4",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":1}\t3\t3",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":2}\t3\t4",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":4}\t4\t3",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":5}\t4\t4",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":0}\t5\t2",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":1}\t5\t3",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":2}\t5\t4",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":3}\t6\t2",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":4}\t6\t3",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":5}\t6\t4"));
    // Check bucket contents
    Assert.assertEquals("pre-compaction bucket 0", expectedRsBucket0,
        testDataProvider.getBucketData(tblName, "536870912"));

    conf.setVar(HiveConf.ConfVars.PREEXECHOOKS, HiveProtoLoggingHook.class.getName());
    // Run major compaction and cleaner
    CompactorTestUtil.runCompaction(conf, dbName, tblName, CompactionType.MAJOR, true);
    conf.setVar(HiveConf.ConfVars.PREEXECHOOKS, StringUtils.EMPTY);

    CompactorTestUtil.runCleaner(conf);
    verifySuccessfulCompaction(1);
    // Should contain only one base directory now
    String expectedBase = "base_0000005_v0000009";
    Assert.assertEquals("Base directory does not match after major compaction",
        Collections.singletonList(expectedBase),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, null));
    // Check base dir contents
    List<String> expectedBucketFiles = Arrays.asList("bucket_00000");
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil
            .getBucketFileNames(fs, table, null, expectedBase));
    // Check bucket contents
    Assert.assertEquals("post-compaction bucket 0", expectedRsBucket0,
        testDataProvider.getBucketData(tblName, "536870912"));
    // Check bucket file contents
    checkBucketIdAndRowIdInAcidFile(fs, new Path(table.getSd().getLocation(), expectedBase), 0);

    CompactorTestUtilities.checkAcidVersion(fs.listFiles(new Path(table.getSd().getLocation()), true), fs, true,
        new String[] { AcidUtils.BASE_PREFIX});
    conf.setBoolVar(HiveConf.ConfVars.HIVE_WRITE_ACID_VERSION_FILE, originalEnableVersionFile);

    HiveHookEvents.HiveHookEventProto event = getRelatedTezEvent(dbTableName);
    Assert.assertNotNull(event);
    Assert.assertEquals(event.getQueue(), CUSTOM_COMPACTION_QUEUE);
  }

  /**
   * Query based compaction should respect the orc.bloom.filter properties
   * @throws Exception
   */
  @Test
  public void testMajorCompactionWithBloomFilter() throws Exception {

    String dbName = "default";
    String tblName = "testMajorCompaction";
    TestDataProvider testDataProvider = new TestDataProvider();
    Map<String, String> additionalTblProperties = new HashMap<>();
    additionalTblProperties.put("orc.bloom.filter.columns", "b");
    additionalTblProperties.put("orc.bloom.filter.fpp", "0.02");
    testDataProvider.createFullAcidTable(dbName, tblName, false, false, additionalTblProperties);
    testDataProvider.insertTestData(tblName);
    // Find the location of the table
    IMetaStoreClient msClient = new HiveMetaStoreClient(conf);
    Table table = msClient.getTable(dbName, tblName);
    FileSystem fs = FileSystem.get(conf);
    // Verify deltas are present
    Assert.assertEquals("Delta directories does not match before compaction",
        Arrays.asList("delta_0000001_0000001_0000", "delta_0000002_0000002_0000",
            "delta_0000004_0000004_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null));
    // Check bucket file contains the bloomFilter
    checkBloomFilterInAcidFile(fs, new Path(table.getSd().getLocation(), "delta_0000001_0000001_0000/bucket_00000_0"));

    // Run major compaction and cleaner
    CompactorTestUtil.runCompaction(conf, dbName, tblName, CompactionType.MAJOR, true);
    CompactorTestUtil.runCleaner(conf);
    verifySuccessfulCompaction(1);
    // Should contain only one base directory now
    String expectedBase = "base_0000005_v0000008";
    Assert.assertEquals("Base directory does not match after major compaction",
        Collections.singletonList(expectedBase),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, null));
    // Check base dir contents
    List<String> expectedBucketFiles = Arrays.asList("bucket_00000");
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil
            .getBucketFileNames(fs, table, null, expectedBase));
    // Check bucket file contents
    checkBucketIdAndRowIdInAcidFile(fs, new Path(table.getSd().getLocation(), expectedBase), 0);

    checkBloomFilterInAcidFile(fs, new Path(table.getSd().getLocation(), expectedBase + "/bucket_00000"));
  }

  /**
   * TestDataProvider uses 2 buckets, I want to test 4 buckets here.
   * @throws Exception
   */
  @Test
  public void testMajorCompactionNotPartitioned4Buckets() throws Exception {
    boolean originalEnableVersionFile = conf.getBoolVar(HiveConf.ConfVars.HIVE_WRITE_ACID_VERSION_FILE);
    conf.setBoolVar(HiveConf.ConfVars.HIVE_WRITE_ACID_VERSION_FILE, false);

    String dbName = "default";
    String tblName = "testMajorCompaction";
    executeStatementOnDriver("drop table if exists " + tblName, driver);
    executeStatementOnDriver("create transactional table " + tblName + " (a int, b int) clustered"
        + " by (a) into 4 buckets"
        + " stored as ORC TBLPROPERTIES('bucketing_version'='2', 'transactional'='true',"
        + " 'transactional_properties'='default')", driver);
    executeStatementOnDriver("insert into " + tblName + " values(1,2),(1,3),(1,4),(2,2),(2,3),(2,4)", driver);
    executeStatementOnDriver("insert into " + tblName + " values(3,2),(3,3),(3,4),(4,2),(4,3),(4,4)", driver);
    executeStatementOnDriver("delete from " + tblName + " where b = 2", driver);
    // Find the location of the table
    IMetaStoreClient msClient = new HiveMetaStoreClient(conf);

    Table table = msClient.getTable(dbName, tblName);
    FileSystem fs = FileSystem.get(conf);
    // Verify deltas (delta_0000001_0000001_0000, delta_0000002_0000002_0000) are present
    Assert.assertEquals("Delta directories does not match before compaction",
        Arrays.asList("delta_0000001_0000001_0000", "delta_0000002_0000002_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null));
    // Verify that delete delta (delete_delta_0000003_0000003_0000) is present
    Assert.assertEquals("Delete directories does not match",
        Arrays.asList("delete_delta_0000003_0000003_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null));
    List<String> expectedRsBucket0 = new ArrayList<>(Arrays.asList(
        "{\"writeid\":1,\"bucketid\":536870912,\"rowid\":1}\t2\t3",
        "{\"writeid\":1,\"bucketid\":536870912,\"rowid\":2}\t2\t4"
    ));
    List<String> expectedRsBucket1 = new ArrayList<>(Arrays.asList(
        "{\"writeid\":1,\"bucketid\":536936448,\"rowid\":1}\t1\t3",
        "{\"writeid\":1,\"bucketid\":536936448,\"rowid\":2}\t1\t4",
        "{\"writeid\":2,\"bucketid\":536936448,\"rowid\":1}\t4\t3",
        "{\"writeid\":2,\"bucketid\":536936448,\"rowid\":2}\t4\t4"
    ));
    List<String> expectedRsBucket2 = new ArrayList<>(Arrays.asList(
        "{\"writeid\":2,\"bucketid\":537001984,\"rowid\":1}\t3\t3",
        "{\"writeid\":2,\"bucketid\":537001984,\"rowid\":2}\t3\t4"
    ));
    TestDataProvider testDataProvider = new TestDataProvider();
    List<String> preCompactionRsBucket0 = testDataProvider.getBucketData(tblName, "536870912");
    List<String> preCompactionRsBucket1 = testDataProvider.getBucketData(tblName, "536936448");
    List<String> preCompactionRsBucket2 = testDataProvider.getBucketData(tblName, "537001984");
    Assert.assertEquals("pre-compaction bucket 0", expectedRsBucket0, preCompactionRsBucket0);
    Assert.assertEquals("pre-compaction bucket 1", expectedRsBucket1, preCompactionRsBucket1);
    Assert.assertEquals("pre-compaction bucket 2", expectedRsBucket2, preCompactionRsBucket2);

    // Run major compaction and cleaner
    CompactorTestUtil.runCompaction(conf, dbName, tblName, CompactionType.MAJOR, true);
    CompactorTestUtil.runCleaner(conf);
    verifySuccessfulCompaction(1);
    // Should contain only one base directory now
    String expectedBase = "base_0000003_v0000009";
    Assert.assertEquals("Base directory does not match after major compaction",
        Collections.singletonList(expectedBase),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, null));
    // Check files in base
    List<String> expectedBucketFiles = Arrays.asList("bucket_00000", "bucket_00001", "bucket_00002");
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil
            .getBucketFileNames(fs, table, null, "base_0000003_v0000009"));
    // Check buckets contents
    Assert.assertEquals("post-compaction bucket 0", expectedRsBucket0, testDataProvider.getBucketData(tblName,
      "536870912"));
    Assert.assertEquals("post-compaction bucket 1", expectedRsBucket1, testDataProvider.getBucketData(tblName,
      "536936448"));
    Assert.assertEquals("post-compaction bucket 2", expectedRsBucket2, testDataProvider.getBucketData(tblName,
      "537001984"));
    // Check bucket file contents
    checkBucketIdAndRowIdInAcidFile(fs, new Path(table.getSd().getLocation(), expectedBase), 0);
    checkBucketIdAndRowIdInAcidFile(fs, new Path(table.getSd().getLocation(), expectedBase), 1);
    checkBucketIdAndRowIdInAcidFile(fs, new Path(table.getSd().getLocation(), expectedBase), 2);

    CompactorTestUtilities.checkAcidVersion(fs.listFiles(new Path(table.getSd().getLocation()), true), fs, false,
        new String[] { AcidUtils.BASE_PREFIX});
    conf.setBoolVar(HiveConf.ConfVars.HIVE_WRITE_ACID_VERSION_FILE, originalEnableVersionFile);
  }

  @Test
  public void testMajorCompactionPartitionedWithoutBuckets() throws Exception {
    String dbName = "default";
    String tblName = "testMajorCompaction";
    TestDataProvider testDataProvider = new TestDataProvider();
    testDataProvider.createFullAcidTable(tblName, true, false);
    testDataProvider.insertTestDataPartitioned(tblName);
    // Find the location of the table
    IMetaStoreClient msClient = new HiveMetaStoreClient(conf);
    Table table = msClient.getTable(dbName, tblName);
    String tablePath = table.getSd().getLocation();
    String partitionToday = "ds=today";
    String partitionTomorrow = "ds=tomorrow";
    String partitionYesterday = "ds=yesterday";
    Path todayPath = new Path(tablePath, partitionToday);
    Path tomorrowPath = new Path(tablePath, partitionTomorrow);
    Path yesterdayPath = new Path(tablePath, partitionYesterday);
    FileSystem fs = FileSystem.get(conf);
    // Verify deltas
    Assert.assertEquals("Delta directories does not match",
        Arrays.asList("delta_0000001_0000001_0000", "delta_0000002_0000002_0000", "delta_0000004_0000004_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, partitionToday));
    // Verify delete delta
    Assert.assertEquals("Delete directories does not match",
        Arrays.asList("delete_delta_0000003_0000003_0000", "delete_delta_0000005_0000005_0000"),
        CompactorTestUtil
            .getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, partitionToday));

    List<String> expectedRsBucket0 = new ArrayList<>(Arrays.asList(
        "{\"writeid\":1,\"bucketid\":536870912,\"rowid\":1}\t2\t3\tyesterday",
        "{\"writeid\":1,\"bucketid\":536870912,\"rowid\":2}\t2\t4\ttoday",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":0}\t3\t3\ttoday",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":0}\t3\t4\tyesterday",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":1}\t4\t3\ttomorrow",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":2}\t4\t4\ttoday",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":0}\t5\t2\tyesterday",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":0}\t5\t4\ttoday",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":1}\t5\t3\tyesterday",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":1}\t6\t2\ttoday",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":2}\t6\t3\ttoday",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":3}\t6\t4\ttoday"));
    Assert.assertEquals("pre-compaction bucket 0", expectedRsBucket0,
        testDataProvider.getBucketData(tblName, "536870912"));

    // Run major compaction and cleaner for all 3 partitions
    CompactorTestUtil.runCompaction(conf, dbName, tblName, CompactionType.MAJOR, true,
        partitionToday, partitionTomorrow, partitionYesterday);
    CompactorTestUtil.runCleaner(conf);
    // 3 compaction should be in the response queue with succeeded state
    verifySuccessfulCompaction( 3);
    // Should contain only one base directory now
    Assert.assertEquals("Base directory does not match after major compaction",
        Collections.singletonList("base_0000005_v0000009"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, partitionToday));
    Assert.assertEquals("Base directory does not match after major compaction",
        Collections.singletonList("base_0000005_v0000014"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, partitionTomorrow));
    Assert.assertEquals("Base directory does not match after major compaction",
        Collections.singletonList("base_0000005_v0000019"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, partitionYesterday));
    // Check base dir contents
    List<String> expectedBucketFiles = Arrays.asList("bucket_00000");
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil
            .getBucketFileNames(fs, table, partitionToday, "base_0000005_v0000009"));
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil
            .getBucketFileNames(fs, table, partitionTomorrow, "base_0000005_v0000014"));
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil
            .getBucketFileNames(fs, table, partitionYesterday, "base_0000005_v0000019"));
    // Check buckets contents
    Assert.assertEquals("post-compaction bucket 0", expectedRsBucket0,
        testDataProvider.getBucketData(tblName, "536870912"));
    // Check bucket file contents
    checkBucketIdAndRowIdInAcidFile(fs, new Path(todayPath, "base_0000005_v0000009"), 0);
    checkBucketIdAndRowIdInAcidFile(fs, new Path(tomorrowPath, "base_0000005_v0000014"), 0);
    checkBucketIdAndRowIdInAcidFile(fs, new Path(yesterdayPath, "base_0000005_v0000019"), 0);

    CompactorTestUtilities.checkAcidVersion(fs.listFiles(new Path(table.getSd().getLocation()), true), fs,
        conf.getBoolVar(HiveConf.ConfVars.HIVE_WRITE_ACID_VERSION_FILE), new String[] { AcidUtils.BASE_PREFIX});
  }

  @Test public void testMajorCompactionPartitionedWithBuckets() throws Exception {
    String dbName = "default";
    String tableName = "testMajorCompaction";
    // Create test table
    TestDataProvider dataProvider = new TestDataProvider();
    dataProvider.createFullAcidTable(tableName, true, true);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    // Insert test data into test table
    dataProvider.insertTestDataPartitioned(tableName);
    // Get all data before compaction is run
    List<String> expectedData = dataProvider.getAllData(tableName);
    // Verify deltas
    String partitionToday = "ds=today";
    String partitionTomorrow = "ds=tomorrow";
    String partitionYesterday = "ds=yesterday";
    Assert.assertEquals("Delta directories does not match",
        Arrays.asList("delta_0000001_0000001_0000", "delta_0000002_0000002_0000", "delta_0000004_0000004_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, partitionToday));
    // Verify delete delta
    Assert.assertEquals("Delete directories does not match",
        Arrays.asList("delete_delta_0000003_0000003_0000", "delete_delta_0000005_0000005_0000"),
        CompactorTestUtil
            .getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, partitionToday));
    // Check bucket contents
    List<String> expectedRsBucket0 = Arrays.asList(
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":0}\t3\t3\ttoday",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":0}\t3\t4\tyesterday");
    List<String> rsBucket0 = dataProvider.getBucketData(tableName, "536870912");

    List<String> expectedRsBucket1 = Arrays.asList(
        "{\"writeid\":1,\"bucketid\":536936448,\"rowid\":0}\t2\t3\tyesterday",
        "{\"writeid\":1,\"bucketid\":536936448,\"rowid\":0}\t2\t4\ttoday",
        "{\"writeid\":2,\"bucketid\":536936448,\"rowid\":0}\t4\t3\ttomorrow",
        "{\"writeid\":2,\"bucketid\":536936448,\"rowid\":1}\t4\t4\ttoday",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":0}\t5\t2\tyesterday",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":0}\t5\t4\ttoday",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":1}\t5\t3\tyesterday",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":1}\t6\t2\ttoday",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":2}\t6\t3\ttoday",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":3}\t6\t4\ttoday");
    List<String> rsBucket1 = dataProvider.getBucketData(tableName, "536936448");
    Assert.assertEquals(expectedRsBucket0, rsBucket0);
    Assert.assertEquals(expectedRsBucket1, rsBucket1);

    // Run a compaction
    CompactorTestUtil
        .runCompaction(conf, dbName, tableName, CompactionType.MAJOR, true, partitionToday,
            partitionTomorrow,
            partitionYesterday);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // 3 compactions should be in the response queue with succeeded state
    verifySuccessfulCompaction( 3);
    // Verify base directories after compaction in each partition
    String expectedBaseToday = "base_0000005_v0000011";
    String expectedBaseTomorrow = "base_0000005_v0000016";
    String expectedBaseYesterday = "base_0000005_v0000021";
    List<String> baseDeltasInToday =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, partitionToday);
    Assert.assertEquals("Delta directories does not match after compaction",
        Collections.singletonList(expectedBaseToday), baseDeltasInToday);
    List<String> baseDeltasInTomorrow =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, partitionTomorrow);

    Assert.assertEquals("Delta directories does not match after compaction",
        Collections.singletonList(expectedBaseTomorrow), baseDeltasInTomorrow);
    List<String> baseDeltasInYesterday =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, partitionYesterday);
    Assert.assertEquals("Delta directories does not match after compaction",
        Collections.singletonList(expectedBaseYesterday), baseDeltasInYesterday);
    // Verify contents of bases
    Assert.assertEquals("Bucket names are not matching after compaction", Arrays.asList("bucket_00000", "bucket_00001"),
        CompactorTestUtil
            .getBucketFileNames(fs, table, partitionToday, expectedBaseToday));
    Assert.assertEquals("Bucket names are not matching after compaction", Arrays.asList("bucket_00001"),
        CompactorTestUtil
            .getBucketFileNames(fs, table, partitionTomorrow, expectedBaseTomorrow));
    Assert.assertEquals("Bucket names are not matching after compaction", Arrays.asList("bucket_00000", "bucket_00001"),
        CompactorTestUtil
            .getBucketFileNames(fs, table, partitionYesterday, expectedBaseYesterday));
    // Verify contents of bucket files.
    // Bucket 0
    rsBucket0 = dataProvider.getBucketData(tableName, "536870912");
    Assert.assertEquals(expectedRsBucket0, rsBucket0);
    // Bucket 1
    rsBucket1 = dataProvider.getBucketData(tableName, "536936448");
    Assert.assertEquals(expectedRsBucket1, rsBucket1);
    // Verify all contents
    List<String> actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);
    String tablePath = table.getSd().getLocation();
    checkBucketIdAndRowIdInAcidFile(fs, new Path(new Path(tablePath, partitionToday), expectedBaseToday), 0);
    checkBucketIdAndRowIdInAcidFile(fs, new Path(new Path(tablePath, partitionToday), expectedBaseToday), 1);
    checkBucketIdAndRowIdInAcidFile(fs, new Path(new Path(tablePath, partitionTomorrow), expectedBaseTomorrow), 1);
    checkBucketIdAndRowIdInAcidFile(fs, new Path(new Path(tablePath, partitionYesterday), expectedBaseYesterday), 0);
    checkBucketIdAndRowIdInAcidFile(fs, new Path(new Path(tablePath, partitionYesterday), expectedBaseYesterday), 1);

    CompactorTestUtilities.checkAcidVersion(fs.listFiles(new Path(table.getSd().getLocation()), true), fs,
        conf.getBoolVar(HiveConf.ConfVars.HIVE_WRITE_ACID_VERSION_FILE), new String[] { AcidUtils.BASE_PREFIX});
  }

  @Test
  public void testMinorCompactionNotPartitionedWithoutBuckets() throws Exception {
    String dbName = "default";
    String tableName = "testMinorCompaction";
    // Create test table
    TestDataProvider dataProvider = new TestDataProvider();
    dataProvider.createFullAcidTable(tableName, false, false);
    // Find the location of the table
    IMetaStoreClient msClient = new HiveMetaStoreClient(conf);
    Table table = msClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    // Insert test data into test table
    dataProvider.insertTestData(tableName);
    // Get all data before compaction is run
    List<String> expectedData = dataProvider.getAllData(tableName);
    // Verify deltas
    Assert.assertEquals("Delta directories does not match",
        Arrays.asList("delta_0000001_0000001_0000", "delta_0000002_0000002_0000", "delta_0000004_0000004_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null));
    // Verify delete delta
    Assert.assertEquals("Delete directories does not match",
        Arrays.asList("delete_delta_0000003_0000003_0000", "delete_delta_0000005_0000005_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null));
    // Run a compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // Only 1 compaction should be in the response queue with succeeded state
    verifySuccessfulCompaction(1);
    // Verify delta directories after compaction
    List<String> actualDeltasAfterComp =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null);
    Assert.assertEquals("Delta directories does not match after compaction",
        Collections.singletonList("delta_0000001_0000005_v0000009"), actualDeltasAfterComp);
    List<String> actualDeleteDeltasAfterComp =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null);
    Assert.assertEquals("Delete delta directories does not match after compaction",
        Collections.singletonList("delete_delta_0000001_0000005_v0000009"), actualDeleteDeltasAfterComp);
    // Verify bucket files in delta dirs
    List<String> expectedBucketFiles = Collections.singletonList("bucket_00000");
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil.getBucketFileNames(fs, table, null, actualDeltasAfterComp.get(0)));
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil.getBucketFileNames(fs, table, null, actualDeleteDeltasAfterComp.get(0)));
    // Verify contents of bucket files.
    // Bucket 0
    List<String> expectedRsBucket0 = Arrays.asList(
        "{\"writeid\":1,\"bucketid\":536870912,\"rowid\":4}\t2\t3",
        "{\"writeid\":1,\"bucketid\":536870912,\"rowid\":5}\t2\t4",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":1}\t3\t3",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":2}\t3\t4",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":4}\t4\t3",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":5}\t4\t4",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":0}\t5\t2",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":1}\t5\t3",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":2}\t5\t4",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":3}\t6\t2",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":4}\t6\t3",
        "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":5}\t6\t4");
    List<String> rsBucket0 = dataProvider.getBucketData(tableName, "536870912");
    Assert.assertEquals(expectedRsBucket0, rsBucket0);
    // Verify all contents
    List<String> actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);

    CompactorTestUtilities.checkAcidVersion(fs.listFiles(new Path(table.getSd().getLocation()), true), fs,
        conf.getBoolVar(HiveConf.ConfVars.HIVE_WRITE_ACID_VERSION_FILE),
        new String[] { AcidUtils.DELTA_PREFIX, AcidUtils.DELETE_DELTA_PREFIX});

    // Clean up
    dataProvider.dropTable(tableName);
  }

  @Test
  public void testMinorCompactionWithoutBuckets() throws Exception {
    String dbName = "default";
    String tableName = "testMinorCompaction_wobuckets_1";
    String tempTableName = "tmp_txt_table_1";

    List<String> expectedDeltas = new ArrayList<>();
    expectedDeltas.add("delta_0000001_0000001_0000");
    expectedDeltas.add("delta_0000006_0000006_0000");
    expectedDeltas.add("delta_0000007_0000007_0000");
    expectedDeltas.add("delta_0000008_0000008_0000");

    List<String> expectedDeleteDeltas = new ArrayList<>();
    expectedDeleteDeltas.add("delete_delta_0000002_0000002_0000");
    expectedDeleteDeltas.add("delete_delta_0000003_0000003_0000");
    expectedDeleteDeltas.add("delete_delta_0000004_0000004_0000");
    expectedDeleteDeltas.add("delete_delta_0000005_0000005_0000");

    testMinorCompactionWithoutBucketsCommon(dbName, tableName, tempTableName, false, expectedDeltas,
        expectedDeleteDeltas, "delta_0000001_0000008_v0000025", CompactionType.MINOR);
  }

  @Test
  public void testMinorCompactionWithoutBucketsInsertOverwrite() throws Exception {
    String dbName = "default";
    String tableName = "testMinorCompaction_wobuckets_2";
    String tempTableName = "tmp_txt_table_2";

    List<String> expectedDeltas = new ArrayList<>();
    expectedDeltas.add("delta_0000006_0000006_0000");
    expectedDeltas.add("delta_0000007_0000007_0000");
    expectedDeltas.add("delta_0000008_0000008_0000");

    List<String> expectedDeleteDeltas = new ArrayList<>();
    expectedDeleteDeltas.add("delete_delta_0000002_0000002_0000");
    expectedDeleteDeltas.add("delete_delta_0000003_0000003_0000");
    expectedDeleteDeltas.add("delete_delta_0000004_0000004_0000");
    expectedDeleteDeltas.add("delete_delta_0000005_0000005_0000");

    testMinorCompactionWithoutBucketsCommon(dbName, tableName, tempTableName, true, expectedDeltas,
        expectedDeleteDeltas, "delta_0000002_0000008_v0000025", CompactionType.MINOR);
  }

  @Test
  public void testMajorCompactionWithoutBucketsInsertAndDeleteInsertOverwrite() throws Exception {
    String dbName = "default";
    String tableName = "testMinorCompaction_wobuckets_3";
    String tempTableName = "tmp_txt_table_3";

    List<String> expectedDeltas = new ArrayList<>();
    expectedDeltas.add("delta_0000006_0000006_0000");
    expectedDeltas.add("delta_0000007_0000007_0000");
    expectedDeltas.add("delta_0000008_0000008_0000");

    List<String> expectedDeleteDeltas = new ArrayList<>();
    expectedDeleteDeltas.add("delete_delta_0000002_0000002_0000");
    expectedDeleteDeltas.add("delete_delta_0000003_0000003_0000");
    expectedDeleteDeltas.add("delete_delta_0000004_0000004_0000");
    expectedDeleteDeltas.add("delete_delta_0000005_0000005_0000");

    testMinorCompactionWithoutBucketsCommon(dbName, tableName, tempTableName, true, expectedDeltas,
        expectedDeleteDeltas, "base_0000008_v0000025", CompactionType.MAJOR);
  }

  private void testMinorCompactionWithoutBucketsCommon(String dbName, String tableName, String tempTableName,
      boolean insertOverWrite, List<String> expectedDeltas, List<String> expectedDeleteDeltas,
      String expectedCompactedDeltaDirName, CompactionType compactionType) throws Exception {

    TestDataProvider dataProvider = new TestDataProvider();
    dataProvider.createTableWithoutBucketWithMultipleSplits(dbName, tableName, tempTableName, true, true,
        insertOverWrite);

    FileSystem fs = FileSystem.get(conf);
    Table table = msClient.getTable(dbName, tableName);

    List<String> expectedData = dataProvider.getAllData(tableName);
    List<String> expectedFileNames = dataProvider.getDataWithInputFileNames(null, tableName);

    // Verify deltas
    Assert.assertEquals("Delta directories does not match", expectedDeltas,
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null));
    // Verify delete delta
    Assert.assertEquals("Delete directories does not match", expectedDeleteDeltas,
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null));

    List<String> expectedBucketFiles =
        CompactorTestUtil.getBucketFileNamesWithoutAttemptId(fs, table, null, expectedDeltas);
    List<String> expectedDeleteBucketFiles =
        CompactorTestUtil.getBucketFileNamesWithoutAttemptId(fs, table, null, expectedDeleteDeltas);

    CompactorTestUtil.runCompaction(conf, dbName, tableName, compactionType, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);

    // Only 1 compaction should be in the response queue with succeeded state
    List<ShowCompactResponseElement> compacts =
        TxnUtils.getTxnStore(conf).showCompact(new ShowCompactRequest()).getCompacts();
    Assert.assertEquals("Completed compaction queue must contain one element", 1, compacts.size());
    Assert.assertEquals("Compaction state is not succeeded", "succeeded", compacts.get(0).getState());

    // Verify delta and delete delta directories after compaction
    if (CompactionType.MAJOR == compactionType) {
      List<String> actualBasesAfterComp =
          CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, null);
      Assert.assertEquals("Base directory does not match after compaction",
          Collections.singletonList(expectedCompactedDeltaDirName), actualBasesAfterComp);
      // Verify bucket files in delta and delete delta dirs
      Assert.assertEquals("Bucket names are not matching after compaction in the base folder",
          expectedBucketFiles, CompactorTestUtil.getBucketFileNames(fs, table, null, actualBasesAfterComp.get(0)));
    } else {
      List<String> actualDeltasAfterComp =
          CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null);
      Assert.assertEquals("Delta directories does not match after compaction",
          Collections.singletonList(expectedCompactedDeltaDirName), actualDeltasAfterComp);
      List<String> actualDeleteDeltasAfterComp =
          CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null);
      Assert.assertEquals("Delete delta directories does not match after compaction",
          Collections.singletonList("delete_" + expectedCompactedDeltaDirName), actualDeleteDeltasAfterComp);
      // Verify bucket files in delta and delete delta dirs
      Assert.assertEquals("Bucket names are not matching after compaction in the delete deltas",
          expectedDeleteBucketFiles,
          CompactorTestUtil.getBucketFileNames(fs, table, null, actualDeleteDeltasAfterComp.get(0)));
      Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
          CompactorTestUtil.getBucketFileNames(fs, table, null, actualDeltasAfterComp.get(0)));
    }

    // Verify all contents
    List<String> actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);
    List<String> actualFileNames = dataProvider.getDataWithInputFileNames(null, tableName);
    Assert.assertTrue(dataProvider.compareFileNames(expectedFileNames, actualFileNames));
    dataProvider.dropTable(tableName);
  }

  @Test
  public void testMinorAndMajorCompactionWithoutBuckets() throws Exception {
    String dbName = "default";
    String tableName = "testMinorCompaction_wobuckets_5";
    String tempTableName = "tmp_txt_table_5";

    TestDataProvider dataProvider = new TestDataProvider();
    dataProvider.createTableWithoutBucketWithMultipleSplits(dbName, tableName, tempTableName, true, true, false);

    FileSystem fs = FileSystem.get(conf);
    Table table = msClient.getTable(dbName, tableName);

    List<String> expectedData = dataProvider.getAllData(tableName);
    // Verify deltas
    List<String> expectedDeltas = new ArrayList<>();
    expectedDeltas.add("delta_0000001_0000001_0000");
    expectedDeltas.add("delta_0000006_0000006_0000");
    expectedDeltas.add("delta_0000007_0000007_0000");
    expectedDeltas.add("delta_0000008_0000008_0000");
    Assert.assertEquals("Delta directories does not match",
        expectedDeltas,
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null));
    // Verify delete delta
    List<String> expectedDeleteDeltas = new ArrayList<>();
    expectedDeleteDeltas.add("delete_delta_0000002_0000002_0000");
    expectedDeleteDeltas.add("delete_delta_0000003_0000003_0000");
    expectedDeleteDeltas.add("delete_delta_0000004_0000004_0000");
    expectedDeleteDeltas.add("delete_delta_0000005_0000005_0000");
    Assert.assertEquals("Delete directories does not match",
        expectedDeleteDeltas,
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null));

    List<String> expectedBucketFiles =
        CompactorTestUtil.getBucketFileNamesWithoutAttemptId(fs, table, null, expectedDeltas);
    List<String> expectedDeleteBucketFiles =
        CompactorTestUtil.getBucketFileNamesWithoutAttemptId(fs, table, null, expectedDeleteDeltas);

    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    CompactorTestUtil.runCleaner(conf);

    // Only 1 compaction should be in the response queue with succeeded state
    List<ShowCompactResponseElement> compacts =
        TxnUtils.getTxnStore(conf).showCompact(new ShowCompactRequest()).getCompacts();
    Assert.assertEquals("Completed compaction queue must contain one element", 1, compacts.size());
    Assert.assertEquals("Compaction state is not succeeded", "succeeded", compacts.get(0).getState());
    // Verify delta directories after compaction
    List<String> actualDeltasAfterComp =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null);
    Assert.assertEquals("Delta directories does not match after compaction",
        Collections.singletonList("delta_0000001_0000008_v0000024"), actualDeltasAfterComp);
    List<String> actualDeleteDeltasAfterComp =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null);
    Assert.assertEquals("Delete delta directories does not match after compaction",
        Collections.singletonList("delete_delta_0000001_0000008_v0000024"), actualDeleteDeltasAfterComp);
    // Verify bucket files in delta dirs
    List<String> actualData = dataProvider.getAllData(tableName);

    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil.getBucketFileNames(fs, table, null, actualDeltasAfterComp.get(0)));

    Assert.assertEquals("Bucket names in delete delta are not matching after compaction", expectedDeleteBucketFiles,
        CompactorTestUtil.getBucketFileNames(fs, table, null, actualDeleteDeltasAfterComp.get(0)));
    // Verify all contents
   // List<String> actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);

    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MAJOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);

    // Only 1 compaction should be in the response queue with succeeded state
    compacts =
        TxnUtils.getTxnStore(conf).showCompact(new ShowCompactRequest()).getCompacts();
    Assert.assertEquals("Completed compaction queue must contain one element", 2, compacts.size());
    Assert.assertEquals("Compaction state is not succeeded", "succeeded", compacts.get(0).getState());
    // Verify delta directories after compaction
    List<String> actualBasesAfterComp =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, null);
    Assert.assertEquals("Base directory does not match after compaction",
        Collections.singletonList("base_0000008_v0000038"), actualBasesAfterComp);
    // Verify bucket files in delta dirs
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil.getBucketFileNames(fs, table, null, actualBasesAfterComp.get(0)));
    // Verify all contents
    actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);
    dataProvider.dropTable(tableName);
  }

  @Test
  public void testMinorCompactionNotPartitionedWithBuckets() throws Exception {
    String dbName = "default";
    String tableName = "testMinorCompaction";
    // Create test table
    TestDataProvider dataProvider = new TestDataProvider();
    dataProvider.createFullAcidTable(tableName, false, true);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    // Insert test data into test table
    dataProvider.insertTestData(tableName);
    // Get all data before compaction is run
    List<String> expectedData = dataProvider.getAllData(tableName);
    // Verify deltas
    Assert.assertEquals("Delta directories does not match",
        Arrays.asList("delta_0000001_0000001_0000", "delta_0000002_0000002_0000", "delta_0000004_0000004_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null));
    // Verify delete delta
    Assert.assertEquals("Delete directories does not match",
        Arrays.asList("delete_delta_0000003_0000003_0000", "delete_delta_0000005_0000005_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null));
    // Run a compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // Only 1 compaction should be in the response queue with succeeded state
    verifySuccessfulCompaction(1);
    // Verify delta directories after compaction
    List<String> actualDeltasAfterComp =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null);
    Assert.assertEquals("Delta directories does not match after compaction",
        Collections.singletonList("delta_0000001_0000005_v0000009"), actualDeltasAfterComp);
    List<String> actualDeleteDeltasAfterComp =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null);
    Assert.assertEquals("Delete delta directories does not match after compaction",
        Collections.singletonList("delete_delta_0000001_0000005_v0000009"), actualDeleteDeltasAfterComp);
    // Verify bucket files in delta dirs
    List<String> expectedBucketFiles = Arrays.asList("bucket_00000", "bucket_00001");
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil.getBucketFileNames(fs, table, null, actualDeltasAfterComp.get(0)));
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil.getBucketFileNames(fs, table, null, actualDeleteDeltasAfterComp.get(0)));
    // Verify contents of bucket files.
    // Bucket 0
    List<String> expectedRsBucket0 = Arrays.asList(
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":1}\t3\t3",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":2}\t3\t4");
    List<String> rsBucket0 = dataProvider.getBucketData(tableName, "536870912");
    Assert.assertEquals(expectedRsBucket0, rsBucket0);
    // Bucket 1
    List<String> expectedRs1Bucket = Arrays.asList(
        "{\"writeid\":1,\"bucketid\":536936448,\"rowid\":1}\t2\t3",
        "{\"writeid\":1,\"bucketid\":536936448,\"rowid\":2}\t2\t4",
        "{\"writeid\":2,\"bucketid\":536936448,\"rowid\":1}\t4\t3",
        "{\"writeid\":2,\"bucketid\":536936448,\"rowid\":2}\t4\t4",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":0}\t5\t2",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":1}\t5\t3",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":2}\t5\t4",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":3}\t6\t2",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":4}\t6\t3",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":5}\t6\t4");
    List<String> rsBucket1 = dataProvider.getBucketData(tableName, "536936448");
    Assert.assertEquals(expectedRs1Bucket, rsBucket1);
    // Verify all contents
    List<String> actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);

    CompactorTestUtilities.checkAcidVersion(fs.listFiles(new Path(table.getSd().getLocation()), true), fs,
        conf.getBoolVar(HiveConf.ConfVars.HIVE_WRITE_ACID_VERSION_FILE),
        new String[] { AcidUtils.DELTA_PREFIX, AcidUtils.DELETE_DELTA_PREFIX});

    // Clean up
    dataProvider.dropTable(tableName);
  }

  @Test
  public void testMinorCompactionPartitionedWithoutBuckets() throws Exception {
    String dbName = "default";
    String tableName = "testMinorCompaction";
    // Create test table
    TestDataProvider dataProvider = new TestDataProvider();
    dataProvider.createFullAcidTable(tableName, true, false);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    // Insert test data into test table
    dataProvider.insertTestDataPartitioned(tableName);
    // Get all data before compaction is run
    List<String> expectedData = dataProvider.getAllData(tableName);
    // Verify deltas
    String partitionToday = "ds=today";
    String partitionTomorrow = "ds=tomorrow";
    String partitionYesterday = "ds=yesterday";
    Assert.assertEquals("Delta directories does not match",
        Arrays.asList("delta_0000001_0000001_0000", "delta_0000002_0000002_0000", "delta_0000004_0000004_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, partitionToday));
    // Verify delete delta
    Assert.assertEquals("Delete directories does not match",
        Arrays.asList("delete_delta_0000003_0000003_0000", "delete_delta_0000005_0000005_0000"),
        CompactorTestUtil
            .getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, partitionToday));
    // Run a compaction
    CompactorTestUtil
        .runCompaction(conf, dbName, tableName, CompactionType.MINOR, true, partitionToday, partitionTomorrow,
            partitionYesterday);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // 3 compaction should be in the response queue with succeeded state
    verifySuccessfulCompaction(3);
    // Verify delta directories after compaction in each partition
    List<String> actualDeltasAfterCompPartToday =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, partitionToday);
    Assert.assertEquals("Delta directories does not match after compaction",
        Collections.singletonList("delta_0000001_0000005_v0000009"), actualDeltasAfterCompPartToday);
    List<String> actualDeleteDeltasAfterCompPartToday =
        CompactorTestUtil
            .getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, partitionToday);
    Assert.assertEquals("Delete delta directories does not match after compaction",
        Collections.singletonList("delete_delta_0000001_0000005_v0000009"), actualDeleteDeltasAfterCompPartToday);
    // Verify bucket files in delta dirs
    List<String> expectedBucketFiles = Collections.singletonList("bucket_00000");
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil
            .getBucketFileNames(fs, table, partitionToday, actualDeltasAfterCompPartToday.get(0)));
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil
            .getBucketFileNames(fs, table, partitionToday, actualDeleteDeltasAfterCompPartToday.get(0)));

    // Verify contents of bucket files.
    // Bucket 0
    List<String> expectedRsBucket0 = Arrays
        .asList("{\"writeid\":1,\"bucketid\":536870912,\"rowid\":1}\t2\t3\tyesterday",
            "{\"writeid\":1,\"bucketid\":536870912,\"rowid\":2}\t2\t4\ttoday",
            "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":0}\t3\t3\ttoday",
            "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":0}\t3\t4\tyesterday",
            "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":1}\t4\t3\ttomorrow",
            "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":2}\t4\t4\ttoday",
            "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":0}\t5\t2\tyesterday",
            "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":0}\t5\t4\ttoday",
            "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":1}\t5\t3\tyesterday",
            "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":1}\t6\t2\ttoday",
            "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":2}\t6\t3\ttoday",
            "{\"writeid\":4,\"bucketid\":536870912,\"rowid\":3}\t6\t4\ttoday");
    List<String> rsBucket0 = dataProvider.getBucketData(tableName, "536870912");
    Assert.assertEquals(expectedRsBucket0, rsBucket0);

    // Verify all contents
    List<String> actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);

    CompactorTestUtilities.checkAcidVersion(fs.listFiles(new Path(table.getSd().getLocation()), true), fs,
        conf.getBoolVar(HiveConf.ConfVars.HIVE_WRITE_ACID_VERSION_FILE),
        new String[] { AcidUtils.DELTA_PREFIX, AcidUtils.DELETE_DELTA_PREFIX});

    // Clean up
    dataProvider.dropTable(tableName);
  }

  @Test
  public void testMinorCompactionPartitionedWithBuckets() throws Exception {
    String dbName = "default";
    String tableName = "testMinorCompaction";
    // Create test table
    TestDataProvider dataProvider = new TestDataProvider();
    dataProvider.createFullAcidTable(tableName, true, true);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    // Insert test data into test table
    dataProvider.insertTestDataPartitioned(tableName);
    // Get all data before compaction is run
    List<String> expectedData = dataProvider.getAllData(tableName);
    // Verify deltas
    String partitionToday = "ds=today";
    String partitionTomorrow = "ds=tomorrow";
    String partitionYesterday = "ds=yesterday";
    Assert.assertEquals("Delta directories does not match",
        Arrays.asList("delta_0000001_0000001_0000", "delta_0000002_0000002_0000", "delta_0000004_0000004_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, partitionToday));
    // Verify delete delta
    Assert.assertEquals("Delete directories does not match",
        Arrays.asList("delete_delta_0000003_0000003_0000", "delete_delta_0000005_0000005_0000"),
        CompactorTestUtil
            .getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, partitionToday));
    // Run a compaction
    CompactorTestUtil
        .runCompaction(conf, dbName, tableName, CompactionType.MINOR, true, partitionToday, partitionTomorrow,
            partitionYesterday);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // 3 compactions should be in the response queue with succeeded state
    verifySuccessfulCompaction( 3);
    // Verify delta directories after compaction in each partition
    List<String> actualDeltasAfterCompPartToday =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, partitionToday);
    Assert.assertEquals("Delta directories does not match after compaction",
        Collections.singletonList("delta_0000001_0000005_v0000009"), actualDeltasAfterCompPartToday);
    List<String> actualDeleteDeltasAfterCompPartToday =
        CompactorTestUtil
            .getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, partitionToday);
    Assert.assertEquals("Delete delta directories does not match after compaction",
        Collections.singletonList("delete_delta_0000001_0000005_v0000009"), actualDeleteDeltasAfterCompPartToday);
    // Verify bucket files in delta dirs
    List<String> expectedBucketFiles = Arrays.asList("bucket_00000", "bucket_00001");
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil
            .getBucketFileNames(fs, table, partitionToday, actualDeltasAfterCompPartToday.get(0)));
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil
            .getBucketFileNames(fs, table, partitionToday, actualDeleteDeltasAfterCompPartToday.get(0)));
    // Verify contents of bucket files.
    // Bucket 0
    List<String> expectedRsBucket0 = Arrays.asList(
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":0}\t3\t3\ttoday",
        "{\"writeid\":2,\"bucketid\":536870912,\"rowid\":0}\t3\t4\tyesterday");
    List<String> rsBucket0 = dataProvider.getBucketData(tableName, "536870912");
    Assert.assertEquals(expectedRsBucket0, rsBucket0);
    // Bucket 1
    List<String> expectedRsBucket1 = Arrays.asList("{\"writeid\":1,\"bucketid\":536936448,\"rowid\":0}\t2\t3\tyesterday",
        "{\"writeid\":1,\"bucketid\":536936448,\"rowid\":0}\t2\t4\ttoday",
        "{\"writeid\":2,\"bucketid\":536936448,\"rowid\":0}\t4\t3\ttomorrow",
        "{\"writeid\":2,\"bucketid\":536936448,\"rowid\":1}\t4\t4\ttoday",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":0}\t5\t2\tyesterday",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":0}\t5\t4\ttoday",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":1}\t5\t3\tyesterday",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":1}\t6\t2\ttoday",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":2}\t6\t3\ttoday",
        "{\"writeid\":4,\"bucketid\":536936448,\"rowid\":3}\t6\t4\ttoday");
    List<String> rsBucket1 = dataProvider.getBucketData(tableName, "536936448");
    Assert.assertEquals(expectedRsBucket1, rsBucket1);
    // Verify all contents
    List<String> actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);

    CompactorTestUtilities.checkAcidVersion(fs.listFiles(new Path(table.getSd().getLocation()), true), fs,
        conf.getBoolVar(HiveConf.ConfVars.HIVE_WRITE_ACID_VERSION_FILE),
        new String[] { AcidUtils.DELTA_PREFIX, AcidUtils.DELETE_DELTA_PREFIX});

    // Clean up
    dataProvider.dropTable(tableName);
  }

  @Test
  public void testMinorCompaction10DeltaDirs() throws Exception {
    String dbName = "default";
    String tableName = "testMinorCompaction";
    // Create test table
    TestDataProvider dataProvider = new TestDataProvider();
    dataProvider.createFullAcidTable(tableName, false, false);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    // Insert test data into test table
    dataProvider.insertTestData(tableName, 10);
    // Get all data before compaction is run
    List<String> expectedData = dataProvider.getAllData(tableName);
    Collections.sort(expectedData);
    // Verify deltas
    List<String> deltaNames = CompactorTestUtil
        .getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null);
    Assert.assertEquals(10, deltaNames.size());
    List<String> deleteDeltaName =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null);
    Assert.assertEquals(5, deleteDeltaName.size());
    // Run a compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    verifySuccessfulCompaction( 1);
    // Verify delta directories after compaction
    List<String> actualDeltasAfterComp =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null);
    Assert.assertEquals(Collections.singletonList("delta_0000001_0000015_v0000019"), actualDeltasAfterComp);
    List<String> actualDeleteDeltasAfterComp =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null);
    Assert
        .assertEquals(Collections.singletonList("delete_delta_0000001_0000015_v0000019"), actualDeleteDeltasAfterComp);
    // Verify bucket file in delta dir
    List<String> expectedBucketFile = Collections.singletonList("bucket_00000");
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFile,
        CompactorTestUtil.getBucketFileNames(fs, table, null, actualDeltasAfterComp.get(0)));
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFile,
        CompactorTestUtil.getBucketFileNames(fs, table, null, actualDeleteDeltasAfterComp.get(0)));
    // Verify contents of bucket file
    List<String> rsBucket0 = dataProvider.getBucketData(tableName, "536870912");
    Assert.assertEquals(5, rsBucket0.size());
    // Verify all contents
    List<String> actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);

    CompactorTestUtilities.checkAcidVersion(fs.listFiles(new Path(table.getSd().getLocation()), true), fs,
        conf.getBoolVar(HiveConf.ConfVars.HIVE_WRITE_ACID_VERSION_FILE),
        new String[] { AcidUtils.DELTA_PREFIX, AcidUtils.DELETE_DELTA_PREFIX});

    // Clean up
    dataProvider.dropTable(tableName);
  }

  @Test
  public void testMultipleMinorCompactions() throws Exception {
    String dbName = "default";
    String tableName = "testMinorCompaction";
    // Create test table
    TestDataProvider dataProvider = new TestDataProvider();
    dataProvider.createFullAcidTable(tableName, false, true);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    // Insert test data into test table
    dataProvider.insertTestData(tableName);
    // Run a compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // Only 1 compaction should be in the response queue with succeeded state
    verifySuccessfulCompaction(1);
    // Insert test data into test table
    dataProvider.insertTestData(tableName);
    // Run a compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // 2 compactions should be in the response queue with succeeded state
    verifySuccessfulCompaction(2);
    // Insert test data into test table
    dataProvider.insertTestData(tableName);
    // Run a compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // 3 compaction should be in the response queue with succeeded state
    verifySuccessfulCompaction(3);
    // Verify delta directories after compaction
    List<String> actualDeltasAfterComp =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null);
    Assert.assertEquals("Delta directories does not match after compaction",
        Collections.singletonList("delta_0000001_0000015_v0000044"), actualDeltasAfterComp);
    List<String> actualDeleteDeltasAfterComp =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null);
    Assert.assertEquals("Delete delta directories does not match after compaction",
        Collections.singletonList("delete_delta_0000001_0000015_v0000044"), actualDeleteDeltasAfterComp);

    CompactorTestUtilities.checkAcidVersion(fs.listFiles(new Path(table.getSd().getLocation()), true), fs,
        conf.getBoolVar(HiveConf.ConfVars.HIVE_WRITE_ACID_VERSION_FILE),
        new String[] { AcidUtils.DELTA_PREFIX, AcidUtils.DELETE_DELTA_PREFIX});

  }

  @Test
  public void testMinorCompactionWhileStreaming() throws Exception {
    String dbName = "default";
    String tableName = "testMinorCompaction";
    executeStatementOnDriver("drop table if exists " + tableName, driver);
    executeStatementOnDriver("CREATE TABLE " + tableName + "(a INT, b STRING) " + " CLUSTERED BY(a) INTO 1 BUCKETS"
        + " STORED AS ORC  TBLPROPERTIES ('transactional'='true')", driver);
    StreamingConnection connection = null;
    try {
      // Write a couple of batches
      for (int i = 0; i < 2; i++) {
        CompactorTestUtil.writeBatch(conf, dbName, tableName, false, false);
      }

      // Start a third batch, but don't close it.
      connection = CompactorTestUtil.writeBatch(conf, dbName, tableName, false, true);

      // Now, compact
      CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);

      // Find the location of the table
      IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
      Table table = metaStoreClient.getTable(dbName, tableName);
      FileSystem fs = FileSystem.get(conf);
      Assert.assertEquals("Delta names does not match", Arrays
          .asList("delta_0000001_0000002", "delta_0000001_0000005_v0000009", "delta_0000003_0000004",
              "delta_0000005_0000006"), CompactorTestUtil.getBaseOrDeltaNames(fs, null, table, null));
      CompactorTestUtil.checkExpectedTxnsPresent(null,
          new Path[] {new Path(table.getSd().getLocation(), "delta_0000001_0000005_v0000009")}, "a,b", "int:string",
          0, 1L, 4L, null, 1);
    } finally {
      if (connection != null) {
        connection.close();
      }
    }
  }

  @Test
  public void testMinorCompactionWhileStreamingAfterAbort() throws Exception {
    String dbName = "default";
    String tableName = "testMinorCompaction";
    executeStatementOnDriver("drop table if exists " + tableName, driver);
    executeStatementOnDriver("CREATE TABLE " + tableName + "(a INT, b STRING) " + " CLUSTERED BY(a) INTO 1 BUCKETS"
        + " STORED AS ORC  TBLPROPERTIES ('transactional'='true')", driver);
    CompactorTestUtil.runStreamingAPI(conf, dbName, tableName, Lists
        .newArrayList(new CompactorTestUtil.StreamingConnectionOption(false, false),
            new CompactorTestUtil.StreamingConnectionOption(false, false),
            new CompactorTestUtil.StreamingConnectionOption(true, false)));
    // Now, compact
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    Assert.assertEquals("Delta names does not match",
        Arrays.asList("delta_0000001_0000002", "delta_0000001_0000006_v0000009", "delta_0000003_0000004"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, null, table, null));
    CompactorTestUtil.checkExpectedTxnsPresent(null,
        new Path[] {new Path(table.getSd().getLocation(), "delta_0000001_0000006_v0000009")}, "a,b", "int:string", 0,
        1L, 4L, Lists.newArrayList(5, 6), 1);

    CompactorTestUtilities.checkAcidVersion(fs.listFiles(new Path(table.getSd().getLocation()), true), fs,
        conf.getBoolVar(HiveConf.ConfVars.HIVE_WRITE_ACID_VERSION_FILE),
        new String[] { AcidUtils.DELTA_PREFIX });
  }

  @Test
  public void testMinorCompactionWhileStreamingWithAbort() throws Exception {
    String dbName = "default";
    String tableName = "testMinorCompaction";
    executeStatementOnDriver("drop table if exists " + tableName, driver);
    executeStatementOnDriver(
            "CREATE TABLE " + tableName + "(a INT, b STRING, c int, d int, e int, f int, j int, i int) " +
                    " STORED AS ORC  TBLPROPERTIES ('transactional'='true')", driver);
    CompactorTestUtil.runStreamingAPI(conf, dbName, tableName, Lists
        .newArrayList(new CompactorTestUtil.StreamingConnectionOption(false, false),
            new CompactorTestUtil.StreamingConnectionOption(true, false),
            new CompactorTestUtil.StreamingConnectionOption(false, false)));
    // Now, compact
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    Assert.assertEquals("Delta names does not match",
        Arrays.asList("delta_0000001_0000002", "delta_0000001_0000006_v0000009", "delta_0000005_0000006"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, null, table, null));
    CompactorTestUtil.checkExpectedTxnsPresent(null,
        new Path[] {new Path(table.getSd().getLocation(), "delta_0000001_0000006_v0000009")}, "a,b", "int:string", 0,
        1L, 6L, Lists.newArrayList(3, 4), 1);
  }

  @Test
  public void testMinorCompactionWhileStreamingWithAbortInMiddle() throws Exception {
    String dbName = "default";
    String tableName = "testMinorCompaction";
    executeStatementOnDriver("drop table if exists " + tableName, driver);
    executeStatementOnDriver(
        "CREATE TABLE " + tableName + "(a INT, b STRING) " + " STORED AS ORC  TBLPROPERTIES ('transactional'='true')",
        driver);
    StrictDelimitedInputWriter writer = StrictDelimitedInputWriter.newBuilder().withFieldDelimiter(',').build();
    StreamingConnection connection = HiveStreamingConnection.newBuilder().withDatabase(dbName).withTable(tableName)
        .withAgentInfo("UT_" + Thread.currentThread().getName()).withHiveConf(conf).withRecordWriter(writer).connect();
    connection.beginTransaction();
    connection.write("50,Kiev".getBytes());
    connection.write("51,St. Petersburg".getBytes());
    connection.write("52,Boston".getBytes());
    connection.commitTransaction();
    connection.beginTransaction();
    connection.write("60,Budapest".getBytes());
    connection.abortTransaction();
    connection.beginTransaction();
    connection.write("71,Szeged".getBytes());
    connection.write("72,Debrecen".getBytes());
    connection.commitTransaction();
    connection.close();
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    CompactorTestUtil.runCleaner(conf);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    Assert.assertEquals("Delta names does not match", Collections.singletonList("delta_0000001_0000003_v0000006"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, null, table, null));
    CompactorTestUtil.checkExpectedTxnsPresent(null,
        new Path[] {new Path(table.getSd().getLocation(), "delta_0000001_0000003_v0000006")}, "a,b", "int:string", 0,
        1L, 3L, Lists.newArrayList(2), 1);
  }

  @Test
  public void testMajorCompactionAfterMinor() throws Exception {
    String dbName = "default";
    String tableName = "testMinorCompaction";
    // Create test table
    TestDataProvider dataProvider = new TestDataProvider();
    dataProvider.createFullAcidTable(tableName, false, false);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    // Insert test data into test table
    dataProvider.insertTestData(tableName);
    // Get all data before compaction is run
    List<String> expectedData = dataProvider.getAllData(tableName);
    Collections.sort(expectedData);
    // Run a compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // Only 1 compaction should be in the response queue with succeeded state
    verifySuccessfulCompaction(1);
    // Verify delta directories after compaction
    Assert.assertEquals("Delta directories does not match after minor compaction",
        Collections.singletonList("delta_0000001_0000005_v0000009"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null));
    Assert.assertEquals("Delete delta directories does not match after minor compaction",
        Collections.singletonList("delete_delta_0000001_0000005_v0000009"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null));
    // Verify all contents
    List<String> actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);
    // Insert another round of test data
    dataProvider.insertTestData(tableName);
    expectedData = dataProvider.getAllData(tableName);
    Collections.sort(expectedData);
    // Run a compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MAJOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // 2 compaction should be in the response queue with succeeded state
    verifySuccessfulCompaction(2);
    // Verify base directory after compaction
    Assert.assertEquals("Base directory does not match after major compaction",
        Collections.singletonList("base_0000010_v0000029"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, null));
    // Verify all contents
    actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);
  }

  @Test
  public void testMinorCompactionAfterMajor() throws Exception {
    String dbName = "default";
    String tableName = "testMinorCompactionAfterMajor";
    // Create test table
    TestDataProvider dataProvider = new TestDataProvider();
    dataProvider.createFullAcidTable(tableName, false, false);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    // Insert test data into test table
    dataProvider.insertTestData(tableName);
    // Get all data before compaction is run
    List<String> expectedData = dataProvider.getAllData(tableName);
    Collections.sort(expectedData);
    // Run a compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MAJOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // Only 1 compaction should be in the response queue with succeeded state
    verifySuccessfulCompaction(1);
    // Verify base directory after compaction
    Assert.assertEquals("Base directory does not match after major compaction",
        Collections.singletonList("base_0000005_v0000009"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, null));
    // Verify all contents
    List<String> actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);
    // Insert another round of test data
    dataProvider.insertTestData(tableName);
    expectedData = dataProvider.getAllData(tableName);
    Collections.sort(expectedData);
    // Run a compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // 2 compaction should be in the response queue with succeeded state
    verifySuccessfulCompaction(2);
    // Verify base directory after compaction
    Assert.assertEquals("Base directory does not match after major compaction",
        Collections.singletonList("base_0000005_v0000009"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, null));
    Assert.assertEquals("Delta directories do not match after major compaction",
        Collections.singletonList("delta_0000006_0000010_v0000021"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null));
    Assert.assertEquals("Delete delta directories does not match after minor compaction",
        Collections.singletonList("delete_delta_0000006_0000010_v0000021"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null));
    // Verify all contents
    actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);
  }

  @Test
  public void testMinorCompactionWhileStreamingWithSplitUpdate() throws Exception {
    String dbName = "default";
    String tableName = "testMinorCompaction";
    executeStatementOnDriver("drop table if exists " + tableName, driver);
    executeStatementOnDriver("CREATE TABLE " + tableName + "(a INT, b STRING) " + " CLUSTERED BY(a) INTO 1 BUCKETS"
        + " STORED AS ORC  TBLPROPERTIES ('transactional'='true'," + "'transactional_properties'='default')", driver);
    StreamingConnection connection = null;
    // Write a couple of batches
    try {
      for (int i = 0; i < 2; i++) {
        CompactorTestUtil.writeBatch(conf, dbName, tableName, false, false);
      }
      // Start a third batch, but don't close it.
      connection = CompactorTestUtil.writeBatch(conf, dbName, tableName, false, true);
      // Now, compact
      CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
      // Find the location of the table
      IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
      Table table = metaStoreClient.getTable(dbName, tableName);
      FileSystem fs = FileSystem.get(conf);
      Assert.assertEquals("Delta names does not match", Arrays
          .asList("delta_0000001_0000002", "delta_0000001_0000005_v0000009", "delta_0000003_0000004",
              "delta_0000005_0000006"), CompactorTestUtil.getBaseOrDeltaNames(fs, null, table, null));
      CompactorTestUtil.checkExpectedTxnsPresent(null,
          new Path[] {new Path(table.getSd().getLocation(), "delta_0000001_0000005_v0000009")}, "a,b", "int:string",
          0, 1L, 4L, null, 1);
      //Assert that we have no delete deltas if there are no input delete events.
      Assert.assertEquals(0,
          CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null).size());
    } finally {
      if (connection != null) {
        connection.close();
      }
    }

  }

  @Test
  public void testCompactionWithSchemaEvolutionAndBuckets() throws Exception {
    String dbName = "default";
    String tblName = "testCompactionWithSchemaEvolutionAndBuckets";
    executeStatementOnDriver("drop table if exists " + tblName, driver);
    executeStatementOnDriver("create transactional table " + tblName
        + " (a int, b int) partitioned by(ds string) clustered by (a) into 2 buckets"
        + " stored as ORC TBLPROPERTIES('bucketing_version'='2', 'transactional'='true',"
        + " 'transactional_properties'='default')", driver);
    // Insert some data
    executeStatementOnDriver("insert into " + tblName
        + " partition (ds) values(1,2,'today'),(1,3,'today'),(1,4,'yesterday'),(2,2,'yesterday'),(2,3,'today'),(2,4,'today')",
        driver);
    // Add a new column
    executeStatementOnDriver("alter table " + tblName + " add columns(c int)", driver);
    // Insert more data
    executeStatementOnDriver("insert into " + tblName
        + " partition (ds) values(3,2,1000,'yesterday'),(3,3,1001,'today'),(3,4,1002,'yesterday'),(4,2,1003,'today'),"
        + "(4,3,1004,'yesterday'),(4,4,1005,'today')", driver);
    executeStatementOnDriver("delete from " + tblName + " where b = 2", driver);

    List<String> expectedRsBucket0PtnToday = new ArrayList<>();
    expectedRsBucket0PtnToday.add("{\"writeid\":1,\"bucketid\":536870912,\"rowid\":0}\t2\t3\tNULL\ttoday");
    expectedRsBucket0PtnToday.add("{\"writeid\":1,\"bucketid\":536870912,\"rowid\":1}\t2\t4\tNULL\ttoday");
    expectedRsBucket0PtnToday.add("{\"writeid\":3,\"bucketid\":536870912,\"rowid\":0}\t3\t3\t1001\ttoday");
    List<String> expectedRsBucket1PtnToday = new ArrayList<>();
    expectedRsBucket1PtnToday.add("{\"writeid\":1,\"bucketid\":536936448,\"rowid\":1}\t1\t3\tNULL\ttoday");
    expectedRsBucket1PtnToday.add("{\"writeid\":3,\"bucketid\":536936448,\"rowid\":1}\t4\t4\t1005\ttoday");
    // Bucket 0, partition 'today'
    List<String> rsBucket0PtnToday = executeStatementOnDriverAndReturnResults("select ROW__ID, * from  "
        + tblName + " where ROW__ID.bucketid = 536870912 and ds='today' order by a,b", driver);
    // Bucket 1, partition 'today'
    List<String> rsBucket1PtnToday = executeStatementOnDriverAndReturnResults("select ROW__ID, * from  " + tblName
        + " where ROW__ID.bucketid = 536936448 and ds='today' order by a,b", driver);
    Assert.assertEquals("pre-compaction read", expectedRsBucket0PtnToday, rsBucket0PtnToday);
    Assert.assertEquals("pre-compaction read", expectedRsBucket1PtnToday, rsBucket1PtnToday);

    //  Run major compaction and cleaner
    CompactorTestUtil
        .runCompaction(conf, dbName, tblName, CompactionType.MAJOR, true, "ds=yesterday", "ds=today");
    CompactorTestUtil.runCleaner(conf);

    // Bucket 0, partition 'today'
    List<String> rsCompactBucket0PtnToday = executeStatementOnDriverAndReturnResults("select ROW__ID, * from  "
        + tblName + " where ROW__ID.bucketid = 536870912 and ds='today' order by a,b", driver);
    Assert.assertEquals("compacted read", expectedRsBucket0PtnToday, rsCompactBucket0PtnToday);
    // Bucket 1, partition 'today'
    List<String> rsCompactBucket1PtnToday = executeStatementOnDriverAndReturnResults("select ROW__ID, * from  "
        + tblName + " where ROW__ID.bucketid = 536936448 and ds='today' order by a,b", driver);
    Assert.assertEquals("compacted read", expectedRsBucket1PtnToday, rsCompactBucket1PtnToday);
    // Clean up
    executeStatementOnDriver("drop table " + tblName, driver);
  }

  @Test
  public void testCompactionWithSchemaEvolutionNoBucketsMultipleReducers() throws Exception {
    HiveConf hiveConf = new HiveConf(conf);
    hiveConf.setIntVar(HiveConf.ConfVars.MAXREDUCERS, 2);
    hiveConf.setIntVar(HiveConf.ConfVars.HADOOPNUMREDUCERS, 2);
    driver = DriverFactory.newDriver(hiveConf);
    String dbName = "default";
    String tblName = "testCompactionWithSchemaEvolutionNoBucketsMultipleReducers";
    executeStatementOnDriver("drop table if exists " + tblName, driver);
    executeStatementOnDriver("create transactional table " + tblName + " (a int, b int) partitioned by(ds string)"
        + " stored as ORC TBLPROPERTIES('transactional'='true'," + " 'transactional_properties'='default')", driver);
    // Insert some data
    executeStatementOnDriver("insert into " + tblName
        + " partition (ds) values(1,2,'today'),(1,3,'today'),(1,4,'yesterday'),(2,2,'yesterday'),(2,3,'today'),(2,4,'today')",
        driver);
    // Add a new column
    executeStatementOnDriver("alter table " + tblName + " add columns(c int)", driver);
    // Insert more data
    executeStatementOnDriver("insert into " + tblName
        + " partition (ds) values(3,2,1000,'yesterday'),(3,3,1001,'today'),(3,4,1002,'yesterday'),(4,2,1003,'today'),"
        + "(4,3,1004,'yesterday'),(4,4,1005,'today')", driver);
    executeStatementOnDriver("delete from " + tblName + " where b = 2", driver);
    //  Run major compaction and cleaner
    CompactorTestUtil
        .runCompaction(conf, dbName, tblName, CompactionType.MAJOR, true, "ds=yesterday", "ds=today");
    CompactorTestUtil.runCleaner(hiveConf);
    List<String> expectedRsPtnToday = new ArrayList<>();
    expectedRsPtnToday.add("{\"writeid\":1,\"bucketid\":536870912,\"rowid\":1}\t1\t3\tNULL\ttoday");
    expectedRsPtnToday.add("{\"writeid\":1,\"bucketid\":536870912,\"rowid\":2}\t2\t3\tNULL\ttoday");
    expectedRsPtnToday.add("{\"writeid\":1,\"bucketid\":536870912,\"rowid\":3}\t2\t4\tNULL\ttoday");
    expectedRsPtnToday.add("{\"writeid\":3,\"bucketid\":536870912,\"rowid\":0}\t3\t3\t1001\ttoday");
    expectedRsPtnToday.add("{\"writeid\":3,\"bucketid\":536870912,\"rowid\":2}\t4\t4\t1005\ttoday");
    List<String> expectedRsPtnYesterday = new ArrayList<>();
    expectedRsPtnYesterday.add("{\"writeid\":1,\"bucketid\":536936448,\"rowid\":0}\t1\t4\tNULL\tyesterday");
    expectedRsPtnYesterday.add("{\"writeid\":3,\"bucketid\":536936448,\"rowid\":1}\t3\t4\t1002\tyesterday");
    expectedRsPtnYesterday.add("{\"writeid\":3,\"bucketid\":536936448,\"rowid\":2}\t4\t3\t1004\tyesterday");
    // Partition 'today'
    List<String> rsCompactPtnToday = executeStatementOnDriverAndReturnResults("select ROW__ID, * from  " + tblName
        + " where ds='today'", driver);
    Assert.assertEquals("compacted read", expectedRsPtnToday, rsCompactPtnToday);
    // Partition 'yesterday'
    List<String> rsCompactPtnYesterday = executeStatementOnDriverAndReturnResults("select ROW__ID, * from  " + tblName
        + " where ds='yesterday'", driver);
    Assert.assertEquals("compacted read", expectedRsPtnYesterday, rsCompactPtnYesterday);
    // Clean up
    executeStatementOnDriver("drop table " + tblName, driver);
  }

  @Test public void testMajorCompactionDb() throws Exception {
    testCompactionDb(CompactionType.MAJOR, "base_0000005_v0000011");
  }

  @Test public void testMinorCompactionDb() throws Exception {
    testCompactionDb(CompactionType.MINOR, "delta_0000001_0000005_v0000011");
  }

  /**
   * Minor compaction on a table with no deletes shouldn't result in any delete deltas.
   */
  @Test public void testJustInserts() throws Exception {
    String dbName = "default";
    String tableName = "testJustInserts";
    // Create test table
    executeStatementOnDriver("CREATE TABLE " + tableName + " (id string, value string)"
        + "CLUSTERED BY(id) INTO 10 BUCKETS "
        + "STORED AS ORC TBLPROPERTIES('transactional'='true')", driver);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    // Insert test data into test table
    executeStatementOnDriver("insert into " + tableName + " values ('21', 'value21'),('84', 'value84'),"
        + "('66', 'value66'),('54', 'value54')", driver);
    executeStatementOnDriver("insert into " + tableName + " values ('22', 'value22'),('34', 'value34'),"
        + "('35', 'value35')", driver);
    executeStatementOnDriver("insert into " + tableName + " values ('75', 'value75'),('99', 'value99')", driver);

    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // Only 1 compaction should be in the response queue with succeeded state
    verifySuccessfulCompaction(1);

    List<String> actualDeltasAfterComp =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null);
    Assert.assertEquals("Delta directories does not match after compaction",
        Collections.singletonList("delta_0000001_0000003_v0000005"), actualDeltasAfterComp);
    List<String> actualDeleteDeltasAfterComp =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null);
    Assert.assertEquals("Delete delta directories does not match after compaction",
        Collections.emptyList(), actualDeleteDeltasAfterComp);
  }

  /**
   * Minor compaction on a table with no insert deltas should result in just a delete delta.
   */
  @Test public void testJustDeletes() throws Exception {
    String dbName = "default";
    String tableName = "testJustDeletes";
    // Create test table
    executeStatementOnDriver("CREATE TABLE " + tableName + " (id string, value string)"
        + "CLUSTERED BY(id) INTO 10 BUCKETS "
        + "STORED AS ORC TBLPROPERTIES('transactional'='true')", driver);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    // Insert test data into test table
    executeStatementOnDriver("insert overwrite table " + tableName + " values ('1','one'),('2','two'),('3','three'),"
        + "('4','four'),('5','five'),('6','six'),('7','seven'),('8','eight'),('9','nine'),('10','ten'),('11','eleven'),"
        + "('12','twelve'),('13','thirteen'),('14','fourteen'),('15','fifteen'),('16','sixteen'),('17','seventeen'),"
        + "('18','eighteen'),('19','nineteen'),('20','twenty')", driver);
    executeStatementOnDriver("delete from " + tableName + " where id in ('2', '4', '12', '15')", driver);
    executeStatementOnDriver("delete from " + tableName + " where id in ('11', '10', '14', '5')", driver);

    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // Only 1 compaction should be in the response queue with succeeded state
    verifySuccessfulCompaction(1);

    // insert one more to verify a correct writeid (4)
    executeStatementOnDriver("insert into " + tableName + " values ('75', 'value75'),('99', 'value99')", driver);

    List<String> actualDeltasAfterComp =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null);
    Assert.assertEquals("Delta directories does not match after compaction",
        Collections.singletonList("delta_0000004_0000004_0000"), actualDeltasAfterComp);
    List<String> actualDeleteDeltasAfterComp =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null);
    Assert.assertEquals("Delete delta directories does not match after compaction",
        Collections.singletonList("delete_delta_0000002_0000003_v0000005"), actualDeleteDeltasAfterComp);
  }

  /**
   * After running insert overwrite, followed by a minor compaction, major compaction was failing because minor
   * compaction was resulting in deltas named delta_1_y.
   */
  @Test public void testIowMinorMajor() throws Exception {
    String dbName = "default";
    String tableName = "testIowMinorMajor";
    // Create test table
    executeStatementOnDriver("CREATE TABLE " + tableName + " (id string, value string)"
        + "CLUSTERED BY(id) INTO 2 BUCKETS "
        + "STORED AS ORC TBLPROPERTIES('transactional'='true')", driver);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    // Insert test data into test table
    executeStatementOnDriver("insert overwrite table " + tableName + " values ('1','one'),('2','two'),('3','three'),"
        + "('4','four'),('5','five'),('6','six'),('7','seven'),('8','eight'),('9','nine'),('10','ten'),('11','eleven'),"
        + "('12','twelve'),('13','thirteen'),('14','fourteen'),('15','fifteen'),('16','sixteen'),('17','seventeen'),"
        + "('18','eighteen'),('19','nineteen'),('20','twenty')", driver);
    executeStatementOnDriver("delete from " + tableName + " where id in ('2', '4', '12', '15')", driver);
    executeStatementOnDriver("delete from " + tableName + " where id in ('11', '10', '14', '5')", driver);
    executeStatementOnDriver("insert into " + tableName + " values ('21', 'value21'),('84', 'value84'),('66', 'value66'),('54', 'value54')", driver);
    executeStatementOnDriver("insert into " + tableName + " values ('22', 'value22'),('34', 'value34'),('35', 'value35')", driver);
    executeStatementOnDriver("insert into " + tableName + " values ('75', 'value75'),('99', 'value99')", driver);

    // Verify deltas
    Assert.assertEquals("Delta directories does not match",
        Arrays.asList("delta_0000004_0000004_0000", "delta_0000005_0000005_0000", "delta_0000006_0000006_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null));
    // Verify delete delta
    Assert.assertEquals("Delete directories does not match",
        Arrays.asList("delete_delta_0000002_0000002_0000", "delete_delta_0000003_0000003_0000"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null));
    // Get all data before compaction is run
    TestDataProvider dataProvider = new TestDataProvider();
    List<String> expectedData = dataProvider.getAllData(tableName);

    // Run a compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // Only 1 compaction should be in the response queue with succeeded state
    verifySuccessfulCompaction(1);
    // Verify deltas
    Assert.assertEquals("Delta directories does not match",
        Collections.singletonList("delta_0000002_0000006_v0000009"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null));
    // Verify delete delta
    Assert.assertEquals("Delete directories does not match",
        Collections.singletonList("delete_delta_0000002_0000006_v0000009"),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null));
    // Verify all contents
    List<String> actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);

    // Run a compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MAJOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // 2 compactions should be in the response queue with succeeded state
    verifySuccessfulCompaction(2);
    // Should contain only one base directory now
    String expectedBase = "base_0000006_v0000023";
    Assert.assertEquals("Base directory does not match after major compaction",
        Collections.singletonList(expectedBase),
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, null));
    // Check base dir contents
    List<String> expectedBucketFiles = Arrays.asList("bucket_00000", "bucket_00001");
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil.getBucketFileNames(fs, table, null, expectedBase));
    // Check bucket file contents
    checkBucketIdAndRowIdInAcidFile(fs, new Path(table.getSd().getLocation(), expectedBase), 0);
    checkBucketIdAndRowIdInAcidFile(fs, new Path(table.getSd().getLocation(), expectedBase), 1);
    // Verify all contents
    actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);

    // Clean up
    dataProvider.dropTable(tableName);
  }

  @Test
  public void testMajorCompactionAfterTwoMergeStatements() throws Exception {
    String dbName = "default";
    String tableName = "comp_and_merge_test";
    TestDataProvider dataProvider = new TestDataProvider();
    // Create a non bucketed test table and insert some initial data
    executeStatementOnDriver(
        "CREATE TABLE " + tableName + "(id int,value string) STORED AS ORC TBLPROPERTIES ('transactional'='true')",
        driver);
    executeStatementOnDriver("insert into " + tableName
        + " values(1, 'value_1'),(2, 'value_2'),(3, 'value_3'),(4, 'value_4'),(5, 'value_5')", driver);

    // Find the location of the table
    IMetaStoreClient msClient = new HiveMetaStoreClient(conf);
    Table table = msClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);

    runMergeStatement(tableName,
        Arrays.asList("1, 'newvalue_1'", "2, 'newvalue_2'", "3, 'newvalue_3'", "6, 'value_6'", "7, 'value_7'"));
    runMergeStatement(tableName, Arrays.asList("1, 'newestvalue_1'", "2, 'newestvalue_2'", "5, 'newestvalue_5'",
        "7, 'newestvalue_7'", "8, 'value_8'"));

    List<String> expectedData = dataProvider.getAllData(tableName);

    // Run a query-based MAJOR compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MAJOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // Only 1 compaction should be in the response queue with succeeded state
    verifySuccessfulCompaction(1);

    // Verify delta directories after compaction
    List<String> actualDeltasAfterComp =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, null);
    Assert.assertEquals("Base directory does not match after compaction",
        Collections.singletonList("base_0000003_v0000014"), actualDeltasAfterComp);

    // Verify bucket files in delta dirs
    List<String> expectedBucketFiles = Collections.singletonList("bucket_00000");
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
        CompactorTestUtil.getBucketFileNames(fs, table, null, actualDeltasAfterComp.get(0)));

    // Verify contents of bucket files.
    List<String> expectedRsBucket0 = Arrays.asList("{\"writeid\":1,\"bucketid\":536870912,\"rowid\":3}\t4\tvalue_4",
    "{\"writeid\":2,\"bucketid\":536870913,\"rowid\":2}\t3\tnewvalue_3",
    "{\"writeid\":2,\"bucketid\":536870914,\"rowid\":0}\t6\tvalue_6",
    "{\"writeid\":3,\"bucketid\":536870913,\"rowid\":0}\t1\tnewestvalue_1",
    "{\"writeid\":3,\"bucketid\":536870913,\"rowid\":1}\t2\tnewestvalue_2",
    "{\"writeid\":3,\"bucketid\":536870913,\"rowid\":2}\t5\tnewestvalue_5",
    "{\"writeid\":3,\"bucketid\":536870913,\"rowid\":3}\t7\tnewestvalue_7",
    "{\"writeid\":3,\"bucketid\":536870914,\"rowid\":0}\t8\tvalue_8");
    List<String> rsBucket0 = executeStatementOnDriverAndReturnResults("select ROW__ID, * from " + tableName, driver);
    Assert.assertEquals(expectedRsBucket0, rsBucket0);
    // Verify all contents
    List<String> actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);
    // Clean up
    dataProvider.dropTable(tableName);
    msClient.close();
  }

  @Test
  public void testMinorCompactionAfterMultipleMergeStatements() throws Exception {
    String dbName = "default";
    String tableName = "minor_comp_and_merge_test";
    TestDataProvider dataProvider = new TestDataProvider();
    // Create a non bucketed test table and insert some initial data
    executeStatementOnDriver(
        "CREATE TABLE " + tableName + "(id int,value string) STORED AS ORC TBLPROPERTIES ('transactional'='true')",
        driver);
    executeStatementOnDriver("insert into " + tableName
        + " values(1, 'value_1'),(2, 'value_2'),(3, 'value_3'),(4, 'value_4'),(5, 'value_5'), (6, 'value_6'), (7, 'value_7'), (8, 'value_8')",
        driver);

    // Find the location of the table
    IMetaStoreClient msClient = new HiveMetaStoreClient(conf);
    Table table = msClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);

    runMergeStatement(tableName, Arrays.asList("1, 'newvalue_1'", "2, 'newvalue_2'", "4, 'newvalue_4'",
        "6, 'newvalue_6'", "9, 'value_9'", "10, 'value_10'", "11, 'value_11'", "12, 'value_12'"));
    runMergeStatement(tableName, Arrays.asList("2, 'newestvalue_2'", "4, 'newestvalue_4'", "6, 'newestvalue_6'",
        "10, 'newestvalue_10'", "11, 'newestvalue_11'", "13, 'value_13'", "14, 'value_14'"));

    // Run a query-based MAJOR compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // Only 1 compaction should be in the response queue with succeeded state
    verifySuccessfulCompaction(1);

    runMergeStatement(tableName, Arrays.asList("1, 'latestvalue_1'", "4, 'latestvalue_4'", "5, 'latestvalue_5'",
        "9, 'latestvalue_9'", "11, 'latestvalue_11'", "13, 'latestvalue_13'", "15, 'value_15'"));
    List<String> expectedData = dataProvider.getAllData(tableName);

    // Run a query-based MAJOR compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, CompactionType.MINOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);
    // Only 1 compaction should be in the response queue with succeeded state
    verifySuccessfulCompaction(2);

    // Verify delta and delete delta directories after compaction
    List<String> actualDeltasAfterComp =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null);
    Assert.assertEquals("Delta directory does not match after compaction",
        Collections.singletonList("delta_0000001_0000004_v0000032"), actualDeltasAfterComp);

    List<String> actualDeleteDeltasAfterComp =
        CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deleteEventDeltaDirFilter, table, null);
    Assert.assertEquals("Delete delta directory does not match after compaction",
        Collections.singletonList("delete_delta_0000001_0000004_v0000032"), actualDeleteDeltasAfterComp);

    // Verify bucket files in delta dirs
    List<String> expectedBucketFiles = Collections.singletonList("bucket_00000");
    Assert.assertEquals("Bucket name in delta directory is not matching after compaction", expectedBucketFiles,
        CompactorTestUtil.getBucketFileNames(fs, table, null, actualDeltasAfterComp.get(0)));
    Assert.assertEquals("Bucket name in delete delta directory is not matching after compaction", expectedBucketFiles,
        CompactorTestUtil.getBucketFileNames(fs, table, null, actualDeleteDeltasAfterComp.get(0)));

    // Verify contents of bucket files.
    List<String> expectedRsBucket0 =
            Arrays.asList("{\"writeid\":4,\"bucketid\":536870913,\"rowid\":0}\t1\tlatestvalue_1",
    "{\"writeid\":3,\"bucketid\":536870913,\"rowid\":0}\t2\tnewestvalue_2",
    "{\"writeid\":1,\"bucketid\":536870912,\"rowid\":2}\t3\tvalue_3",
    "{\"writeid\":4,\"bucketid\":536870913,\"rowid\":1}\t4\tlatestvalue_4",
    "{\"writeid\":4,\"bucketid\":536870913,\"rowid\":2}\t5\tlatestvalue_5",
    "{\"writeid\":3,\"bucketid\":536870913,\"rowid\":2}\t6\tnewestvalue_6",
    "{\"writeid\":1,\"bucketid\":536870912,\"rowid\":6}\t7\tvalue_7",
    "{\"writeid\":1,\"bucketid\":536870912,\"rowid\":7}\t8\tvalue_8",
    "{\"writeid\":4,\"bucketid\":536870913,\"rowid\":3}\t9\tlatestvalue_9",
    "{\"writeid\":3,\"bucketid\":536870913,\"rowid\":3}\t10\tnewestvalue_10",
    "{\"writeid\":4,\"bucketid\":536870913,\"rowid\":4}\t11\tlatestvalue_11",
    "{\"writeid\":2,\"bucketid\":536870914,\"rowid\":3}\t12\tvalue_12",
    "{\"writeid\":4,\"bucketid\":536870913,\"rowid\":5}\t13\tlatestvalue_13",
    "{\"writeid\":3,\"bucketid\":536870914,\"rowid\":1}\t14\tvalue_14",
    "{\"writeid\":4,\"bucketid\":536870914,\"rowid\":0}\t15\tvalue_15");
    List<String> rsBucket0 =
        executeStatementOnDriverAndReturnResults("select ROW__ID, * from " + tableName + " order by id", driver);
    Assert.assertEquals(expectedRsBucket0, rsBucket0);
    // Verify all contents
    List<String> actualData = dataProvider.getAllData(tableName);
    Assert.assertEquals(expectedData, actualData);
    // Clean up
    dataProvider.dropTable(tableName);
    msClient.close();
  }

  private void runMergeStatement(String tableName, List<String> values) throws Exception {
    executeStatementOnDriver("DROP TABLE IF EXISTS merge_source", driver);
    executeStatementOnDriver("CREATE TABLE merge_source(id int,value string) STORED AS ORC", driver);
    StringBuilder sb = new StringBuilder();
    for (String value : values) {
      sb.append("(");
      sb.append(value);
      sb.append("),");
    }
    executeStatementOnDriver("INSERT INTO merge_source VALUES " + sb.toString().substring(0, sb.length() - 1), driver);
    executeStatementOnDriver("MERGE INTO " + tableName
        + " AS T USING merge_source AS S ON T.ID = S.ID WHEN MATCHED AND (T.value != S.value AND S.value IS NOT NULL) THEN UPDATE SET value = S.value WHEN NOT MATCHED THEN INSERT VALUES (S.ID, S.value)",
        driver);
    executeStatementOnDriver("DROP TABLE merge_source", driver);
  }

  /**
   * Make sure db is specified in compaction queries.
   */
  private void testCompactionDb(CompactionType compactionType, String resultDirName)
      throws Exception {
    String dbName = "myDb";
    String tableName = "testCompactionDb";
    // Create test table
    TestDataProvider dataProvider = new TestDataProvider();
    dataProvider.createDb(dbName);
    dataProvider.createFullAcidTable(dbName, tableName, false, false);
    // Find the location of the table
    IMetaStoreClient metaStoreClient = new HiveMetaStoreClient(conf);
    Table table = metaStoreClient.getTable(dbName, tableName);
    FileSystem fs = FileSystem.get(conf);
    // Insert test data into test table
    dataProvider.insertTestData(dbName, tableName);
    // Get all data before compaction is run
    List<String> expectedData = dataProvider.getAllData(dbName, tableName, false);
    Collections.sort(expectedData);
    // Run a compaction
    CompactorTestUtil.runCompaction(conf, dbName, tableName, compactionType, true);
    CompactorTestUtil.runCleaner(conf);
    verifySuccessfulCompaction(1);
    // Verify directories after compaction
    PathFilter pathFilter = compactionType == CompactionType.MAJOR ? AcidUtils.baseFileFilter :
        AcidUtils.deltaFileFilter;
    Assert.assertEquals("Result directory does not match after " + compactionType.name()
            + " compaction", Collections.singletonList(resultDirName),
        CompactorTestUtil.getBaseOrDeltaNames(fs, pathFilter, table, null));
    // Verify all contents
    List<String> actualData = dataProvider.getAllData(dbName, tableName, false);
    Assert.assertEquals(expectedData, actualData);
  }

  @Test public void testVectorizationOff() throws Exception {
    conf.setBoolVar(HiveConf.ConfVars.HIVE_VECTORIZATION_ENABLED, false);
    testMinorCompactionAfterMajor();
  }

  /**
   * Read file, and
   * 1. make sure that the bucket property in each row matches the file name.
   * For example, if the bucketId is 0, we check file bucket_00000 to make sure that the third
   * column contains only the value 536870912.
   * 2. make sure that rowIds are in ascending order
   * @param fs file system
   * @param path where to look for the bucket file
   * @param bucketId bucket Id to check, e.g. 0.
   */
  private void checkBucketIdAndRowIdInAcidFile(FileSystem fs, Path path, int bucketId) throws IOException {
    Path bucketFilePath = AcidUtils.createBucketFile(path, bucketId);
    Reader orcReader = OrcFile.createReader(bucketFilePath,
        OrcFile.readerOptions(fs.getConf()).filesystem(fs));
    TypeDescription schema = orcReader.getSchema();
    try (RecordReader rows = orcReader.rows()) {
      VectorizedRowBatch batch = schema.createRowBatch();
      rows.nextBatch(batch);
      // check that bucket property in each row matches the bucket in the file name
      long[] bucketIdVector = ((LongColumnVector) batch.cols[2]).vector;
      for (int i = 0; i < batch.count(); i++) {
        Assert.assertEquals(bucketId, decodeBucketProperty(bucketIdVector[i]));
      }
      // check that writeIds, then rowIds are sorted in ascending order
      long[] writeIdVector = ((LongColumnVector) batch.cols[1]).vector;
      long[] rowIdVector = ((LongColumnVector) batch.cols[3]).vector;
      long writeId = writeIdVector[0];
      long rowId = 0;
      for (int i = 0; i < batch.count(); i++) {
        long currentWriteId = writeIdVector[i];
        long currentRowId = rowIdVector[i];
        if (writeId == writeIdVector[i]) {
          Assert.assertTrue(rowId <= currentRowId);
          rowId = currentRowId;
        } else {
          Assert.assertTrue(writeId < currentWriteId);
          writeId = currentWriteId;
          rowId = 0;
        }
      }
    }
  }

  private void checkBloomFilterInAcidFile(FileSystem fs, Path bucketFilePath) throws IOException {
    Reader orcReader = OrcFile.createReader(bucketFilePath,
        OrcFile.readerOptions(fs.getConf()).filesystem(fs));
    StripeInformation stripe = orcReader.getStripes().get(0);
    try (RecordReaderImpl rows = (RecordReaderImpl)orcReader.rows()) {
      boolean bloomFilter = rows.readStripeFooter(stripe).getStreamsList().stream().anyMatch(
          s -> s.getKind() == OrcProto.Stream.Kind.BLOOM_FILTER_UTF8
              || s.getKind() == OrcProto.Stream.Kind.BLOOM_FILTER);
      Assert.assertTrue("Bloom filter is missing", bloomFilter);
    }
  }

  /**
   * Couldn't find any way to get the bucket property from BucketCodec, so just reverse
   * engineered the encoding. The actual bucketId is represented by bits 2-11 of 29 bits
   */
  private int decodeBucketProperty(long bucketCodec) {
    return (int) ((bucketCodec >> 16) & (0xFFF));
  }

  /**
   * Tests whether hive.llap.io.etl.skip.format config is handled properly whenever QueryCompactor#runCompactionQueries
   * is invoked.
   * @throws Exception
   */
  @Test
  public void testLlapCacheOffDuringCompaction() throws Exception {
    // Setup
    QueryCompactor qc = new QueryCompactor() {
      @Override
      void runCompaction(HiveConf hiveConf, Table table, Partition partition, StorageDescriptor storageDescriptor,
                         ValidWriteIdList writeIds, CompactionInfo compactionInfo, AcidDirectory dir) throws IOException {
      }

      @Override
      protected void commitCompaction(String dest, String tmpTableName, HiveConf conf, ValidWriteIdList actualWriteIds,
                                      long compactorTxnId) throws IOException, HiveException {
      }
    };
    StorageDescriptor sdMock = mock(StorageDescriptor.class);
    doAnswer(invocationOnMock -> {
      return null;
    }).when(sdMock).getLocation();
    CompactionInfo ciMock = mock(CompactionInfo.class);
    ciMock.runAs = "hive";
    List<String> emptyQueries = new ArrayList<>();
    HiveConf hiveConf = new HiveConf();
    hiveConf.set(ValidTxnList.VALID_TXNS_KEY, "8:9223372036854775807::");

    // Check for default case.
    qc.runCompactionQueries(hiveConf, null, sdMock, null, ciMock, null, emptyQueries, emptyQueries, emptyQueries, null);
    Assert.assertEquals("all", hiveConf.getVar(HiveConf.ConfVars.LLAP_IO_ETL_SKIP_FORMAT));

    // Check for case where  hive.llap.io.etl.skip.format is explicitly set to none - as to always use cache.
    hiveConf.setVar(HiveConf.ConfVars.LLAP_IO_ETL_SKIP_FORMAT, "none");
    qc.runCompactionQueries(hiveConf, null, sdMock, null, ciMock, null, emptyQueries, emptyQueries, emptyQueries, null);
    Assert.assertEquals("none", hiveConf.getVar(HiveConf.ConfVars.LLAP_IO_ETL_SKIP_FORMAT));
  }

  @Test
  public void testIfEmptyBaseIsPresentAfterCompaction() throws Exception {
    String dbName = "default";
    String tblName = "empty_table";

    // Setup of LOAD INPATH scenario.
    executeStatementOnDriver("drop table if exists " + tblName, driver);
    executeStatementOnDriver("create table " + tblName + " (a string) stored as orc " +
            "TBLPROPERTIES ('transactional'='true')", driver);
    executeStatementOnDriver("insert into " + tblName + " values ('a')", driver);
    executeStatementOnDriver("delete from " + tblName + " where a='a'", driver);

    // Run a query-based MAJOR compaction
    CompactorTestUtil.runCompaction(conf, dbName, tblName, CompactionType.MAJOR, true);
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);

    IMetaStoreClient hmsClient = new HiveMetaStoreClient(conf);
    Table table = hmsClient.getTable(dbName, tblName);
    FileSystem fs = FileSystem.get(conf);

    FileStatus[] fileStatuses = fs.listStatus(new Path(table.getSd().getLocation()));
    // There should be only dir
    Assert.assertEquals(1, fileStatuses.length);
    Path basePath = fileStatuses[0].getPath();
    // And it's a base
    Assert.assertTrue(AcidUtils.baseFileFilter.accept(basePath));
    RemoteIterator<LocatedFileStatus> filesInBase = fs.listFiles(basePath, true);
    // It has no files in it
    Assert.assertFalse(filesInBase.hasNext());
  }

  @Test
  public void testNonAcidToAcidConversionWithNestedTableWithUnionSubdir() throws Exception {
    String dbName = "default";

    // Helper table for the union all insert
    String helperTblName = "helper_table";
    executeStatementOnDriver("drop table if exists " + helperTblName, driver);
    executeStatementOnDriver("create table " + helperTblName + " (a int, b int) stored as orc " +
            "TBLPROPERTIES ('transactional'='false')", driver);
    executeStatementOnDriver("insert into " + helperTblName + " values (1, 1), (2, 2)", driver);

    // Non acid nested table with union subdirs
    String tblName = "non_acid_nested";
    executeStatementOnDriver("drop table if exists " + tblName, driver);
    executeStatementOnDriver("create table " + tblName +
            "(a int, b int) partitioned by (p string, q string) stored as orc TBLPROPERTIES ('transactional'='false')", driver);

    // Insert some union data
    executeStatementOnDriver("insert into " + tblName + " partition(p='p1',q='q1') " +
            "select a,b from " + helperTblName + " union all select a,b from " + helperTblName, driver);

    // Some sanity checks
    List<String> result = execSelectAndDumpData("select * from " + tblName, driver, tblName);
    Assert.assertEquals(4, result.size());

    // Convert the table to acid
    executeStatementOnDriver("alter table " + tblName + " SET TBLPROPERTIES ('transactional'='true')", driver);

    // Run a query-based MAJOR compaction
    CompactorTestUtil.runCompaction(conf, dbName, tblName, CompactionType.MAJOR, true, "p=p1/q=q1");
    // Clean up resources
    CompactorTestUtil.runCleaner(conf);

    // Verify file level
    IMetaStoreClient hmsClient = new HiveMetaStoreClient(conf);
    Table table = hmsClient.getTable(dbName, tblName);
    FileSystem fs = FileSystem.get(conf);

    Path tablePath = new Path(table.getSd().getLocation());

    // Partition lvl1
    FileStatus[] fileStatuses = fs.listStatus(tablePath);
    Assert.assertEquals(1, fileStatuses.length);
    String partitionName1 = fileStatuses[0].getPath().getName();
    Assert.assertEquals("p=p1", partitionName1);

    // Partition lvl2
    fileStatuses = fs.listStatus(new Path(table.getSd().getLocation() + "/" + partitionName1));
    Assert.assertEquals(1, fileStatuses.length);
    String partitionName2 = fileStatuses[0].getPath().getName();
    Assert.assertEquals("q=q1", partitionName2);

    // 1 base should be here
    fileStatuses = fs.listStatus(new Path(table.getSd().getLocation() + "/" + partitionName1 + "/" + partitionName2));
    Assert.assertEquals(1, fileStatuses.length);
    String baseName = fileStatuses[0].getPath().getName();
    Assert.assertEquals("base_10000000_v0000009", baseName);
  }

  @Test
  public void testCompactionShouldNotFailOnStructField() throws Exception {
    conf.setBoolVar(HiveConf.ConfVars.COMPACTOR_CRUD_QUERY_BASED, true);
    String dbName = "default";
    String tblName = "compaction_hive_26374";

    TxnStore txnHandler = TxnUtils.getTxnStore(conf);
    TestDataProvider testDP = new TestDataProvider();

    // Create test table
    executeStatementOnDriver("drop table if exists " + tblName, driver);
    executeStatementOnDriver("CREATE TABLE " + tblName + "(col1 array<struct<arr_col1:int, `timestamp`:string>>)" +
            "STORED AS ORC TBLPROPERTIES('transactional'='true')", driver);

    // Insert test data into test table
    executeStatementOnDriver("INSERT INTO TABLE " + tblName +
            " SELECT ARRAY(NAMED_STRUCT('arr_col1',1,'timestamp','2022-07-05 21:51:20.371'))",driver);
    executeStatementOnDriver("INSERT INTO TABLE " + tblName +
            " SELECT ARRAY(NAMED_STRUCT('arr_col1',2,'timestamp','2022-07-05 21:51:20.371'))",driver);

    // Find the location of the table
    IMetaStoreClient msClient = new HiveMetaStoreClient(conf);
    Table table = msClient.getTable(dbName, tblName);
    FileSystem fs = FileSystem.get(conf);
    // Verify deltas (delta_0000001_0000001_0000, delta_0000002_0000002_0000) are present
    Assert.assertEquals("Delta directories does not match before compaction",
            Arrays.asList("delta_0000001_0000001_0000", "delta_0000002_0000002_0000"),
            CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.deltaFileFilter, table, null));

    // Get all data before compaction is run
    List<String> expectedData = testDP.getAllData(tblName);

    //Do a compaction directly and wait for it to finish
    CompactionRequest rqst = new CompactionRequest(dbName, tblName, CompactionType.MAJOR);
    CompactionResponse resp = txnHandler.compact(rqst);
    runWorker(conf);

    CompactorTestUtil.runCleaner(conf);

    //Check if the compaction succeed
    ShowCompactResponse rsp = txnHandler.showCompact(new ShowCompactRequest());
    List<ShowCompactResponseElement> compacts = rsp.getCompacts();
    Assert.assertEquals("Expecting 1 rows and found " + compacts.size(), 1, compacts.size());
    Assert.assertEquals("Expecting compaction state 'succeeded' and found:" + compacts.get(0).getState(),
            "succeeded", compacts.get(0).getState());
    // Should contain only one base directory now
    FileStatus[] status = fs.listStatus(new Path(table.getSd().getLocation()));
    int inputFileCount = 0;
    for(FileStatus file: status) {
      inputFileCount++;
    }
    Assert.assertEquals("Expecting 1 file and found "+ inputFileCount, 1, inputFileCount);

    // Check bucket file name
    List<String> baseDir = CompactorTestUtil.getBaseOrDeltaNames(fs, AcidUtils.baseFileFilter, table, null);
    List<String> expectedBucketFiles = Arrays.asList("bucket_00000");
    Assert.assertEquals("Bucket names are not matching after compaction", expectedBucketFiles,
            CompactorTestUtil
                    .getBucketFileNames(fs, table, null, baseDir.get(0)));

    // Verify all contents
    List<String> actualData = testDP.getAllData(tblName);
    Assert.assertEquals(expectedData, actualData);
  }

  @Test
  public void testCompactionWithCreateTableProps() throws Exception {
    conf.setBoolVar(HiveConf.ConfVars.COMPACTOR_CRUD_QUERY_BASED, true);
    conf.setVar(HiveConf.ConfVars.HIVE_PROTO_EVENTS_BASE_PATH, tmpFolder);

    String dbName = "default";
    String tblName = "comp_with_create_tblprops_test";
    String dbTableName = dbName + "." + tblName;

    TxnStore txnHandler = TxnUtils.getTxnStore(conf);
    TestDataProvider testDP = new TestDataProvider();

    // Create test table
    executeStatementOnDriver("drop table if exists " + tblName, driver);
    executeStatementOnDriver("CREATE TABLE " + tblName + "(col1 array<struct<arr_col1:int, `timestamp`:string>>)" +
            "STORED AS ORC TBLPROPERTIES('transactional'='true', 'compactor.tez.task.resource.memory.mb'='8000')", driver);

    // Insert test data into test table
    executeStatementOnDriver("INSERT INTO TABLE " + tblName +
            " SELECT ARRAY(NAMED_STRUCT('arr_col1',1,'timestamp','2022-07-05 21:51:20.371'))",driver);
    executeStatementOnDriver("INSERT INTO TABLE " + tblName +
            " SELECT ARRAY(NAMED_STRUCT('arr_col1',2,'timestamp','2022-07-06 21:51:20.371'))",driver);

    // Get all data before compaction is run
    List<String> expectedData = testDP.getAllData(tblName);

    // Initiate a compaction request.
    CompactionRequest rqst = new CompactionRequest(dbName, tblName, CompactionType.MAJOR);
    CompactionResponse resp = txnHandler.compact(rqst);

    conf.setVar(HiveConf.ConfVars.PREEXECHOOKS, HiveProtoLoggingHook.class.getName());
    // Run major compaction and cleaner
    runWorker(conf);
    conf.setVar(HiveConf.ConfVars.PREEXECHOOKS, StringUtils.EMPTY);

    CompactorTestUtil.runCleaner(conf);

    //Check if the compaction succeeds
    ShowCompactResponse rsp = txnHandler.showCompact(new ShowCompactRequest());
    List<ShowCompactResponseElement> compacts = rsp.getCompacts();
    Assert.assertEquals("Expecting 1 rows and found " + compacts.size(), 1, compacts.size());
    Assert.assertEquals("Expecting compaction state 'succeeded' and found:" + compacts.get(0).getState(),
            "succeeded", compacts.get(0).getState());

    IMetaStoreClient msClient = new HiveMetaStoreClient(conf);
    Table table = msClient.getTable(dbName, tblName);

    FileSystem fs = FileSystem.get(conf);
    FileStatus[] fileStatus = fs.listStatus(new Path(table.getSd().getLocation()));
    for(FileStatus file: fileStatus) {
      Assert.assertTrue(file.getPath().getName().startsWith(AcidUtils.BASE_PREFIX));
    }

    // Verify all contents
    List<String> actualData = testDP.getAllData(tblName);
    Assert.assertEquals(expectedData, actualData);

    HiveHookEvents.HiveHookEventProto event = getRelatedTezEvent(dbTableName);
    Assert.assertNotNull(event);

    for (org.apache.hadoop.hive.ql.hooks.proto.HiveHookEvents.MapFieldEntry mapFieldEntry: event.getOtherInfoList()) {
      if (mapFieldEntry.getKey().equalsIgnoreCase("CONF")) {
        Assert.assertTrue(mapFieldEntry.getValue().contains("\"tez.task.resource.memory.mb\":\"8000\""));
      }
    }
  }

  @Test
  public void testCompactionWithAlterTableProps() throws Exception {
    conf.setBoolVar(HiveConf.ConfVars.COMPACTOR_CRUD_QUERY_BASED, true);
    conf.setVar(HiveConf.ConfVars.HIVE_PROTO_EVENTS_BASE_PATH, tmpFolder);

    String dbName = "default";
    String tblName = "comp_with_alter_tblprops_test";
    String dbTableName = dbName + "." + tblName;

    TxnStore txnHandler = TxnUtils.getTxnStore(conf);
    TestDataProvider testDP = new TestDataProvider();

    // Create test table
    executeStatementOnDriver("drop table if exists " + tblName, driver);
    executeStatementOnDriver("CREATE TABLE " + tblName + "(col1 array<struct<arr_col1:int, `timestamp`:string>>)" +
            "STORED AS ORC TBLPROPERTIES('transactional'='true', 'compactor.tez.task.resource.memory.mb'='8000')", driver);

    // Insert test data into test table
    executeStatementOnDriver("INSERT INTO TABLE " + tblName +
            " SELECT ARRAY(NAMED_STRUCT('arr_col1',1,'timestamp','2022-07-05 21:51:20.371'))",driver);
    executeStatementOnDriver("INSERT INTO TABLE " + tblName +
            " SELECT ARRAY(NAMED_STRUCT('arr_col1',2,'timestamp','2022-07-05 21:51:20.371'))",driver);

    executeStatementOnDriver("ALTER TABLE " + tblName + " COMPACT 'major' WITH OVERWRITE TBLPROPERTIES " +
            "('compactor.tez.task.resource.memory.mb'='5000')", driver);

    // Get all data before compaction is run
    List<String> expectedData = testDP.getAllData(tblName);

    conf.setVar(HiveConf.ConfVars.PREEXECHOOKS, HiveProtoLoggingHook.class.getName());
    // Run major compaction and cleaner
    runWorker(conf);
    conf.setVar(HiveConf.ConfVars.PREEXECHOOKS, StringUtils.EMPTY);

    CompactorTestUtil.runCleaner(conf);

    //Check if the compaction succeeds
    ShowCompactResponse rsp = txnHandler.showCompact(new ShowCompactRequest());
    List<ShowCompactResponseElement> compacts = rsp.getCompacts();
    Assert.assertEquals("Expecting 1 rows and found " + compacts.size(), 1, compacts.size());
    Assert.assertEquals("Expecting compaction state 'succeeded' and found:" + compacts.get(0).getState(),
            "succeeded", compacts.get(0).getState());

    IMetaStoreClient msClient = new HiveMetaStoreClient(conf);
    Table table = msClient.getTable(dbName, tblName);

    FileSystem fs = FileSystem.get(conf);
    FileStatus[] fileStatus = fs.listStatus(new Path(table.getSd().getLocation()));
    for(FileStatus file: fileStatus) {
      Assert.assertTrue(file.getPath().getName().startsWith(AcidUtils.BASE_PREFIX));
    }

    // Verify all contents
    List<String> actualData = testDP.getAllData(tblName);
    Assert.assertEquals(expectedData, actualData);

    HiveHookEvents.HiveHookEventProto event = getRelatedTezEvent(dbTableName);
    Assert.assertNotNull(event);

    for (org.apache.hadoop.hive.ql.hooks.proto.HiveHookEvents.MapFieldEntry mapFieldEntry: event.getOtherInfoList()) {
      if (mapFieldEntry.getKey().equalsIgnoreCase("CONF")) {
        Assert.assertTrue(mapFieldEntry.getValue().contains("\"tez.task.resource.memory.mb\":\"5000\""));
      }
    }
  }
}
