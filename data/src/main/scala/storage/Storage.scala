/** Copyright 2014 TappingStone, Inc.
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *     http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */

package io.prediction.data.storage

import grizzled.slf4j.Logging

import scala.collection.JavaConversions._
import scala.language.existentials
import scala.reflect.runtime.universe._

import scala.concurrent.ExecutionContext.Implicits.global

private[prediction] case class StorageError(val message: String)

/**
 * Backend-agnostic data storage layer with lazy initialization and connection
 * pooling. Use this object when you need to interface with Event Store in your
 * engine.
 */
object Storage extends Logging {
  private var errors = 0

  private def prefixPath(prefix: String, body: String) = s"${prefix}_${body}"

  private val sourcesPrefix = "PIO_STORAGE_SOURCES"
  private def sourcesPrefixPath(body: String) =
    prefixPath(sourcesPrefix, body)

  private val sourceTypesRegex = """PIO_STORAGE_SOURCES_([^_]+)_TYPE""".r

  private val sourceKeys: Seq[String] = sys.env.keys.toSeq.flatMap { k =>
    val keys = sourceTypesRegex findFirstIn k match {
      case Some(sourceTypesRegex(sourceType)) => Seq(sourceType)
      case None => Nil
    }
    val propKeys = System.getProperties.keysIterator.toSeq.flatMap { pKey =>
      sourceTypesRegex findFirstIn pKey.toString match {
        case Some(sourceTypesRegex(sourceType)) => if (!keys.contains(sourceType) ) Seq(sourceType) else Nil
        case None => Nil
      }
    }

    keys ++ propKeys
  }

  if (sourceKeys.size == 0) warn("There is no properly configured data source.")

  private case class ClientMeta(sourceType: String, client: BaseStorageClient)
  private case class DataObjectMeta(sourceName: String, databaseName: String)

  private val s2cm = scala.collection.mutable.Map[String, Option[ClientMeta]]()
  private def updateS2CM(k: String, parallel: Boolean, test: Boolean):
    Option[ClientMeta] = {
    try {
      val keyedPath = sourcesPrefixPath(k)
      val sourceType = if ( sys.env.contains(prefixPath(keyedPath, "TYPE"))) sys.env(prefixPath(keyedPath, "TYPE")) else System.getProperty(prefixPath(keyedPath, "TYPE"))
      val hosts = (if ( sys.env.contains(prefixPath(keyedPath, "HOSTS"))) sys.env(prefixPath(keyedPath, "HOSTS")) else System.getProperty(prefixPath(keyedPath, "HOSTS")) ).split(',')
      val ports = (if ( sys.env.contains(prefixPath(keyedPath, "PORTS"))) sys.env(prefixPath(keyedPath, "PORTS")) else System.getProperty(prefixPath(keyedPath, "PORTS")) ).split(',').
        map(_.toInt)
      val clientConfig = StorageClientConfig(
        hosts = hosts,
        ports = ports,
        parallel = parallel,
        test = test)
      val client = getClient(clientConfig, sourceType)
      Some(ClientMeta(sourceType, client))
    } catch {
      case e: Throwable =>
        error(s"Error initializing storage client for source ${k}")
        error(e.getMessage)
        errors += 1
        None
    }
  }
  private def sourcesToClientMetaGet(
      source: String,
      parallel: Boolean,
      test: Boolean): Option[ClientMeta] = {
    val sourceName = if (parallel) s"parallel-${source}" else source
    s2cm.getOrElseUpdate(sourceName, updateS2CM(source, parallel, test))
  }
  private def sourcesToClientMeta(
      source: String,
      parallel: Boolean,
      test: Boolean): ClientMeta =
    sourcesToClientMetaGet(source, parallel, test).get

  /*
  if (sys.env.get("PIO_STORAGE_INIT_SOURCES").getOrElse(false)) {
    sourcesToClientMeta.
  }
  */

  /** Reference to the app data repository. */
  private val EventDataRepository = "EVENTDATA"
  private val ModelDataRepository = "MODELDATA"
  private val MetaDataRepository = "METADATA"

