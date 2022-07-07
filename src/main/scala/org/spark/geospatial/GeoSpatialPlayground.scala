package org.spark.geospatial

import org.apache.sedona.core.serde.SedonaKryoRegistrator
import org.apache.sedona.sql.utils.SedonaSQLRegistrator
import org.apache.spark.SparkConf
import org.apache.spark.serializer.KryoSerializer
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.{array, col, collect_list, count, explode, expr, struct, when}

import java.io.File

object GeoSpatialPlayground {


  def main(args: Array[String]): Unit = {
    val datasetPath = "90per.json"
    val outputPath = "results/"
    runApp(new SparkConf(), datasetPath, outputPath)
  }

  def runApp(sparkConf: SparkConf, datasetPath: String, outputPath: String): Unit = {
    implicit val spark: SparkSession = SparkSession.builder()
      .config(sparkConf)
      .appName("play-geospatial") // Change this to a proper name
      // Enable Sedona custom Kryo serializer
      .config("spark.serializer", classOf[KryoSerializer].getName) // org.apache.spark.serializer.KryoSerializer
      .config("spark.kryo.registrator", classOf[SedonaKryoRegistrator].getName)
      .getOrCreate() // org.apache.sedona.core.serde.SedonaKryoRegistrator

    SedonaSQLRegistrator.registerAll(spark)

    import spark.implicits._
    // not really spark way, but good enough for test task
    downloadFile("https://storage.googleapis.com/pl-spark-assignments/poi-canopy/90per", datasetPath)

    val inputData = spark.read.json(datasetPath)
    val dataWithGeoHash = inputData
      .withColumn("coordinates", when($"polygon".isNull, array(expr("ST_GeomFromWKT(coordinate)")))
        .otherwise(expr("ST_DumpPoints(ST_GeomFromWKT(polygon))")) //can be optimize to take only surface points, not all
      )
      .select(col("venue_id"), col("categories"), explode($"coordinates").as("coordinate"))
      .withColumn("geohash", expr("ST_GeoHash(coordinate, 7)"))

    // TODO transform the data so that each geohash points to the list of POIs that fall within the geohash, using prefix
    val result = dataWithGeoHash
      .groupBy("geohash")
      .agg(count("*").as("count"), collect_list(struct($"venue_id".as("id"), $"categories")).as("venues"))
      .orderBy($"count".desc)
      .limit(1000)
    //        result.printSchema()
    //        result.show(truncate = false)

    val numOfOutputFiles = 1
    result.coalesce(numOfOutputFiles).write.json(outputPath)
  }

  private def downloadFile(url: String, outputPath: String): Unit = {
    val src = scala.io.Source.fromURL(url)
    val file = new File(outputPath)
    file.getParentFile.mkdirs()
    val out = new java.io.FileWriter(file)
    try {
      out.write(src.mkString)
      out.flush()
      out.close()
    } finally {
      out.close()
      src.close()
    }
  }

}
