/*
 * Copyright 2017 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *            http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.ml.samples.criteo

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.types._


class CriteoTransformer(inputPath: String,
                        features: CriteoFeatures,
                        numPartitions: Integer,
                        indexer: TrainingIndexer,
                        artifactPath: String,
                        importer: CriteoImporter,
                        vocabularyImporter: VocabularyImporter,
                        outputPath: String)
                       (implicit val spark: SparkSession) {

  def addRankFeatures(cleanedDf: DataFrame,
                      vocabularies: Map[String, DataFrame]): DataFrame = {
    // add the ranking feature values to the cateogrical columns
    features.categoricalRawLabels.
      foldLeft(cleanedDf)((df, col) =>
        df.join(vocabularies(col), df(col) === vocabularies(col)("value-" ++ col))
          .withColumnRenamed("index-" ++ col, features.categoricalLabelMap(col))
      )
  }

  def transform(df: DataFrame): DataFrame = {
    val vocabularies = vocabularyImporter.loadFeatureVocabularies()
    val withCategoryRankings = addRankFeatures(df, vocabularies)

    // select just the output  columns (removing the old categorical values)
    val withTargetFeaturesDf = withCategoryRankings
      .select(features.outputLabels.head, features.outputLabels.tail: _*).
      toDF

    // cast integer columns to floats
    val floatCastDf = features.integralColumns.
      foldLeft(withTargetFeaturesDf)((df, col) =>
        df.withColumn(col, withTargetFeaturesDf(col).cast(FloatType)))
    floatCastDf
  }

  def importTransformAndExport(): Unit = {
    val exporter = new FileExporter(outputPath, "tfrecords")
    val df = importer.criteoImport
    val noNonNullDf = df.na.fill("null")
    val filledDf = noNonNullDf.na.replace(noNonNullDf.columns, Map("" -> "null"))

    val missingReplacer = new CriteoMissingReplacer()
    val averages = missingReplacer.getAverageIntegerFeatures(
      filledDf, features.integerFeatureLabels)
    val replacedDf = missingReplacer.replaceIntegerFeatures(
      filledDf, features.integerFeatureLabels, averages)

    val result = transform(replacedDf)
    exporter.criteoExport(result)
  }

  def apply(): Unit = importTransformAndExport()

}