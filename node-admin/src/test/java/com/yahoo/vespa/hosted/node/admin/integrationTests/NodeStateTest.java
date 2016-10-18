// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.integrationTests;

import com.yahoo.metrics.simple.MetricReceiver;
import com.yahoo.vespa.hosted.dockerapi.metrics.MetricReceiverWrapper;
import com.yahoo.vespa.hosted.node.admin.ContainerNodeSpec;
import com.yahoo.vespa.hosted.dockerapi.ContainerName;
import com.yahoo.vespa.hosted.dockerapi.DockerImage;
import com.yahoo.vespa.hosted.node.admin.nodeadmin.NodeAdmin;
import com.yahoo.vespa.hosted.node.admin.nodeadmin.NodeAdminImpl;
import com.yahoo.vespa.hosted.node.admin.nodeadmin.NodeAdminStateUpdater;
import com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgent;
import com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentImpl;
import com.yahoo.vespa.hosted.node.admin.docker.DockerOperationsImpl;
import com.yahoo.vespa.hosted.node.admin.util.Environment;
import com.yahoo.vespa.hosted.node.admin.util.InetAddressResolver;
import com.yahoo.vespa.hosted.node.maintenance.Maintainer;
import com.yahoo.vespa.hosted.provision.Node;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Optional;
import java.util.function.Function;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test NodeState transitions in NodeRepository
 *
 * @author valerijf
 */

public class NodeStateTest {
    private CallOrderVerifier callOrderVerifier;
    private NodeRepoMock nodeRepositoryMock;
    private DockerMock dockerMock;
    private ContainerNodeSpec initialContainerNodeSpec;
    private NodeAdminStateUpdater updater;

    @Before
    public void before() throws InterruptedException, UnknownHostException {
        callOrderVerifier = new CallOrderVerifier();
        StorageMaintainerMock maintenanceSchedulerMock = new StorageMaintainerMock(callOrderVerifier);
        OrchestratorMock orchestratorMock = new OrchestratorMock(callOrderVerifier);
        nodeRepositoryMock = new NodeRepoMock(callOrderVerifier);
        dockerMock = new DockerMock(callOrderVerifier);

        InetAddressResolver inetAddressResolver = mock(InetAddressResolver.class);
        when(inetAddressResolver.getInetAddressForHost(any(String.class))).thenReturn(InetAddress.getByName("1.1.1.1"));
        Environment environment = new Environment(Collections.emptySet(),
                                                  Environment.NetworkType.normal,
                                                  "dev",
                                                  "us-east-1",
                                                  inetAddressResolver);

        MetricReceiverWrapper mr = new MetricReceiverWrapper(MetricReceiver.nullImplementation);
        Function<String, NodeAgent> nodeAgentFactory = (hostName) ->
                new NodeAgentImpl(hostName, nodeRepositoryMock, orchestratorMock,
                                  new DockerOperationsImpl(dockerMock, environment),
                                  maintenanceSchedulerMock, mr, environment, new Maintainer());
        NodeAdmin nodeAdmin = new NodeAdminImpl(dockerMock, nodeAgentFactory, maintenanceSchedulerMock, 100, mr);

        initialContainerNodeSpec = new ContainerNodeSpec(
                "host1",
                Optional.of(new DockerImage("dockerImage")),
                new ContainerName("container"),
                Node.State.active,
                "tenant",
                "docker",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(1L),
                Optional.of(1L),
                Optional.of(1d),
                Optional.of(1d),
                Optional.of(1d));
        nodeRepositoryMock.addContainerNodeSpec(initialContainerNodeSpec);

        updater = new NodeAdminStateUpdater(nodeRepositoryMock, nodeAdmin, 1, 1, orchestratorMock, "basehostname");

        // Wait for node admin to be notified with node repo state and the docker container has been started
        while (nodeAdmin.getListOfHosts().size() == 0) {
            Thread.sleep(10);
        }

        callOrderVerifier.assertInOrder(
                "createContainerCommand with DockerImage: DockerImage { imageId=dockerImage }, HostName: host1, ContainerName: ContainerName { name=container }",
                "executeInContainer with ContainerName: ContainerName { name=container }, args: [/usr/bin/env, test, -x, /opt/yahoo/vespa/bin/vespa-nodectl]",
                "executeInContainer with ContainerName: ContainerName { name=container }, args: [/opt/yahoo/vespa/bin/vespa-nodectl, resume]");
    }

