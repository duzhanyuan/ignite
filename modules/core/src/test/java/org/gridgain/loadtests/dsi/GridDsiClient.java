/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.loadtests.dsi;

import org.gridgain.grid.*;
import org.gridgain.grid.compute.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.util.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.testframework.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 *
 */
public class GridDsiClient implements Callable {
    /** Stats update interval in seconds. */
    private static final int UPDATE_INTERVAL_SEC = 10;

    /** Grid. */
    private static Grid g;

    /** Transaction count. */
    private static AtomicLong txCnt = new AtomicLong();

    /** Latency. */
    private static AtomicLong latency = new AtomicLong();

    /** Submit time. */
    private static GridAtomicLong submitTime = new GridAtomicLong();

    /** Server stats. */
    private static volatile T3<Long, Integer, Integer> srvStats;

    /** Finish flag. */
    private static AtomicBoolean finish = new AtomicBoolean();

    /** Terminal ID. */
    private String terminalId;

    /** Node ID. */
    private UUID nodeId;

    /**
     * Client constructor.
     *
     * @param terminalId Terminal ID.
     * @param nodeId Node ID.
     */
    GridDsiClient(String terminalId, UUID nodeId) {
        this.terminalId = terminalId;
        this.nodeId = nodeId;
    }

    /**
     * Predicate to look for server node.
     *
     * @return {@code true} if node segment is 'server'.
     */
    public static GridPredicate<GridNode> serverNode() {
        return new GridPredicate<GridNode>() {
            @Override public boolean apply(GridNode node) {
                return "server".equals(node.attribute("segment"));
            }
        };
    }

