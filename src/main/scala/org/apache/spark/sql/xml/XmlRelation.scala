/*
 * Copyright 2014 Apache
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
package org.apache.spark.sql.xml

import java.io.IOException

import org.apache.spark.sql.xml.parsers.stax._
import org.apache.spark.sql.xml.util.{InferSchema, ParseModes}

import scala.collection.JavaConversions._
import org.apache.hadoop.fs.Path
import org.slf4j.LoggerFactory

import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.sources.{BaseRelation, InsertableRelation, TableScan}
import org.apache.spark.sql.types._

case class XmlRelation protected[spark] (
    baseRDD: () => RDD[String],
    location: Option[String],
    parseMode: String,
    rootTag: String,
    samplingRatio: Double,
    includeAttributeFlag: Boolean,
    treatEmptyValuesAsNulls: Boolean,
    userSchema: StructType = null)(@transient val sqlContext: SQLContext)
  extends BaseRelation
  with TableScan
  with InsertableRelation {

  private val logger = LoggerFactory.getLogger(XmlRelation.getClass)

  // Parse mode flags
  if (!ParseModes.isValidMode(parseMode)) {
    logger.warn(s"$parseMode is not a valid parse mode. Using ${ParseModes.DEFAULT}.")
  }

  private val failFast = ParseModes.isFailFastMode(parseMode)
  private val dropMalformed = ParseModes.isDropMalformedMode(parseMode)
  private val permissive = ParseModes.isPermissiveMode(parseMode)

  override val schema: StructType = {
    Option(userSchema).getOrElse {
      InferSchema(
        StaxXmlPartialSchemaParser(
          baseRDD(),
          rootTag,
          samplingRatio)(sqlContext))}
  }

  override def buildScan: RDD[Row] = {
    StaxXmlParser(
      baseRDD(),
      schema,
      rootTag)(sqlContext)
  }

  // The function below was borrowed from XMLRelation
  override def insert(data: DataFrame, overwrite: Boolean): Unit = {

    throw new UnsupportedOperationException("Writing XML is currently not supported.")

    val filesystemPath = location match {
      case Some(p) => new Path(p)
      case None =>
        throw new IOException(s"Cannot INSERT into table with no path defined")
    }

    val fs = filesystemPath.getFileSystem(sqlContext.sparkContext.hadoopConfiguration)

    if (overwrite) {
      try {
        fs.delete(filesystemPath, true)
      } catch {
        case e: IOException =>
          throw new IOException(
            s"Unable to clear output directory ${filesystemPath.toString} prior"
              + s" to INSERT OVERWRITE a XML table:\n${e.toString}")
      }
      // Write the data. We assume that schema isn't changed, and we won't update it.
      data.saveAsXmlFile(filesystemPath.toString)
    } else {
      sys.error("XML tables only support INSERT OVERWRITE for now.")
    }
  }
}
