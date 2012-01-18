/*
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
package org.apache.hcatalog.hbase.snapshot;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hcatalog.hbase.snapshot.lock.LockListener;
import org.apache.hcatalog.hbase.snapshot.lock.WriteLock;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;

/**
 * The service for providing revision management to Hbase tables.
 */
public class ZKBasedRevisionManager implements RevisionManager{

    public static final String HOSTLIST = "revision.manager.zk.HostList";
    public static final String DATADIR = "revision.manager.zk.DataDir";
    public static final String DEFAULT_DATADIR = "/revision-management";
    public static final String DEFAULT_HOSTLIST = "localhost:2181";
    private static  int DEFAULT_WRITE_TRANSACTION_TIMEOUT = 14400000;
    private static final Log LOG = LogFactory.getLog(ZKBasedRevisionManager.class);
    private String zkHostList;
    private String baseDir;
    private ZKUtil zkUtil;


    /*
     * @see org.apache.hcatalog.hbase.snapshot.RevisionManager#initialize()
     */
    @Override
    public void initialize(Properties properties) {
        this.zkHostList = properties.getProperty(
                ZKBasedRevisionManager.HOSTLIST,
                ZKBasedRevisionManager.DEFAULT_HOSTLIST);
        this.baseDir = properties.getProperty(ZKBasedRevisionManager.DATADIR,
                ZKBasedRevisionManager.DEFAULT_DATADIR);
    }

    /**
     * Open a ZooKeeper connection
     * @throws java.io.IOException
     */

    public void open() throws IOException {
        zkUtil = new ZKUtil(zkHostList, this.baseDir);
        zkUtil.createRootZNodes();
        LOG.info("Created root znodes for revision manager.");
    }

    /**
     * Close Zookeeper connection
     */
    public void close() {
        zkUtil.closeZKConnection();
    }

    private void checkInputParams(String table, List<String> families) {
        if (table == null) {
            throw new IllegalArgumentException(
                    "The table name must be specified for reading.");
        }
        if (families == null || families.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one column family should be specified for reading.");
        }
    }


    /* @param table
    /* @param families
    /* @param keepAlive
    /* @return
    /* @throws IOException
     * @see org.apache.hcatalog.hbase.snapshot.RevisionManager#beginWriteTransaction(java.lang.String, java.util.List, long)
     */
    public Transaction beginWriteTransaction(String table,
            List<String> families, long keepAlive) throws IOException {

        checkInputParams(table, families);
        zkUtil.setUpZnodesForTable(table, families);
        long nextId = zkUtil.nextId(table);
        long expireTimestamp = zkUtil.getTimeStamp();
        Transaction transaction = new Transaction(table, families, nextId,
                expireTimestamp);
        if (keepAlive != -1) {
            transaction.setKeepAlive(keepAlive);
        } else {
            transaction.setKeepAlive(DEFAULT_WRITE_TRANSACTION_TIMEOUT);
        }

        refreshTransactionList(transaction.getTableName());
        String lockPath = prepareLockNode(table);
        WriteLock wLock = new WriteLock(zkUtil.getSession(), lockPath,
                Ids.OPEN_ACL_UNSAFE);
        RMLockListener myLockListener = new RMLockListener();
        wLock.setLockListener(myLockListener);
        try {
            boolean lockGrabbed = wLock.lock();
            if (lockGrabbed == false) {
              //TO DO : Let this request queue up and try obtaining lock.
                throw new IOException(
                        "Unable to obtain lock while beginning transaction. "
                                + transaction.toString());
            } else {
                List<String> colFamilies = transaction.getColumnFamilies();
                FamilyRevision revisionData = transaction.getFamilyRevisionInfo();
                for (String cfamily : colFamilies) {
                    String path = PathUtil.getRunningTxnInfoPath(
                            baseDir, table, cfamily);
                    zkUtil.updateData(path, revisionData,
                            ZKUtil.UpdateMode.APPEND);
                }
            }
        } catch (KeeperException e) {
            throw new IOException("Exception while obtaining lock.", e);
        } catch (InterruptedException e) {
            throw new IOException("Exception while obtaining lock.", e);
        }
        finally {
            wLock.unlock();
        }

        return transaction;
    }

    /* @param table The table name.
    /* @param families The column families involved in the transaction.
    /* @return transaction The transaction which was started.
    /* @throws IOException
     * @see org.apache.hcatalog.hbase.snapshot.RevisionManager#beginWriteTransaction(java.lang.String, java.util.List)
     */
    public Transaction beginWriteTransaction(String table, List<String> families)
            throws IOException {
        return beginWriteTransaction(table, families, -1);
    }