    /**
     * Predicate to look for client node.
     *
     * @return {@code true} if node segment is 'client'.
     */
    public static GridPredicate<GridNode> clientNode() {
        return new GridPredicate<GridNode>() {
            @Override public boolean apply(GridNode node) {
                return "client".equals(node.attribute("segment"));
            }
        };
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked", "InfiniteLoopStatement"})
    @Nullable @Override public Object call() throws Exception {
        GridCompute comp = g.forPredicate(serverNode()).compute().enableAsync();

        while (!finish.get()) {
            try {
                long t0 = System.currentTimeMillis();

                long submitTime1 = t0;

                comp.execute(GridDsiRequestTask.class, new GridDsiMessage(terminalId, nodeId));

                GridComputeTaskFuture<T3<Long, Integer, Integer>> f1 = comp.future();

                submitTime.setIfGreater(System.currentTimeMillis() - submitTime1);

                T3<Long, Integer, Integer> res1 = f1.get();

                submitTime1 = System.currentTimeMillis();

                comp.execute(GridDsiResponseTask.class, new GridDsiMessage(terminalId, nodeId));

                GridComputeTaskFuture<T3<Long, Integer, Integer>> f2 = comp.future();

                submitTime.setIfGreater(System.currentTimeMillis() - submitTime1);

                T3<Long, Integer, Integer> res2 = f2.get();

                long t1 = System.currentTimeMillis();

                txCnt.incrementAndGet();

                latency.addAndGet(t1 - t0);

                if (res1 != null)
                    srvStats = res1;

                if (res2 != null)
                    srvStats = res2;
            }
            catch (GridException e) {
                e.printStackTrace();
            }
        }

        return null;
    }

    /**
     * Method to print request statistics.
     */
    private static void displayReqCount() {
        new Thread(new Runnable() {
            @SuppressWarnings({"BusyWait", "InfiniteLoopStatement"})
            @Override public void run() {
                int interval = 30;

                while (true) {
                    long cnt0 = txCnt.get();
                    long lt0 = latency.get();

                    try {
                        Thread.sleep(interval * 1000);
                    }
                    catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    long cnt1 = txCnt.get();
                    long lt1 = latency.get();

                    X.println(">>>");
                    X.println(">>> Transaction/s: " + (cnt1 - cnt0) / interval);
                    X.println(
                        ">>> Avg Latency: " + ((cnt1 - cnt0) > 0 ? (lt1 - lt0) / (cnt1 - cnt0) + "ms" : "invalid"));
                    X.println(">>> Max Submit Time: " + submitTime.getAndSet(0));
                }
            }
        }).start();
    }

    /**
     * Execute DSI load client.
     *
     * @param args Command line arguments, two required - first one is the number of threads,
     *      second one should point to the Spring XML configuration file.
     * @throws Exception If client fails.
     */
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        GridFileLock fileLock = GridLoadTestUtils.fileLock();

        fileLock.lock(true); // Get shared lock, allowing multiple instances.

        try {
            GridGain.start(args.length < 4 ? "modules/core/src/test/config/load/dsi-load-client.xml" : args[3]);

            Thread collector = null;

            Thread timer = null;

            try {
                g = GridGain.grid("dsi");

                int noThreads = Integer.parseInt(args[0]);

                final int duration = args.length < 2 ? 0 : Integer.parseInt(args[1]);

                final String outputFileName = args.length < 3 ? null : args[2];

                X.println("Thread count: " + noThreads);

                Collection<GridNode> srvNodes = g.forPredicate(serverNode()).nodes();

                if (srvNodes.isEmpty()) {
                    X.println("No server nodes available");

                    System.exit(-1);
                }

                X.println("No of servers: " + srvNodes.size());

                int srvMaxNoTerminals = noThreads / srvNodes.size();

                if (srvMaxNoTerminals * srvNodes.size() != noThreads) {
                    noThreads = srvMaxNoTerminals * srvNodes.size();

                    X.println("Using " + noThreads + " threads instead to ensure equal distribution of terminals");
                }

                Collection<Callable<Object>> clients = new ArrayList<>(noThreads);

                // No 2 client should use the same simulator.
                HashMap<UUID, Collection<String>> terminals = (HashMap<UUID, Collection<String>>)
                    g.cache("CLIENT_PARTITIONED_CACHE").get("terminals");

                if (terminals == null) {
                    X.println(">>> Terminals map has not been initialized.");

                    terminals = new HashMap<>(srvNodes.size());

                    // Distribute terminals evenly across all servers.
                    for (GridNode node : srvNodes) {
                        UUID srvrId = node.id();

                        X.println(">>> Node ID: " + srvrId);

                        Collection<String> list = terminals.get(srvrId);

                        if (list == null)
                            list = new ArrayList<>(0);

                        int terminalsPerSrv = 0;

                        int tid = 0; // Terminal ID.

                        while (true) {
                            String terminalId = String.valueOf(++tid);

                            // Server partition cache.
                            if (!srvrId.equals(g.mapKeyToNode("PARTITIONED_CACHE", terminalId).id()))
                                continue;

                            if (terminalsPerSrv < srvMaxNoTerminals) {
                                list.add(terminalId);

                                clients.add(new GridDsiClient(terminalId, srvrId));

                                terminalsPerSrv++;

                                X.println("Terminal ID: " + terminalId);
                            }
                            else
                                break;
                        }

                        terminals.put(srvrId, list);
                    }

                    g.cache("CLIENT_PARTITIONED_CACHE").putx("terminals", terminals);
                }
                else {
                    X.println(">>> Terminals map has been initialized.");

                    for (Map.Entry<UUID, Collection<String>> e : terminals.entrySet()) {
                        X.println(">>> Node ID: " + e.getKey());

                        for (String s : e.getValue()) {
                            clients.add(new GridDsiClient(s, e.getKey()));

                            X.println("Terminal ID: " + s);
                        }
                    }
                }

                if (duration > 0) {
                    timer = new Thread(new Runnable() {
                        @Override public void run() {
                            try {
                                Thread.sleep(duration * 1000);

                                finish.set(true);
                            }
                            catch (InterruptedException ignored) {
                                // No-op.
                            }
                        }
                    });
                    timer.start();
                }

                collector = new Thread(new Runnable() {
                    @SuppressWarnings({"BusyWait", "InfiniteLoopStatement"})
                    @Override public void run() {
                        long txPerSecond = -1;
                        long avgLatency = -1;
                        long maxSubmitTime = -1;
                        T3<Long, Integer, Integer> sst = null;

                        try {
                            while (!finish.get()) {
                                long cnt0 = txCnt.get();
                                long lt0 = latency.get();

                                Thread.sleep(UPDATE_INTERVAL_SEC * 1000);

                                long cnt1 = txCnt.get();
                                long lt1 = latency.get();

                                X.println(">>>");

                                txPerSecond = (cnt1 - cnt0) / UPDATE_INTERVAL_SEC;
                                X.println(">>> Transaction/s: " + txPerSecond);

                                avgLatency = (cnt1 - cnt0) > 0 ? (lt1 - lt0) / (cnt1 - cnt0) : -1;
                                X.println(
                                    ">>> Avg Latency: " + (avgLatency >= 0 ? avgLatency + "ms" : "invalid"));

                                maxSubmitTime = submitTime.getAndSet(0);
                                X.println(">>> Max Submit Time: " + maxSubmitTime);

                                sst = srvStats;

                                if (sst != null)
                                    X.println(String.format(">>> Server stats: [tx/sec=%d, nearSize=%d, dhtSize=%d]",
                                        sst.get1(), sst.get2(), sst.get3()));
                            }
                        }
                        catch (InterruptedException ignored) {
                            X.println(">>> Interrupted.");

                            Thread.currentThread().interrupt();
                        }

                        // Output data to a file, if specified.
                        if (outputFileName != null) {
                            X.println("Writing client results to a file: " + outputFileName);

                            try {
                                GridLoadTestUtils.appendLineToFile(
                                    outputFileName,
                                    "%s,%d,%d,%d",
                                    GridLoadTestUtils.DATE_TIME_FORMAT.format(new Date()),
                                    txPerSecond,
                                    avgLatency,
                                    maxSubmitTime);
                            }
                            catch (IOException e) {
                                X.println("Failed to write client results: ", e);
                            }

                            if (sst != null) {
                                String srvOutputFileName = outputFileName + "-server";

                                X.println("Writing server results to a file: " + srvOutputFileName);

                                try {
                                    GridLoadTestUtils.appendLineToFile(
                                        srvOutputFileName,
                                        "%s,%d,%d,%d",
                                        GridLoadTestUtils.DATE_TIME_FORMAT.format(new Date()),
                                        sst.get1(),
                                        sst.get2(),
                                        sst.get3());
                                }
                                catch (IOException e) {
                                    X.println("Failed to write server results: ", e);
                                }
                            }
                        }
                    }
                });
                collector.start();

                ExecutorService pool = Executors.newFixedThreadPool(noThreads);

                pool.invokeAll(clients);

                collector.interrupt();

                pool.shutdown();
            }
            finally {
                if (collector != null && !collector.isInterrupted())
                    collector.interrupt();

                if (timer != null)
                    timer.interrupt();

                GridGain.stopAll(true);
            }
        }
        finally {
            fileLock.close();
        }
    }
}
