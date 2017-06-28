/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.deploy.kubernetes.submit

import org.apache.spark.{SSLOptions, SparkConf, SparkFunSuite}
import org.apache.spark.deploy.kubernetes.config._
import scala.collection.JavaConverters._

import io.fabric8.kubernetes.api.model.{ContainerBuilder, Pod, PodBuilder}



private[spark] class PythonSubmissionResourcesSuite extends SparkFunSuite {
  private val PYSPARK_FILES = Seq(
    "hdfs://localhost:9000/app/files/file1.py",
    "file:///app/files/file2.py",
    "local:///app/files/file3.py",
    "http://app/files/file4.py",
    "file:///app/files/file5.py")
  private val RESOLVED_PYSPARK_FILES = Seq(
    "hdfs://localhost:9000/app/files/file1.py",
    "/var/spark-data/spark-files/file2.py",
    "local:///app/file`s/file3.py",
    "http://app/files/file4.py")
  private val PYSPARK_PRIMARY_FILE = "file:///app/files/file5.py"
  private val RESOLVED_PYSPARK_PRIMARY_FILE = "/var/data/spark-files/file5.py"

  private val pyFilesResource = new PythonSubmissionResourcesImpl(
    PYSPARK_PRIMARY_FILE, Array(PYSPARK_FILES.mkString(","), "500")
  )
  private val pyResource = new PythonSubmissionResourcesImpl(
    PYSPARK_PRIMARY_FILE, Array(null, "500")
  )
  private val SPARK_FILES = Seq.empty[String]
  private val SPARK_JARS = Seq.empty[String]
  private val JARS_DOWNLOAD_PATH = "/var/data/spark-jars"
  private val FILES_DOWNLOAD_PATH = "/var/data/spark-files"
  private val localizedFilesResolver = new ContainerLocalizedFilesResolverImpl(
    SPARK_JARS,
    SPARK_FILES,
    PYSPARK_FILES,
    PYSPARK_PRIMARY_FILE,
    JARS_DOWNLOAD_PATH,
    FILES_DOWNLOAD_PATH)
  private val lessLocalizedFilesResolver = new ContainerLocalizedFilesResolverImpl(
    SPARK_JARS,
    SPARK_FILES,
    Seq.empty[String],
    PYSPARK_PRIMARY_FILE,
    JARS_DOWNLOAD_PATH,
    FILES_DOWNLOAD_PATH)
  private val NAMESPACE = "example_pyspark"
  private val DRIVER_CONTAINER_NAME = "pyspark_container"
  private val driverContainer = new ContainerBuilder()
    .withName(DRIVER_CONTAINER_NAME)
    .build()
  private val basePodBuilder = new PodBuilder()
    .withNewMetadata()
      .withName("base_pod")
    .endMetadata()
    .withNewSpec()
      .addToContainers(driverContainer)
    .endSpec()
  private val driverFileMounter = new DriverInitContainerComponentsProviderImpl(
    new SparkConf(true).set(KUBERNETES_NAMESPACE, NAMESPACE),
    "kubeResourceName",
    "namespace",
    SPARK_JARS,
    SPARK_FILES,
    PYSPARK_PRIMARY_FILE +: PYSPARK_FILES,
    SSLOptions()
  ).provideDriverPodFileMounter()
  private val lessDriverFileMounter = new DriverInitContainerComponentsProviderImpl(
    new SparkConf(true).set(KUBERNETES_NAMESPACE, NAMESPACE),
    "kubeResourceName",
    "namespace",
    SPARK_JARS,
    SPARK_FILES,
    Array(PYSPARK_PRIMARY_FILE),
    SSLOptions()
  ).provideDriverPodFileMounter()

  test("Test with --py-files included") {
    assert(pyFilesResource.sparkJars === Seq.empty[String])
    assert(pyFilesResource.pySparkFiles ===
      PYSPARK_PRIMARY_FILE +: PYSPARK_FILES)
    assert(pyFilesResource.primaryPySparkResource(localizedFilesResolver) ===
      RESOLVED_PYSPARK_PRIMARY_FILE)
    val driverPod: Pod = pyFilesResource.driverPod(
      driverFileMounter,
      RESOLVED_PYSPARK_PRIMARY_FILE,
      RESOLVED_PYSPARK_FILES.mkString(","),
      DRIVER_CONTAINER_NAME,
      basePodBuilder
      )
    val driverContainer = driverPod.getSpec.getContainers.asScala.head
    val envs = driverContainer.getEnv.asScala.map(env => (env.getName, env.getValue)).toMap
    envs.get("PYSPARK_PRIMARY") foreach{ a => assert (a === RESOLVED_PYSPARK_PRIMARY_FILE) }
    envs.get("PYSPARK_FILES") foreach{ a => assert (a === RESOLVED_PYSPARK_FILES.mkString(",")) }
  }

  test("Test without --py-files") {
    assert(pyResource.sparkJars === Seq.empty[String])
    assert(pyResource.pySparkFiles === Array(PYSPARK_PRIMARY_FILE))
    assert(pyResource.primaryPySparkResource(lessLocalizedFilesResolver) ===
      RESOLVED_PYSPARK_PRIMARY_FILE)
    val driverPod: Pod = pyResource.driverPod(
      lessDriverFileMounter,
      RESOLVED_PYSPARK_PRIMARY_FILE,
      "",
      DRIVER_CONTAINER_NAME,
      basePodBuilder
    )
    val driverContainer = driverPod.getSpec.getContainers.asScala.head
    val envs = driverContainer.getEnv.asScala.map(env => (env.getName, env.getValue)).toMap
    envs.get("PYSPARK_PRIMARY") foreach{ a => assert (a === RESOLVED_PYSPARK_PRIMARY_FILE) }
    envs.get("PYSPARK_FILES") foreach{ a => assert (a === "") }
  }
}