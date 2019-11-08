/*
 * Copyright © 2018-2019 VMware, Inc. All Rights Reserved.
 * SPDX-License-Identifier: BSD-2
 */

package org.dcm;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watcher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

//import static org.junit.jupiter.api.Assertions.assertEquals;
//import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
//import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * To run these specific tests, pass a `schedulerName` property to maven, for example:
 *
 *  mvn integrate-test -DargLine="-Dk8sUrl=<hostname>:<port> -DschedulerName=dcm-scheduler"
 */
public class WorkloadGeneratorIT extends ITBase {
    private static final Logger LOG = LoggerFactory.getLogger(WorkloadGeneratorIT.class);
    private static final String SCHEDULER_NAME_PROPERTY = "schedulerName";
    @Nullable private static String schedulerName;

    @BeforeAll
    public static void setSchedulerFromEnvironment() {
        schedulerName = System.getProperty(SCHEDULER_NAME_PROPERTY);
    }

    @BeforeEach
    public void logBuildInfo() {
        final InputStream resourceAsStream = Scheduler.class.getResourceAsStream("/git.properties");
        try (final BufferedReader gitPropertiesFile = new BufferedReader(new InputStreamReader(resourceAsStream,
                Charset.forName("UTF8")))) {
            final String gitProperties = gitPropertiesFile.lines().collect(Collectors.joining(" "));
            LOG.info("Running integration test for the following build: {}", gitProperties);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testAffinityAntiAffinity() throws Exception {
        // Trace pod and node arrivals/departure
        final long traceId = System.currentTimeMillis();
        fabricClient.pods().inAnyNamespace().watch(new LoggingPodWatcher(traceId));
        fabricClient.nodes().watch(new LoggingNodeWatcher(traceId));

        assertNotNull(schedulerName);
        LOG.info("Running testAffinityAntiAffinity with parameters: MasterUrl:{} SchedulerName:{}",
                 fabricClient.getConfiguration().getMasterUrl(), schedulerName);

        final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        final InputStream inStream = classLoader.getResourceAsStream("test-data-2.txt");

        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(inStream,
		        Charset.forName("UTF8")))) {
            final ScheduledExecutorService scheduledExecutorService =
                    Executors.newScheduledThreadPool(10);
            //	final ArrayList<ScheduledFuture> futureList = new ArrayList<ScheduledFuture>();
            final ArrayList<Deployment> deploymentList = new ArrayList<Deployment>();

            String line;
            int recCount = 0;
            while ((line = reader.readLine()) != null) {
                final Deployment deployment = getDeployment(line, recCount);
		        fabricClient.apps().deployments().inNamespace(TEST_NAMESPACE)
                	    .create(deployment);
		        deploymentList.add(deployment);
		        final int index = recCount;
		        final Runnable task = () -> {
                        fabricClient.apps().deployments().inNamespace(TEST_NAMESPACE)
                                .delete(deploymentList.get(index));
                };
                recCount++;
//                final ScheduledFuture scheduledFuture = scheduledExecutorService.schedule(
//                                task, 30, TimeUnit.SECONDS);
                                //duration, TimeUnit.SECONDS);
                 //futureList.add(scheduledExecutorService.schedule(task, 30, TimeUnit.SECONDS));
                final ScheduledFuture scheduledFuture = scheduledExecutorService.schedule(task, 30,
                        TimeUnit.SECONDS);
                System.out.println(scheduledFuture);
            }

        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Deployment getDeployment(final String line, final int recCount) {
        final String[] parts = line.split(" ", 7);
        final int startTime = Integer.parseInt(parts[2]) / 60;
        int endTime = Integer.parseInt(parts[3]) / (60 * 100);
        if (endTime <= startTime) {
            endTime = startTime + 1;
        }
        final float cpu = Float.parseFloat(parts[4]) / 1000;
        final float mem = Float.parseFloat(parts[5]) / 100;
        final int count = Integer.parseInt(parts[6]);
        System.out.println(recCount + " " + startTime + " " + endTime + " " + cpu + " " +
                mem + " " + count);
        final int duration = (endTime - startTime) * 60;
        System.out.println("dur " + duration);

        final URL url = getClass().getClassLoader().getResource("cache-example.yml");
        assertNotNull(url);
        final File file = new File(url.getFile());
        final Deployment deployment = fabricClient.apps().deployments().load(file).get();
        deployment.getSpec().getTemplate().getSpec().setSchedulerName(schedulerName);
        final String appName = "app" + recCount;
        deployment.getMetadata().setName(appName);
        deployment.getSpec().setReplicas(count);

        final List<Container> containerList = deployment.getSpec().getTemplate().getSpec().getContainers();
        for (ListIterator<Container> iter = containerList.listIterator(); iter.hasNext(); ) {
            final Container container = iter.next();
            final ResourceRequirements resReq = new ResourceRequirements();
            final Map<String, Quantity> reqs = new HashMap<String, Quantity>();
            reqs.put("cpu", new Quantity(Float.toString(cpu * 1000) + "m"));
            reqs.put("memory", new Quantity(Float.toString(mem)));
            resReq.setRequests(reqs);
            iter.set(container);
        }
        deployment.getSpec().getTemplate().getSpec().setContainers(containerList);
        return deployment;
    }

    private static final class LoggingPodWatcher implements Watcher<Pod> {
        private final long traceId;

        LoggingPodWatcher(final long traceId) {
            this.traceId = traceId;
        }

        @Override
        public void eventReceived(final Action action, final Pod pod) {
            LOG.info("Timestamp: {}, Trace: {}, PodName: {}, NodeName: {}, Status: {}, Action: {}",
                    System.currentTimeMillis(), traceId, pod.getMetadata().getName(), pod.getSpec().getNodeName(),
                    pod.getStatus().getPhase(), action);
        }

        @Override
        public void onClose(final KubernetesClientException cause) {
            LOG.info("Timestamp: {}, Trace: {}, PodWatcher closed", System.currentTimeMillis(), traceId);
        }
    }


    private static final class LoggingNodeWatcher implements Watcher<Node> {
        private final long traceId;

        LoggingNodeWatcher(final long traceId) {
            this.traceId = traceId;
        }

        @Override
        public void eventReceived(final Action action, final Node node) {
            LOG.info("Timestamp: {}, Trace: {}, NodeName: {}, Action: {}", System.currentTimeMillis(), traceId,
                    node.getMetadata().getName(), action);
        }

        @Override
        public void onClose(final KubernetesClientException cause) {
            LOG.info("Timestamp: {}, Trace: {}, NodeWatcher closed", System.currentTimeMillis(), traceId);
        }
    }
}