  private val repositoriesPrefix = "PIO_STORAGE_REPOSITORIES"
  private def repositoriesPrefixPath(body: String) =
    prefixPath(repositoriesPrefix, body)

  private val repositoryNamesRegex =
    """PIO_STORAGE_REPOSITORIES_([^_]+)_NAME""".r

  private val repositoryKeys: Seq[String] = sys.env.keys.toSeq.flatMap { k =>
    val keys = repositoryNamesRegex findFirstIn k match {
      case Some(repositoryNamesRegex(repositoryName)) => Seq(repositoryName)
      case None => Nil
    }

    val propKeys = System.getProperties.keysIterator.toSeq.flatMap { pKey =>
      repositoryNamesRegex findFirstIn pKey.toString match {
        case Some(repositoryNamesRegex(repositoryName)) => if (!keys.contains(repositoryName) ) Seq(repositoryName) else Nil
        case None => Nil
      }
    }

    keys ++ propKeys
  }

  if (repositoryKeys.size == 0)
    warn("There is no properly configured repository.")

  private val requiredRepositories = Seq(MetaDataRepository)

  requiredRepositories foreach { r =>
    if (!repositoryKeys.contains(r)) {
      error(s"Required repository (${r}) configuration is missing.")
      errors += 1
    }
  }
  private val repositoriesToDataObjectMeta: Map[String, DataObjectMeta] =
    repositoryKeys.map(r =>
      try {
        val keyedPath = repositoriesPrefixPath(r)
        val name = if ( sys.env.contains(prefixPath(keyedPath, "NAME")))  sys.env(prefixPath(keyedPath, "NAME")) else System.getProperty(prefixPath(keyedPath, "NAME"))
        val sourceName = if ( sys.env.contains(prefixPath(keyedPath, "SOURCE"))) sys.env(prefixPath(keyedPath, "SOURCE")) else System.getProperty(prefixPath(keyedPath, "SOURCE"))
        if (sourceKeys.contains(sourceName)) {
          r -> DataObjectMeta(
            sourceName = sourceName,
            databaseName = name)
        } else {
          error(s"$sourceName is not a configured storage source.")
          r -> DataObjectMeta("", "")
        }
      } catch {
        case e: Throwable =>
          error(e.getMessage)
          errors += 1
          r -> DataObjectMeta("", "")
      }
    ).toMap

  private def getClient(
      clientConfig: StorageClientConfig,
      pkg: String): BaseStorageClient = {
    val className = "io.prediction.data.storage." + pkg + ".StorageClient"
    try {
      Class.forName(className).getConstructors()(0).newInstance(clientConfig).
        asInstanceOf[BaseStorageClient]
    } catch {
      case e: ClassNotFoundException =>
        val originalClassName = pkg + ".StorageClient"
        Class.forName(originalClassName).getConstructors()(0).
          newInstance(clientConfig).asInstanceOf[BaseStorageClient]
      case e: java.lang.reflect.InvocationTargetException =>
        throw e.getCause
    }
  }

  private def getClient(
      sourceName: String,
      parallel: Boolean,
      test: Boolean): Option[BaseStorageClient] =
    sourcesToClientMetaGet(sourceName, parallel, test).map(_.client)

  private[prediction]
  def getDataObject[T](repo: String, test: Boolean = false)
    (implicit tag: TypeTag[T]): T = {
    val repoDOMeta = repositoriesToDataObjectMeta(repo)
    val repoDOSourceName = repoDOMeta.sourceName
    getDataObject[T](repoDOSourceName, repoDOMeta.databaseName, test = test)
  }

  private[prediction]
  def getPDataObject[T](repo: String)(implicit tag: TypeTag[T]): T = {
    val repoDOMeta = repositoriesToDataObjectMeta(repo)
    val repoDOSourceName = repoDOMeta.sourceName
    getPDataObject[T](repoDOSourceName, repoDOMeta.databaseName)
  }

