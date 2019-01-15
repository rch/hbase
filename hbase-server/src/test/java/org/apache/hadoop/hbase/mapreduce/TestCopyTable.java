/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.mapreduce;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.testclassification.LargeTests;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.LauncherSecurityManager;
import org.apache.hadoop.util.ToolRunner;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Basic test for the CopyTable M/R tool
 */
@Category(LargeTests.class)
public class TestCopyTable {
  private static final HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
  private static final byte[] ROW1 = Bytes.toBytes("row1");
  private static final byte[] ROW2 = Bytes.toBytes("row2");
  private static final String FAMILY_A_STRING = "a";
  private static final String FAMILY_B_STRING = "b";
  private static final byte[] FAMILY_A = Bytes.toBytes(FAMILY_A_STRING);
  private static final byte[] FAMILY_B = Bytes.toBytes(FAMILY_B_STRING);
  private static final byte[] QUALIFIER = Bytes.toBytes("q");


  @BeforeClass
  public static void beforeClass() throws Exception {
    TEST_UTIL.setJobWithoutMRCluster();
    TEST_UTIL.startMiniCluster(3);
  }

  @AfterClass
  public static void afterClass() throws Exception {
    TEST_UTIL.shutdownMiniCluster();
  }

  private void doCopyTableTest(boolean bulkload) throws Exception {
    final TableName TABLENAME1 = TableName.valueOf("testCopyTable1");
    final TableName TABLENAME2 = TableName.valueOf("testCopyTable2");
    final byte[] FAMILY = Bytes.toBytes("family");
    final byte[] COLUMN1 = Bytes.toBytes("c1");

    try (Table t1 = TEST_UTIL.createTable(TABLENAME1, FAMILY);
         Table t2 = TEST_UTIL.createTable(TABLENAME2, FAMILY);) {
      // put rows into the first table
      loadData(t1, FAMILY, COLUMN1);

      CopyTable copy = new CopyTable(TEST_UTIL.getConfiguration());
      int code;
      if (bulkload) {
        code = ToolRunner.run(new Configuration(TEST_UTIL.getConfiguration()),
            copy, new String[] { "--new.name=" + TABLENAME2.getNameAsString(),
            "--bulkload", TABLENAME1.getNameAsString() });
      } else {
        code = ToolRunner.run(new Configuration(TEST_UTIL.getConfiguration()),
            copy, new String[] { "--new.name=" + TABLENAME2.getNameAsString(),
            TABLENAME1.getNameAsString() });
      }
      assertEquals("copy job failed", 0, code);

      // verify the data was copied into table 2
      verifyRows(t2, FAMILY, COLUMN1);
    } finally {
      TEST_UTIL.deleteTable(TABLENAME1);
      TEST_UTIL.deleteTable(TABLENAME2);
    }
  }

  /**
   * Simple end-to-end test
   * @throws Exception
   */
  @Test
  public void testCopyTable() throws Exception {
    doCopyTableTest(false);
  }

  /**
   * Simple end-to-end test with bulkload.
   */
  @Test
  public void testCopyTableWithBulkload() throws Exception {
    doCopyTableTest(true);
  }

  @Test
  public void testStartStopRow() throws Exception {
    final TableName TABLENAME1 = TableName.valueOf("testStartStopRow1");
    final TableName TABLENAME2 = TableName.valueOf("testStartStopRow2");
    final byte[] FAMILY = Bytes.toBytes("family");
    final byte[] COLUMN1 = Bytes.toBytes("c1");
    final byte[] ROW0 = Bytes.toBytesBinary("\\x01row0");
    final byte[] ROW1 = Bytes.toBytesBinary("\\x01row1");
    final byte[] ROW2 = Bytes.toBytesBinary("\\x01row2");

    Table t1 = TEST_UTIL.createTable(TABLENAME1, FAMILY);
    Table t2 = TEST_UTIL.createTable(TABLENAME2, FAMILY);

    // put rows into the first table
    Put p = new Put(ROW0);
    p.add(FAMILY, COLUMN1, COLUMN1);
    t1.put(p);
    p = new Put(ROW1);
    p.add(FAMILY, COLUMN1, COLUMN1);
    t1.put(p);
    p = new Put(ROW2);
    p.add(FAMILY, COLUMN1, COLUMN1);
    t1.put(p);

    CopyTable copy = new CopyTable(TEST_UTIL.getConfiguration());
    assertEquals(
      0,
      copy.run(new String[] { "--new.name=" + TABLENAME2, "--startrow=\\x01row1",
          "--stoprow=\\x01row2", TABLENAME1.getNameAsString() }));

    // verify the data was copied into table 2
    // row1 exist, row0, row2 do not exist
    Get g = new Get(ROW1);
    Result r = t2.get(g);
    assertEquals(1, r.size());
    assertTrue(CellUtil.matchingQualifier(r.rawCells()[0], COLUMN1));

    g = new Get(ROW0);
    r = t2.get(g);
    assertEquals(0, r.size());

    g = new Get(ROW2);
    r = t2.get(g);
    assertEquals(0, r.size());

    t1.close();
    t2.close();
    TEST_UTIL.deleteTable(TABLENAME1);
    TEST_UTIL.deleteTable(TABLENAME2);
  }

