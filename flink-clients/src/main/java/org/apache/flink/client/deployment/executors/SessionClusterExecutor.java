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

package org.apache.flink.client.deployment.executors;

import org.apache.flink.api.common.JobExecutionResult;
import org.apache.flink.api.dag.Pipeline;
import org.apache.flink.client.ClientUtils;
import org.apache.flink.client.FlinkPipelineTranslationUtil;
import org.apache.flink.client.cli.ExecutionConfigAccessor;
import org.apache.flink.client.deployment.ClusterClientFactory;
import org.apache.flink.client.deployment.ClusterClientServiceLoader;
import org.apache.flink.client.deployment.ClusterDescriptor;
import org.apache.flink.client.deployment.DefaultClusterClientServiceLoader;
import org.apache.flink.client.program.ClusterClient;
import org.apache.flink.client.program.PackagedProgram;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.PipelineOptions;
import org.apache.flink.core.execution.Executor;
import org.apache.flink.runtime.jobgraph.JobGraph;

import java.io.File;
import java.net.URL;
import java.util.Collections;
import java.util.List;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * The {@link Executor} to be used when executing a job on an already running cluster.
 */
public class SessionClusterExecutor<ClusterID> implements Executor {

	private final ClusterClientServiceLoader clusterClientServiceLoader;

	public SessionClusterExecutor() {
		this(new DefaultClusterClientServiceLoader());
	}

	public SessionClusterExecutor(final ClusterClientServiceLoader clusterClientServiceLoader) {
		this.clusterClientServiceLoader = checkNotNull(clusterClientServiceLoader);
	}

	@Override
	public JobExecutionResult execute(final Pipeline pipeline, final Configuration executionConfig) throws Exception {
		final ExecutionConfigAccessor configAccessor = ExecutionConfigAccessor.fromConfiguration(executionConfig);

		final ClusterClientFactory<ClusterID> clusterClientFactory = clusterClientServiceLoader.getClusterClientFactory(executionConfig);

		try (final ClusterDescriptor<ClusterID> clusterDescriptor = clusterClientFactory.createClusterDescriptor(executionConfig)) {
			final ClusterID clusterID = clusterClientFactory.getClusterId(executionConfig);
			checkState(clusterID != null);

			try (final ClusterClient<ClusterID> clusterClient = clusterDescriptor.retrieve(clusterID)) {

				final JobGraph jobGraph = FlinkPipelineTranslationUtil.getJobGraph(
						pipeline,
						clusterClient.getFlinkConfiguration(),
						configAccessor.getParallelism());

				final List<URL> classpaths = configAccessor.getClasspaths();
				final URL jarFileUrl = configAccessor.getJarFilePath();

				final List<File> extractedLibs = PackagedProgram.extractContainedLibraries(jarFileUrl);
				final boolean isPython = executionConfig.getBoolean(PipelineOptions.Internal.IS_PYTHON);
				final List<URL> libraries = jarFileUrl == null
						? Collections.emptyList()
						: PackagedProgram.getAllLibraries(jarFileUrl, extractedLibs, isPython);

				final ClassLoader userClassLoader = ClientUtils.buildUserCodeClassLoader(libraries, classpaths, getClass().getClassLoader());

				jobGraph.addJars(libraries);
				jobGraph.setClasspaths(classpaths);
				jobGraph.setSavepointRestoreSettings(configAccessor.getSavepointRestoreSettings());

				return configAccessor.getDetachedMode()
						? ClientUtils.submitJob(clusterClient, jobGraph)
						: ClientUtils.submitJobAndWaitForResult(clusterClient, jobGraph, userClassLoader).getJobExecutionResult();
			}
		}
	}
}