  private[prediction] def getDataObject[T](
      sourceName: String,
      databaseName: String,
      parallel: Boolean = false,
      test: Boolean = false)(implicit tag: TypeTag[T]): T = {
    val clientMeta = sourcesToClientMeta(sourceName, parallel, test)
    val sourceType = clientMeta.sourceType
    val ctorArgs = dataObjectCtorArgs(clientMeta.client, databaseName)
    val classPrefix = clientMeta.client.prefix
    val originalClassName = tag.tpe.toString.split('.')
    val rawClassName = sourceType + "." + classPrefix + originalClassName.last
    val className = "io.prediction.data.storage." + rawClassName
    val clazz = try {
      Class.forName(className)
    } catch {
      case e: ClassNotFoundException => Class.forName(rawClassName)
    }
    val constructor = clazz.getConstructors()(0)
    try {
      constructor.newInstance(ctorArgs: _*).
        asInstanceOf[T]
    } catch {
      case e: IllegalArgumentException =>
        error(
          "Unable to instantiate data object with class '" +
          constructor.getDeclaringClass.getName + " because its constructor" +
          " does not have the right number of arguments." +
          " Number of required constructor arguments: " +
          ctorArgs.size + "." +
          " Number of existing constructor arguments: " +
          constructor.getParameterTypes.size + "." +
          s" Storage source name: ${sourceName}." +
          s" Exception message: ${e.getMessage}).")
        errors += 1
        throw e
    }
  }

  private def getPDataObject[T](
      sourceName: String,
      databaseName: String)(implicit tag: TypeTag[T]): T =
    getDataObject[T](sourceName, databaseName, true)

  private def dataObjectCtorArgs(
      client: BaseStorageClient,
      dbName: String): Seq[AnyRef] = {
    Seq(client.client, dbName)
  }

  private[prediction] def verifyAllDataObjects(): Unit = {
    println("  Verifying Meta Data Backend")
    getMetaDataEngineManifests()
    getMetaDataEngineInstances()
    getMetaDataApps()
    getMetaDataAccessKeys()
    println("  Verifying Model Data Backend")
    getModelDataModels()
    println("  Verifying Event Data Backend")
    val eventsDb = getLEvents(test = true)
    println("  Test write Event Store (App Id 0)")
    // use appId=0 for testing purpose
    eventsDb.init(0)
    eventsDb.insert(Event(
      event="test",
      entityType="test",
      entityId="test"), 0)
    eventsDb.remove(0)
    eventsDb.close()
  }

  private[prediction] def getMetaDataEngineManifests(): EngineManifests =
    getDataObject[EngineManifests](MetaDataRepository)

  private[prediction] def getMetaDataEngineInstances(): EngineInstances =
    getDataObject[EngineInstances](MetaDataRepository)

  private[prediction] def getMetaDataApps(): Apps =
    getDataObject[Apps](MetaDataRepository)

  private[prediction] def getMetaDataAccessKeys(): AccessKeys =
    getDataObject[AccessKeys](MetaDataRepository)

  private[prediction] def getModelDataModels(): Models =
    getDataObject[Models](ModelDataRepository)

  private[prediction] def getLEvents(test: Boolean = false): LEvents =
    getDataObject[LEvents](EventDataRepository, test = test)

  /** Obtains a data access object that returns [[Event]] related RDD data
    * structure.
    */
  def getPEvents(): PEvents =
    getPDataObject[PEvents](EventDataRepository)

  if (errors > 0) {
    error(s"There were $errors configuration errors. Exiting.")
    System.exit(1)
  }
}

private[prediction] trait BaseStorageClient {
  val config: StorageClientConfig
  val client: AnyRef
  val prefix: String = ""
}

private[prediction] case class StorageClientConfig(
  hosts: Seq[String],
  ports: Seq[Int],
  parallel: Boolean = false,
  test: Boolean = false) // test mode config

private[prediction] class StorageClientException(msg: String)
    extends RuntimeException(msg)