    /**
     * This method commits a write transaction.
     * @param transaction The revision information associated with transaction.
     * @throws java.io.IOException
     */
    public void commitWriteTransaction(Transaction transaction) throws IOException {
        refreshTransactionList(transaction.getTableName());

        String lockPath = prepareLockNode(transaction.getTableName());
        WriteLock wLock = new WriteLock(zkUtil.getSession(), lockPath,
                Ids.OPEN_ACL_UNSAFE);
        RMLockListener myLockListener = new RMLockListener();
        wLock.setLockListener(myLockListener);
        try {
            boolean lockGrabbed = wLock.lock();
            if (lockGrabbed == false) {
              //TO DO : Let this request queue up and try obtaining lock.
                throw new IOException(
                        "Unable to obtain lock while commiting transaction. "
                                + transaction.toString());
            } else {
                String tableName = transaction.getTableName();
                List<String> colFamilies = transaction.getColumnFamilies();
                FamilyRevision revisionData = transaction.getFamilyRevisionInfo();
                for (String cfamily : colFamilies) {
                    String path = PathUtil.getRunningTxnInfoPath(
                            baseDir, tableName, cfamily);
                    zkUtil.updateData(path, revisionData,
                            ZKUtil.UpdateMode.REMOVE);
                }

            }
        } catch (KeeperException e) {
            throw new IOException("Exception while obtaining lock.", e);
        } catch (InterruptedException e) {
            throw new IOException("Exception while obtaining lock.", e);
        }
        finally {
            wLock.unlock();
        }
        LOG.info("Write Transaction committed: " + transaction.toString());
    }

    /**
     * This method aborts a write transaction.
     * @param state the state associated with the Transaction
     * @throws java.io.IOException
     */
    public void abortWriteTransaction(Transaction transaction) throws IOException {

        refreshTransactionList(transaction.getTableName());
        String lockPath = prepareLockNode(transaction.getTableName());
        WriteLock wLock = new WriteLock(zkUtil.getSession(), lockPath,
                Ids.OPEN_ACL_UNSAFE);
        RMLockListener myLockListener = new RMLockListener();
        wLock.setLockListener(myLockListener);
        try {
            boolean lockGrabbed = wLock.lock();
            if (lockGrabbed == false) {
              //TO DO : Let this request queue up and try obtaining lock.
                throw new IOException(
                        "Unable to obtain lock while aborting transaction. "
                                + transaction.toString());
            } else {
                String tableName = transaction.getTableName();
                List<String> colFamilies = transaction.getColumnFamilies();
                FamilyRevision revisionData = transaction
                        .getFamilyRevisionInfo();
                for (String cfamily : colFamilies) {
                    String path = PathUtil.getRunningTxnInfoPath(
                            baseDir, tableName, cfamily);
                    zkUtil.updateData(path, revisionData,
                            ZKUtil.UpdateMode.REMOVE);
                    path = PathUtil.getAbortInformationPath(baseDir,
                            tableName, cfamily);
                    zkUtil.updateData(path, revisionData,
                            ZKUtil.UpdateMode.APPEND);
                }

            }
        } catch (KeeperException e) {
            throw new IOException("Exception while obtaining lock.", e);
        } catch (InterruptedException e) {
            throw new IOException("Exception while obtaining lock.", e);
        }
        finally {
            wLock.unlock();
        }
        LOG.info("Write Transaction aborted: " + transaction.toString());
    }


     /* @param transaction
     /* @throws IOException
      * @see org.apache.hcatalog.hbase.snapshot.RevsionManager#keepAlive(org.apache.hcatalog.hbase.snapshot.Transaction)
      */
     public void keepAlive(Transaction transaction)
            throws IOException {

         refreshTransactionList(transaction.getTableName());
         transaction.keepAliveTransaction();
         String lockPath = prepareLockNode(transaction.getTableName());
         WriteLock wLock = new WriteLock(zkUtil.getSession(), lockPath,
                 Ids.OPEN_ACL_UNSAFE);
         RMLockListener myLockListener = new RMLockListener();
         wLock.setLockListener(myLockListener);
         try {
             boolean lockGrabbed = wLock.lock();
             if (lockGrabbed == false) {
               //TO DO : Let this request queue up and try obtaining lock.
                 throw new IOException(
                         "Unable to obtain lock for keep alive of transaction. "
                                 + transaction.toString());
             }else {
                 String tableName = transaction.getTableName();
                 List<String> colFamilies = transaction.getColumnFamilies();
                 FamilyRevision revisionData = transaction.getFamilyRevisionInfo();
                 for (String cfamily : colFamilies) {
                     String path = PathUtil.getRunningTxnInfoPath(
                             baseDir, tableName, cfamily);
                     zkUtil.updateData(path, revisionData,
                             ZKUtil.UpdateMode.KEEP_ALIVE);
                 }

             }
         } catch (KeeperException e) {
             throw new IOException("Exception while obtaining lock.", e);
         } catch (InterruptedException e) {
             throw new IOException("Exception while obtaining lock.", e);
         }finally {
             wLock.unlock();
         }

    }