    @After
    public void after() {
        updater.deconstruct();
    }


    @Test
    public void activeToDirty() throws InterruptedException, IOException {
        // Change node state to dirty
        nodeRepositoryMock.updateContainerNodeSpec(
                initialContainerNodeSpec.hostname,
                initialContainerNodeSpec.wantedDockerImage,
                initialContainerNodeSpec.containerName,
                Node.State.dirty,
                initialContainerNodeSpec.wantedRestartGeneration,
                initialContainerNodeSpec.currentRestartGeneration,
                initialContainerNodeSpec.minCpuCores,
                initialContainerNodeSpec.minMainMemoryAvailableGb,
                initialContainerNodeSpec.minDiskAvailableGb);

        // Wait until it is marked ready
        Optional<ContainerNodeSpec> containerNodeSpec;
        while ((containerNodeSpec = nodeRepositoryMock.getContainerNodeSpec(initialContainerNodeSpec.hostname)).isPresent()
                && containerNodeSpec.get().nodeState != Node.State.ready) {
            Thread.sleep(10);
        }

        assertThat(nodeRepositoryMock.getContainerNodeSpec(initialContainerNodeSpec.hostname).get().nodeState, is(Node.State.ready));

        callOrderVerifier.assertInOrderWithAssertMessage("Node set to dirty, but no stop/delete call received",
                                                         "stopContainer with ContainerName: ContainerName { name=container }",
                                                         "deleteContainer with ContainerName: ContainerName { name=container }");
    }

    @Test
    public void activeToInactiveToActive() throws InterruptedException, IOException {
        Optional<DockerImage> newDockerImage = Optional.of(new DockerImage("newDockerImage"));

        // Change node state to inactive and change the wanted docker image
        nodeRepositoryMock.updateContainerNodeSpec(
                initialContainerNodeSpec.hostname,
                newDockerImage,
                initialContainerNodeSpec.containerName,
                Node.State.inactive,
                initialContainerNodeSpec.wantedRestartGeneration,
                initialContainerNodeSpec.currentRestartGeneration,
                initialContainerNodeSpec.minCpuCores,
                initialContainerNodeSpec.minMainMemoryAvailableGb,
                initialContainerNodeSpec.minDiskAvailableGb);

        callOrderVerifier.assertInOrderWithAssertMessage("Node set to inactive, but no stop/delete call received",
                                                         "stopContainer with ContainerName: ContainerName { name=container }",
                                                         "deleteContainer with ContainerName: ContainerName { name=container }");


        // Change node state to active
        nodeRepositoryMock.updateContainerNodeSpec(
                initialContainerNodeSpec.hostname,
                newDockerImage,
                initialContainerNodeSpec.containerName,
                Node.State.active,
                initialContainerNodeSpec.wantedRestartGeneration,
                initialContainerNodeSpec.currentRestartGeneration,
                initialContainerNodeSpec.minCpuCores,
                initialContainerNodeSpec.minMainMemoryAvailableGb,
                initialContainerNodeSpec.minDiskAvailableGb);

        // Check that the container is started again after the delete call
        callOrderVerifier.assertInOrderWithAssertMessage("Node not started again after being put to active state",
                                                         "deleteContainer with ContainerName: ContainerName { name=container }",
                                                         "createContainerCommand with DockerImage: DockerImage { imageId=newDockerImage }, HostName: host1, ContainerName: ContainerName { name=container }",
                                                         "executeInContainer with ContainerName: ContainerName { name=container }, args: [/usr/bin/env, test, -x, /opt/yahoo/vespa/bin/vespa-nodectl]",
                                                         "executeInContainer with ContainerName: ContainerName { name=container }, args: [/opt/yahoo/vespa/bin/vespa-nodectl, resume]");
    }
}