  /**
   * Test copy of table from sourceTable to targetTable all rows from family a
   */
  @Test
  public void testRenameFamily() throws Exception {
    String sourceTable = "sourceTable";
    String targetTable = "targetTable";

    byte[][] families = { FAMILY_A, FAMILY_B };

    Table t = TEST_UTIL.createTable(Bytes.toBytes(sourceTable), families);
    Table t2 = TEST_UTIL.createTable(Bytes.toBytes(targetTable), families);
    Put p = new Put(ROW1);
    p.add(FAMILY_A, QUALIFIER,  Bytes.toBytes("Data11"));
    p.add(FAMILY_B, QUALIFIER,  Bytes.toBytes("Data12"));
    p.add(FAMILY_A, QUALIFIER,  Bytes.toBytes("Data13"));
    t.put(p);
    p = new Put(ROW2);
    p.add(FAMILY_B, QUALIFIER, Bytes.toBytes("Dat21"));
    p.add(FAMILY_A, QUALIFIER, Bytes.toBytes("Data22"));
    p.add(FAMILY_B, QUALIFIER, Bytes.toBytes("Data23"));
    t.put(p);

    long currentTime = System.currentTimeMillis();
    String[] args = new String[] { "--new.name=" + targetTable, "--families=a:b", "--all.cells",
        "--starttime=" + (currentTime - 100000), "--endtime=" + (currentTime + 100000),
        "--versions=1", sourceTable };
    assertNull(t2.get(new Get(ROW1)).getRow());

    assertTrue(runCopy(args));

    assertNotNull(t2.get(new Get(ROW1)).getRow());
    Result res = t2.get(new Get(ROW1));
    byte[] b1 = res.getValue(FAMILY_B, QUALIFIER);
    assertEquals("Data13", new String(b1));
    assertNotNull(t2.get(new Get(ROW2)).getRow());
    res = t2.get(new Get(ROW2));
    b1 = res.getValue(FAMILY_A, QUALIFIER);
    // Data from the family of B is not copied
    assertNull(b1);

  }

  /**
   * Test main method of CopyTable.
   */
  @Test
  public void testMainMethod() throws Exception {
    String[] emptyArgs = { "-h" };
    PrintStream oldWriter = System.err;
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    PrintStream writer = new PrintStream(data);
    System.setErr(writer);
    SecurityManager SECURITY_MANAGER = System.getSecurityManager();
    LauncherSecurityManager newSecurityManager= new LauncherSecurityManager();
    System.setSecurityManager(newSecurityManager);
    try {
      CopyTable.main(emptyArgs);
      fail("should be exit");
    } catch (SecurityException e) {
      assertEquals(1, newSecurityManager.getExitCode());
    } finally {
      System.setErr(oldWriter);
      System.setSecurityManager(SECURITY_MANAGER);
    }
    assertTrue(data.toString().contains("rs.class"));
    // should print usage information
    assertTrue(data.toString().contains("Usage:"));
  }

  private boolean runCopy(String[] args) throws Exception {
    CopyTable copy = new CopyTable(TEST_UTIL.getConfiguration());
    int code = ToolRunner.run(new Configuration(TEST_UTIL.getConfiguration()), copy, args);
    return code == 0;
  }

  private void loadData(Table t, byte[] family, byte[] column) throws IOException {
    for (int i = 0; i < 10; i++) {
      byte[] row = Bytes.toBytes("row" + i);
      Put p = new Put(row);
      p.addColumn(family, column, row);
      t.put(p);
    }
  }

  private void verifyRows(Table t, byte[] family, byte[] column) throws IOException {
    for (int i = 0; i < 10; i++) {
      byte[] row = Bytes.toBytes("row" + i);
      Get g = new Get(row).addFamily(family);
      Result r = t.get(g);
      Assert.assertNotNull(r);
      Assert.assertEquals(1, r.size());
      Cell cell = r.rawCells()[0];
      Assert.assertTrue(CellUtil.matchingQualifier(cell, column));
      Assert.assertEquals(Bytes.compareTo(cell.getValueArray(), cell.getValueOffset(),
        cell.getValueLength(), row, 0, row.length), 0);
    }
  }

  private void testCopyTableBySnapshot(String tablePrefix, boolean bulkLoad)
      throws Exception {
    TableName table1 = TableName.valueOf(tablePrefix + 1);
    TableName table2 = TableName.valueOf(tablePrefix + 2);
    Table t1 = TEST_UTIL.createTable(table1, FAMILY_A);
    Table t2 = TEST_UTIL.createTable(table2, FAMILY_A);
    loadData(t1, FAMILY_A, Bytes.toBytes("qualifier"));
    String snapshot = tablePrefix + "_snapshot";
    TEST_UTIL.getHBaseAdmin().snapshot(snapshot, table1);
    boolean success;
    if (bulkLoad) {
      success =
          runCopy(new String[] { "--snapshot", "--new.name=" + table2, "--bulkload", snapshot });
    } else {
      success = runCopy(new String[] { "--snapshot", "--new.name=" + table2, snapshot });
    }
    Assert.assertTrue(success);
    verifyRows(t2, FAMILY_A, Bytes.toBytes("qualifier"));
  }

  @Test
  public void testLoadingSnapshotToTable() throws Exception {
    testCopyTableBySnapshot("testLoadingSnapshotToTable", false);
  }

  @Test
  public void testLoadingSnapshotAndBulkLoadToTable() throws Exception {
    testCopyTableBySnapshot("testLoadingSnapshotAndBulkLoadToTable", true);
  }

  @Test
  public void testLoadingSnapshotToRemoteCluster() throws Exception {
    Assert.assertFalse(runCopy(
      new String[] { "--snapshot", "--peerAdr=hbase://remoteHBase", "sourceSnapshotName" }));
  }

  @Test
  public void testLoadingSnapshotWithoutSnapshotName() throws Exception {
    Assert.assertFalse(runCopy(new String[] { "--snapshot", "--peerAdr=hbase://remoteHBase" }));
  }

  @Test
  public void testLoadingSnapshotWithoutDestTable() throws Exception {
    Assert.assertFalse(runCopy(new String[] { "--snapshot", "sourceSnapshotName" }));
  }
}