    /* This method allows the user to create latest snapshot of a
    /* table.
    /* @param tableName The table whose snapshot is being created.
    /* @return TableSnapshot An instance of TableSnaphot
    /* @throws IOException
     * @see org.apache.hcatalog.hbase.snapshot.RevsionManager#createSnapshot(java.lang.String)
     */
    public TableSnapshot createSnapshot(String tableName) throws IOException{
        refreshTransactionList(tableName);
        long latestID = zkUtil.currentID(tableName);
        HashMap<String, Long> cfMap = new HashMap<String, Long>();
        List<String> columnFamilyNames = zkUtil.getColumnFamiliesOfTable(tableName);

        for(String cfName: columnFamilyNames){
            String cfPath = PathUtil.getRunningTxnInfoPath(baseDir, tableName, cfName);
            List<FamilyRevision> tranxList = zkUtil.getTransactionList(cfPath);
            long version;
            if (!tranxList.isEmpty()) {
                Collections.sort(tranxList);
                // get the smallest running Transaction ID
                long runningVersion = tranxList.get(0).getRevision();
                version = runningVersion -1;
            } else {
                version = latestID;
            }
            cfMap.put(cfName, version);
        }

        return new TableSnapshot(tableName, cfMap);
    }

    /* This method allows the user to create snapshot of a
    /* table with a given revision number.
    /* @param tableName
    /* @param revision
    /* @return TableSnapshot
    /* @throws IOException
     * @see org.apache.hcatalog.hbase.snapshot.RevsionManager#createSnapshot(java.lang.String, long)
     */
    public TableSnapshot createSnapshot(String tableName, long revision) throws IOException{

        long currentID = zkUtil.currentID(tableName);
        if (revision > currentID) {
            throw new IOException(
                    "The revision specified in the snapshot is higher than the current revision of the table.");
        }
        refreshTransactionList(tableName);
        HashMap<String, Long> cfMap = new HashMap<String, Long>();
        List<String> columnFamilies = zkUtil.getColumnFamiliesOfTable(tableName);

        for(String cf: columnFamilies){
            cfMap.put(cf, revision);
        }

        return new TableSnapshot(tableName, cfMap);
    }

    /**
     * Get the list of in-progress Transactions for a column family
     * @param table the table name
     * @param columnFamily the column family name
     * @return a list of in-progress WriteTransactions
     * @throws java.io.IOException
     */
    List<FamilyRevision> getRunningTransactions(String table,
            String columnFamily) throws IOException {
        String path = PathUtil.getRunningTxnInfoPath(baseDir, table,
                columnFamily);
        return zkUtil.getTransactionList(path);
    }

    /**
     * Get the list of aborted Transactions for a column family
     * @param table the table name
     * @param columnFamily the column family name
     * @return a list of aborted WriteTransactions
     * @throws java.io.IOException
     */
     List<FamilyRevision> getAbortedWriteTransactions(String table,
            String columnFamily) throws IOException {
         String path = PathUtil.getAbortInformationPath(baseDir, table, columnFamily);
         return zkUtil.getTransactionList(path);
    }

     private void refreshTransactionList(String tableName) throws IOException{
        String lockPath = prepareLockNode(tableName);
        WriteLock wLock = new WriteLock(zkUtil.getSession(), lockPath,
                Ids.OPEN_ACL_UNSAFE);
        RMLockListener myLockListener = new RMLockListener();
        wLock.setLockListener(myLockListener);
        try {
            boolean lockGrabbed = wLock.lock();
            if (lockGrabbed == false) {
              //TO DO : Let this request queue up and try obtaining lock.
                throw new IOException(
                        "Unable to obtain lock while refreshing transactions of table "
                                + tableName + ".");
            }else {
                List<String> cfPaths = zkUtil
                        .getColumnFamiliesOfTable(tableName);
                for (String cf : cfPaths) {
                    String runningDataPath = PathUtil.getRunningTxnInfoPath(
                            baseDir, tableName, cf);
                    zkUtil.refreshTransactions(runningDataPath);
        }

            }
        } catch (KeeperException e) {
            throw new IOException("Exception while obtaining lock.", e);
        } catch (InterruptedException e) {
            throw new IOException("Exception while obtaining lock.", e);
        } finally {
            wLock.unlock();
        }

     }

     private String prepareLockNode(String tableName) throws IOException{
         String txnDataPath = PathUtil.getTxnDataPath(this.baseDir, tableName);
         String lockPath = PathUtil.getLockManagementNode(txnDataPath);
         zkUtil.ensurePathExists(lockPath, null, Ids.OPEN_ACL_UNSAFE,
                 CreateMode.PERSISTENT);
         return lockPath;
     }


    /*
     * This class is a listener class for the locks used in revision management.
     * TBD: Use the following class to signal that that the lock is actually
     * been granted.
     */
     class RMLockListener implements LockListener {

        /*
         * @see org.apache.hcatalog.hbase.snapshot.lock.LockListener#lockAcquired()
         */
        @Override
        public void lockAcquired() {

        }

        /*
         * @see org.apache.hcatalog.hbase.snapshot.lock.LockListener#lockReleased()
         */
        @Override
        public void lockReleased() {

        }

     }


}
